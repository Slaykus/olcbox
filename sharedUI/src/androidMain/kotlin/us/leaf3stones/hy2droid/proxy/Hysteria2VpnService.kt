package us.leaf3stones.hy2droid.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import engine.Engine
import engine.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.turnbox.app.data.model.TurnConfig
import org.turnbox.app.data.repository.HysteriaConfigRepository
import org.turnbox.app.vpn.data.KEY_IS_VPN_CONFIG_READY
import org.turnbox.app.vpn.data.KEY_VPN_CONFIG_PATH
import org.turnbox.app.vpn.data.vpnPrefDataStore
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

class Hysteria2VpnService : VpnService() {

    private var hysteriaProcess: Process? = null
    private var hysteriaLoggingThread: Thread? = null
    private var turnProcess: Process? = null
    private var turnLoggingThread: Thread? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val startMutex = Mutex()

    private var startupJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastConfigPath: String? = null
    private var lastMigrationTime: Long = 0L
    private var isRunning = false

    @Volatile
    private var isHysteriaSocksReady = false

    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null
    private var isCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(network, "Available")
        }

        override fun onLost(network: Network) {
            addLog("❌ Network LOST detected")
            val active = connectivityManager.activeNetwork
            if (active != null && active != network) {
                handleNetworkChange(active, "Fallback after LOSS")
            } else {
                addLog("⚠️ No active network after loss — waiting...")
                scope.launch {
                    delay(1500)
                    val a = connectivityManager.activeNetwork
                    if (a != null) handleNetworkChange(a, "Delayed fallback")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (currentNetwork == network) {
                handleNetworkChange(network, "Capabilities changed")
            }
        }

        private fun handleNetworkChange(network: Network, reason: String) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) return

            val netName = getNetName(caps)
            val isNewNetwork = currentNetwork != network
            if (!isNewNetwork && !reason.contains("LOSS")) return

            if (isNewNetwork || reason.contains("Fallback") || reason.contains("LOSS")) {
                addLog("🔄 $reason: $netName")

                currentNetwork = network
                setUnderlyingNetworks(arrayOf(network))
                try {
                    // Это ломает java.net.Socket() для 127.0.0.1, но мы теперь читаем логи!
                    connectivityManager.bindProcessToNetwork(network)
                    addLog("✅ Bound to $netName")
                } catch (e: Exception) {
                    Log.w(TAG, "bind failed", e)
                }

                val now = System.currentTimeMillis()
                if (now - lastMigrationTime < 3000) {
                    addLog("⏳ Migration throttled")
                    return
                }
                lastMigrationTime = now

                lastConfigPath?.let { startVpnChecked(true, it, isMigration = true) }
            }
        }

        private fun getNetName(caps: NetworkCapabilities): String = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            else -> "Other"
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Turnbox::VpnWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VPN) {
            addLog("🛑 Stop VPN requested from notification")
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        val isStart = intent?.action == ACTION_START_VPN
        if (!isStart) {
            cleanup(); stopSelf(); return START_NOT_STICKY
        }

        if (isRunning) return START_STICKY

        startForeground()
        wakeLock?.acquire(24 * 60 * 60 * 1000L)

        scope.launch {
            val pref = vpnPrefDataStore.data.first()
            val ready = pref[KEY_IS_VPN_CONFIG_READY] ?: false
            val path = pref[KEY_VPN_CONFIG_PATH] ?: ""

            connectivityManager.bindProcessToNetwork(null)
            registerNetworkMonitor()
            startVpnChecked(ready, path, isMigration = false)
        }
        return START_STICKY
    }

    private fun registerNetworkMonitor() {
        if (isCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isCallbackRegistered = true
            addLog("📡 Network monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Monitor error", e)
        }
    }

    private fun getAppPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun startForeground(statusText: String = "Protecting your connection") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel("CHANNEL_ID", "Turnbox VPN", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                channel
            )
        }

        val stopIntent =
            Intent(this, Hysteria2VpnService::class.java).apply { action = ACTION_STOP_VPN }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, "CHANNEL_ID")
            .setContentTitle("Turnbox VPN")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this, 100, notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun updateNotification(status: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val stopIntent =
            Intent(this, Hysteria2VpnService::class.java).apply { action = ACTION_STOP_VPN }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, "CHANNEL_ID")
            .setContentTitle("Turnbox VPN")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(100, notif)
    }

    private fun startVpnChecked(
        isConfigReady: Boolean,
        configPath: String,
        isMigration: Boolean = false
    ) {
        if (!isConfigReady || configPath.isBlank()) return
        lastConfigPath = configPath

        startupJob?.cancel()
        watchdogJob?.cancel()

        startupJob = scope.launch {
            startMutex.withLock {
                addLog(if (isMigration) "🔄 Reconnecting tunnel..." else "🚀 Starting VPN...")
                updateNotification("Connecting...")

                try {
                    Engine.stop()
                } catch (_: Exception) {
                }
                stopTransportProcesses()
                delay(1000)

                val repo = configRepository ?: run {
                    addLog("❌ ConfigRepository is NULL")
                    return@withLock
                }

                val selectedTurnType = repo.getSelectedTurnType()
                val baseTurnConfig = repo.loadTurnConfig(selectedTurnType)
                val selectedHysteriaId = repo.getSelectedHysteriaId()
                val hysteriaConfig = repo.loadHysteriaConfig(selectedHysteriaId)
                val turnConfig = baseTurnConfig.copy(peer = hysteriaConfig.server)

                if (turnConfig.enabled) {
                    startTurnInternal(turnConfig)
                    delay(if (isMigration) 1500L else 3000L)
                }

                isHysteriaSocksReady = false
                startHysteriaInternal(configPath)
                addLog("⏳ Waiting for Hysteria SOCKS5...")

                // Надежное ожидание готовности SOCKS5 через логи (таймаут 25 сек)
                var waited = 0
                while (!isHysteriaSocksReady && waited < 25000) {
                    if (hysteriaProcess?.isProcessAlive() == false) {
                        addLog("❌ Hysteria process crashed during startup!")
                        break
                    }
                    delay(500)
                    waited += 500
                }

                if (isHysteriaSocksReady) {
                    delay(500)
                    addLog("✅ SOCKS5 Ready")

                    val pfd = establishSystemVpnTunnel()
                    if (pfd != null) {
                        val fd = pfd.detachFd()
                        val key = Key().apply {
                            mark = 0
                            mtu = 1250
                            device = "fd://$fd"
                            `interface` = ""
                            logLevel = "info"
                            proxy = "socks5://127.0.0.1:1080"
                            restAPI = ""
                            tcpSendBufferSize = ""
                            tcpReceiveBufferSize = ""
                            tcpModerateReceiveBuffer = false
                        }

                        try {
                            Engine.insert(key)
                            Engine.start()
                            isRunning = true
                            _isConnected.value = true
                            addLog("✅ VPN Tunnel established")
                            updateNotification("VPN Connected")
                            startWatchdog()
                        } catch (e: Exception) {
                            addLog("❌ Tun2Socks start error: ${e.message}")
                            updateNotification("Connection Error")
                            try {
                                ParcelFileDescriptor.adoptFd(fd).close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                } else {
                    addLog("❌ Hysteria SOCKS5 connection timed out!")
                    updateNotification("Retrying connection...")
                    scope.launch {
                        delay(3000)
                        startVpnChecked(true, configPath, true)
                    }
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && isRunning) {
                delay(5000)

                var needsRestart = false
                if (turnProcess?.isProcessAlive() == false) {
                    addLog("⚠️ Watchdog: TURN process died!")
                    needsRestart = true
                }
                if (hysteriaProcess?.isProcessAlive() == false) {
                    addLog("⚠️ Watchdog: Hysteria process died!")
                    needsRestart = true
                }

                if (needsRestart) {
                    addLog("🔄 Watchdog triggers VPN restart...")
                    lastConfigPath?.let { startVpnChecked(true, it, isMigration = true) }
                    break
                }
            }
        }
    }

    private fun Process.isProcessAlive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.isAlive
        } else {
            try {
                this.exitValue(); false
            } catch (e: IllegalThreadStateException) {
                true
            }
        }
    }

    private fun startTurnInternal(config: TurnConfig) {
        addLog("🚀 Starting turn tunnel with peer: ${config.peer}")
        val cmd = mutableListOf<String>().apply {
            add(File(applicationInfo.nativeLibraryDir, "libvkturn.so").absolutePath)
            add("-peer"); add(config.peer)
            if (config.link.isNotBlank()) {
                add(if (config.link.contains("yandex")) "-yandex-link" else "-vk-link")
                add(config.link)
            }
            add("-listen"); add(config.listen)
            add("-n"); add(config.threads.toString())
            if (config.udp) add("-udp")
            if (config.noDtls) add("-no-dtls")
        }
        turnProcess = ProcessBuilder(cmd).redirectErrorStream(true).start()
        turnLoggingThread = thread(name = "TurnLog") {
            try {
                BufferedReader(InputStreamReader(turnProcess?.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.contains("Established") || line!!.contains("relayed-address")) {
                            addLog("✅ TURN OK")
                        }
                        Log.v("vk-turn", line!!)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun startHysteriaInternal(configPath: String) {
        val cmd = listOf(
            File(applicationInfo.nativeLibraryDir, "libhysteria.so").absolutePath,
            "-c", configPath
        )
        hysteriaProcess = ProcessBuilder(cmd).redirectErrorStream(true).start()
        hysteriaLoggingThread = thread(name = "HyLog") {
            try {
                BufferedReader(InputStreamReader(hysteriaProcess?.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        // НАДЕЖНАЯ ПРОВЕРКА готовности:
                        if (line!!.contains("SOCKS5 server listening") || line!!.contains("HTTP proxy server listening")) {
                            isHysteriaSocksReady = true
                        }
                        if (line!!.contains("connected")) {
                            addLog("✅ HY2 Connected")
                        }
                        Log.v("hysteria", line!!)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun establishSystemVpnTunnel(): ParcelFileDescriptor? {
        val builder = Builder()
            .setMtu(1250)
            .addAddress("10.0.88.88", 16)
            .addDnsServer("1.1.1.1")
            .addDisallowedApplication(packageName)
            .addRoute("0.0.0.0", 0)

        listOf(
            "com.vkontakte.android", "ru.yandex.searchplugin", "ru.yandex.yandexbrowser",
            "com.yandex.browser", "com.android.vending", "com.google.android.gms",
            "com.android.captiveportallogin"
        ).forEach {
            try {
                builder.addDisallowedApplication(it)
            } catch (_: Exception) {
            }
        }

        currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }

        return builder.establish()
    }

    private fun stopTransportProcesses() {
        addLog("🛑 Stopping transport processes...")

        hysteriaProcess?.destroy()
        hysteriaProcess = null
        hysteriaLoggingThread?.interrupt()
        hysteriaLoggingThread = null

        turnProcess?.destroy()
        turnProcess = null
        turnLoggingThread?.interrupt()
        turnLoggingThread = null
    }

    private fun cleanup() {
        isRunning = false
        _isConnected.value = false
        startupJob?.cancel()
        watchdogJob?.cancel()

        wakeLock?.let { if (it.isHeld) it.release() }

        scope.launch {
            startMutex.withLock {
                try {
                    Engine.stop()
                } catch (_: Exception) {
                }
                stopTransportProcesses()
            }
        }
        if (isCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
            isCallbackRegistered = false
        }
        connectivityManager.bindProcessToNetwork(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    companion object {
        const val ACTION_START_VPN =
            "us.leaf3stones.hy2droid.proxy.Hysteria2VpnService.ACTION_START_VPN"
        const val ACTION_STOP_VPN =
            "us.leaf3stones.hy2droid.proxy.Hysteria2VpnService.ACTION_STOP_VPN"

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs = _logs.asStateFlow()

        private val _isConnected = MutableStateFlow(false)
        val isConnected = _isConnected.asStateFlow()

        var configRepository: HysteriaConfigRepository? = null

        fun addLog(msg: String) {
            Log.d(TAG, msg)
            _logs.update { (it + msg).takeLast(120) }
        }

        private const val TAG = "Hysteria2VpnService"
    }
}