package org.turnbox.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import org.turnbox.app.data.model.LocationConfig
import org.turnbox.app.vpn.service.TurnboxVpnActions
import org.turnbox.app.vpn.service.TurnboxVpnState

class AndroidVpnManager(private val context: Context) : VpnManager {
    override val logs: StateFlow<List<String>> = TurnboxVpnState.logs
    override val status: StateFlow<VpnStatus> = TurnboxVpnState.status
    override val isConnected: StateFlow<Boolean> = TurnboxVpnState.isConnected

    override fun needsPermission(): Boolean = VpnService.prepare(context) != null

    override fun startVpn() {
        val intent = Intent().apply {
            setClassName(context.packageName, TurnboxVpnActions.SERVICE_CLASS_NAME)
            action = TurnboxVpnActions.ACTION_START_VPN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stopVpn() {
        val intent = Intent().apply {
            setClassName(context.packageName, TurnboxVpnActions.SERVICE_CLASS_NAME)
            action = TurnboxVpnActions.ACTION_STOP_VPN
        }
        context.startService(intent)
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        return checkConnection(locationConfig)
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            isVpnAlreadyRunning = TurnboxVpnState.isConnected.value
        )
    }
}
