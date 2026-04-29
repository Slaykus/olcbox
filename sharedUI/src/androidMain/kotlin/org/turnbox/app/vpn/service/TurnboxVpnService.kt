package org.turnbox.app.vpn.service

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import org.turnbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.turnbox.app.data.datasource.LocationsDataSourceImpl
import org.turnbox.app.data.datasource.LocationsRepositoryImpl
import org.turnbox.app.data.model.LocationConfig
import org.turnbox.app.data.repository.LocationsRepository
import org.turnbox.app.vpn.VpnStatus
import java.io.File
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

class TurnboxVpnService : VpnService() {

    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative()
    private external fun getTun2socksStatsNative(): LongArray

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val tunnelMutex = Mutex()
    private val repository: LocationsRepository by lazy {
        LocationsRepositoryImpl(LocationsDataSourceImpl(applicationContext))
    }

    private var startupJob: Job? = null
    private var watchdogJob: Job? = null
    private var retryJob: Job? = null
    private var cleanupJob: Job? = null
    private var generation = 0L

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null
    private var isCallbackRegistered = false
    private var lastMigrationTime: Long = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(network, "Available")
        }

        override fun onLost(network: Network) {
            addLog("Network lost")
            scope.launch {
                delay(NETWORK_LOSS_FALLBACK_DELAY_MS)
                findActiveUpstreamNetwork()?.let { handleNetworkChange(it, "Fallback") }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == currentNetwork || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                handleNetworkChange(network, "Capabilities")
            }
        }

        private fun handleNetworkChange(network: Network, reason: String) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (!caps.isUsableUpstream()) return

            val upstream = findActiveUpstreamNetwork() ?: network
            if (currentNetwork == upstream) return

            updateUnderlyingNetwork(upstream)
            addLog("Network $reason: ${getNetName(upstream)}")

            if (TurnboxVpnState.status.value !is VpnStatus.Connected) return

            val now = System.currentTimeMillis()
            if (now - lastMigrationTime < MIGRATION_DEBOUNCE_MS) return
            lastMigrationTime = now

            startTunnel(isMigration = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Turnbox::VpnWakeLock")

        installMobileCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TurnboxVpnActions.ACTION_STOP_VPN -> {
                addLog("Stop VPN requested")
                cleanup()
                return START_NOT_STICKY
            }

            TurnboxVpnActions.ACTION_START_VPN -> Unit
            else -> {
                cleanup()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (TurnboxVpnState.status.value is VpnStatus.Connected || TurnboxVpnState.status.value is VpnStatus.Connecting) {
            return START_STICKY
        }

        startForeground()
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        registerNetworkMonitor()
        updateUnderlyingNetwork(findActiveUpstreamNetwork())
        startTunnel(isMigration = false)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup(stopService = false)
    }

    override fun onRevoke() {
        addLog("VPN permission revoked")
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    private fun installMobileCallbacks() {
        Mobile.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                return this@TurnboxVpnService.protect(fd.toInt())
            }
        })
        Mobile.setProviders()
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) {
                val line = msg.trimEnd()
                addLog("rtc: $line")
                Log.v("olcrtc", line)
            }
        })
    }

    private fun startTunnel(isMigration: Boolean) {
        startupJob?.cancel()
        retryJob?.cancel()
        val requestedGeneration = ++generation

        startupJob = scope.launch {
            tunnelMutex.withLock {
                coroutineContext.ensureActive()
                if (requestedGeneration != generation) return@withLock

                val active = repository.getActiveLocation()
                val location = active?.location?.normalized()
                if (location == null || !location.isComplete()) {
                    setStatus(VpnStatus.Error("No active location"))
                    updateNotification("Add a location first")
                    stopTransportProcesses(closeTun = true)
                    return@withLock
                }

                if (isMigration && vpnInterface != null && tun2socksThread?.isAlive == true) {
                    reconnectTransport(location, requestedGeneration)
                } else {
                    startFullTunnel(location, requestedGeneration, isMigration)
                }
            }
        }
    }

    private suspend fun reconnectTransport(location: LocationConfig, requestedGeneration: Long) {
        setStatus(VpnStatus.Reconnecting)
        updateNotification("Reconnecting...")
        stopMobile()
        delay(TRANSPORT_RESTART_GRACE_MS)
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val upstream = findActiveUpstreamNetwork()
        if (upstream != null) {
            updateUnderlyingNetwork(upstream)
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
        }

        if (startMobile(location)) {
            upstream?.let { bindProcessToNetwork(it, "Keeping olcRTC bound to ${getNetName(it)}") }
            setStatus(VpnStatus.Connected)
            updateNotification("VPN Connected")
            addLog("Transport reconnected")
        } else {
            unbindProcessFromNetwork()
            scheduleRetry(fullRestart = true)
        }
    }

    private suspend fun startFullTunnel(
        location: LocationConfig,
        requestedGeneration: Long,
        isMigration: Boolean
    ) {
        setStatus(if (isMigration) VpnStatus.Reconnecting else VpnStatus.Connecting)
        updateNotification("Connecting...")
        stopTransportProcesses(closeTun = true)
        delay(TRANSPORT_RESTART_GRACE_MS)
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val upstream = findActiveUpstreamNetwork()
        if (upstream != null) {
            updateUnderlyingNetwork(upstream)
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
        } else {
            addLog("No validated upstream network")
        }

        if (!startMobile(location)) {
            unbindProcessFromNetwork()
            scheduleRetry(fullRestart = true)
            return
        }

        delay(TUNNEL_HANDOFF_DELAY_MS)
        coroutineContext.ensureActive()

        val pfd = establishSystemVpnTunnel()
        if (pfd == null) {
            stopMobile()
            scheduleRetry(fullRestart = true)
            return
        }

        vpnInterface = pfd
        currentNetwork?.let { bindProcessToNetwork(it, "Keeping olcRTC bound to ${getNetName(it)}") }
        if (!startTun2socks(pfd)) {
            stopTransportProcesses(closeTun = true)
            scheduleRetry(fullRestart = true)
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        setStatus(VpnStatus.Connected)
        updateNotification("VPN Connected")
        addLog("VPN tunnel established")
        startWatchdog()
    }

    private fun startMobile(location: LocationConfig): Boolean {
        return try {
            installMobileCallbacks()
            addLog("Starting olcRTC provider=${location.bypassProvider}, room=${location.id}")
            Mobile.start(
                location.bypassProvider,
                location.id,
                location.key,
                LOCAL_SOCKS_PORT.toLong(),
                "",
                ""
            )
            Mobile.waitReady(MOBILE_READY_TIMEOUT_MS)
            addLog("olcRTC ready on 127.0.0.1:$LOCAL_SOCKS_PORT")
            true
        } catch (e: Exception) {
            addLog("olcRTC start failed: ${e.message}")
            stopMobile()
            setStatus(VpnStatus.Error(e.message ?: "Transport failed"))
            updateNotification("Connection failed")
            false
        }
    }

    private fun startTun2socks(pfd: ParcelFileDescriptor): Boolean {
        return try {
            if (!ensureNativeLibrariesLoaded()) {
                addLog("tun2socks native libraries are unavailable")
                setStatus(VpnStatus.Error("tun2socks native libraries are unavailable"))
                updateNotification("Tunnel failed")
                return false
            }

            val nativeFd = ParcelFileDescriptor.dup(pfd.fileDescriptor).detachFd()
            val configFile = writeTun2socksConfig()
            tun2socksThread = thread(name = "TurnboxTun2Socks", isDaemon = true) {
                val result = startTun2socksNative(configFile.absolutePath, nativeFd)
                if (TurnboxVpnState.status.value !is VpnStatus.Stopping && result != 0) {
                    addLog("tun2socks exited with code $result")
                } else {
                    addLog("tun2socks stopped")
                }
            }
            true
        } catch (e: Exception) {
            addLog("tun2socks start failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "tun2socks failed"))
            updateNotification("Tunnel failed")
            false
        }
    }

    private fun establishSystemVpnTunnel(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Turnbox VPN")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4_ADDRESS, IPV4_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(MAPDNS_ADDRESS)
                .setBlocking(true)

            runCatching {
                builder.addDisallowedApplication(packageName)
            }.onFailure {
                addLog("Failed to exclude Turnbox from VPN: ${it.message}")
            }

            currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            builder.establish()
        } catch (e: Exception) {
            addLog("VPN establish failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "VPN establish failed"))
            updateNotification("VPN tunnel error")
            null
        }
    }

    private fun writeTun2socksConfig(): File {
        val file = File(filesDir, TUN2SOCKS_CONFIG_FILE_NAME)
        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: $TUN_MTU
              multi-queue: false
              ipv4: $TUN_IPV4_ADDRESS

            socks5:
              address: 127.0.0.1
              port: $LOCAL_SOCKS_PORT
              udp: 'tcp'
              pipeline: false

            mapdns:
              address: $MAPDNS_ADDRESS
              port: 53
              network: $MAPDNS_NETWORK
              netmask: $MAPDNS_NETMASK
              cache-size: 10000

            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              max-session-count: 1200
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && TurnboxVpnState.status.value is VpnStatus.Connected) {
                delay(WATCHDOG_INTERVAL_MS)
                when {
                    !Mobile.isRunning() -> {
                        addLog("Watchdog: olcRTC stopped")
                        scheduleRetry(fullRestart = false)
                        return@launch
                    }

                    tun2socksThread?.isAlive != true -> {
                        addLog("Watchdog: tun2socks stopped")
                        scheduleRetry(fullRestart = true)
                        return@launch
                    }
                }
            }
        }
    }

    private fun scheduleRetry(fullRestart: Boolean) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RESTART_DELAY_MS)
            if (fullRestart) {
                vpnInterface?.close()
                vpnInterface = null
            }
            startTunnel(isMigration = true)
        }
    }

    private fun cleanup(stopService: Boolean = true) {
        val status = TurnboxVpnState.status.value
        if (status is VpnStatus.Disconnected && vpnInterface == null && tun2socksThread == null) {
            if (stopService) stopSelf()
            return
        }
        if (status is VpnStatus.Stopping && cleanupJob?.isActive == true) return

        generation++
        setStatus(VpnStatus.Stopping)
        startupJob?.cancel()
        watchdogJob?.cancel()
        retryJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }

        if (isCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            isCallbackRegistered = false
        }
        updateUnderlyingNetwork(null)
        unbindProcessFromNetwork()

        stopTun2socks()
        cleanupVpnInterface()
        tun2socksThread?.interrupt()
        tun2socksThread = null
        setStatus(VpnStatus.Disconnected)
        addLog("VPN stopped")

        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            try {
                stopMobile()
            } finally {
                if (stopService) stopSelf()
            }
        }
    }

    private fun stopTransportProcesses(closeTun: Boolean) {
        stopMobile()
        stopTun2socks()
        if (closeTun) cleanupVpnInterface()
        tun2socksThread?.interrupt()
        tun2socksThread = null
    }

    private fun stopTun2socks() {
        if (nativeLibrariesLoaded) {
            runCatching { stopTun2socksNative() }
        }
    }

    private fun stopMobile() {
        runCatching { Mobile.stop() }
    }

    private fun cleanupVpnInterface() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
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
            addLog("Network monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Network monitor failed", e)
        }
    }

    private fun findActiveUpstreamNetwork(): Network? {
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.isUsableUpstream()) return@mapNotNull null
            network to caps
        }

        val active = connectivityManager.activeNetwork
        candidates.firstOrNull { (network, caps) ->
            network == active && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.let { return it.first }
        candidates.firstOrNull { (network, _) -> network == active }?.let { return it.first }
        candidates.firstOrNull { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.let { return it.first }
        candidates.firstOrNull { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.let { return it.first }
        candidates.firstOrNull { (_, caps) ->
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.let { return it.first }
        return candidates.firstOrNull()?.first
    }

    private fun NetworkCapabilities.isUsableUpstream(): Boolean {
        return !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        currentNetwork = network
        setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
    }

    private fun bindProcessToNetwork(network: Network?, successLog: String? = null) {
        try {
            connectivityManager.bindProcessToNetwork(network)
            if (successLog != null) addLog(successLog)
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private fun unbindProcessFromNetwork() {
        bindProcessToNetwork(null)
    }

    private fun getNetName(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network)
        return if (caps != null) getNetName(caps) else "Other"
    }

    private fun getNetName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }

    private fun startForeground(statusText: String = "Protecting your connection") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Turnbox VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Turnbox VPN")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, TurnboxVpnService::class.java).apply { action = ACTION_STOP_VPN },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun getAppPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setStatus(status: VpnStatus) {
        TurnboxVpnState.setStatus(status)
    }

    companion object {
        @Volatile
        private var nativeLibrariesLoaded = false
        private var nativeLibrariesLoadError: Throwable? = null
        private val nativeLibrariesLock = Any()

        private fun ensureNativeLibrariesLoaded(): Boolean {
            if (nativeLibrariesLoaded) return true
            nativeLibrariesLoadError?.let { return false }

            return synchronized(nativeLibrariesLock) {
                if (nativeLibrariesLoaded) {
                    true
                } else {
                    try {
                        System.loadLibrary("hev-socks5-tunnel")
                        System.loadLibrary("turnbox_tun2socks")
                        nativeLibrariesLoaded = true
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        nativeLibrariesLoadError = e
                        Log.e(TAG, "Failed to load native tun2socks libraries", e)
                        false
                    }
                }
            }
        }

        const val ACTION_START_VPN = TurnboxVpnActions.ACTION_START_VPN
        const val ACTION_STOP_VPN = TurnboxVpnActions.ACTION_STOP_VPN

        private const val LOCAL_SOCKS_PORT = 10808
        private const val MOBILE_READY_TIMEOUT_MS = 25_000L
        private const val TRANSPORT_RESTART_GRACE_MS = 300L
        private const val TUNNEL_HANDOFF_DELAY_MS = 300L
        private const val RESTART_DELAY_MS = 2_000L
        private const val MIGRATION_DEBOUNCE_MS = 500L
        private const val NETWORK_LOSS_FALLBACK_DELAY_MS = 300L
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        private const val IPV4_PREFIX_LENGTH = 24
        private const val MAPDNS_ADDRESS = "1.1.1.1"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
        private const val NOTIFICATION_CHANNEL_ID = "turnbox_vpn"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "TurnboxVpnService"

        private fun addLog(msg: String) {
            TurnboxVpnState.addLog(msg)
        }
    }
}
