package com.kunk.singbox.service.manager

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.SelectorManager as CoreSelectorManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.VpnKeepaliveWorker
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.service.network.NetworkManager
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.utils.NetworkClient
import io.nekohasekai.libbox.InterfaceUpdateListener
import kotlinx.coroutines.*

/**
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * - 鐎殿喖鍊归鐐哄礂閹惰姤锛?
 * 注释已清理。
 */
class ShutdownManager(
    private val context: Context,
    private val cleanupScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ShutdownManager"
        private const val FAST_PORT_RELEASE_WAIT_MS = 1500L
    }

    /**
     * 注释已清理。
     */
    interface Callbacks {
        // 注释已清理。
        fun updateServiceState(state: ServiceState)
        fun updateTileState()
        fun stopForegroundService()
        fun stopSelf()

        fun cancelStartVpnJob(): Job?
        fun cancelVpnHealthJob()
        fun cancelRemoteStateUpdateJob()
        fun cancelRouteGroupAutoSelectJob()

        // 注释已清理。
        fun stopForeignVpnMonitor()
        fun tryClearRunningServiceForLibbox()
        fun unregisterScreenStateReceiver()
        fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?)

        // 注释已清理。
        fun isServiceRunning(): Boolean
        fun getVpnInterface(): ParcelFileDescriptor?
        fun getCurrentInterfaceListener(): InterfaceUpdateListener?
        fun getConnectivityManager(): ConnectivityManager?

        // 注释已清理。
        fun setVpnInterface(fd: ParcelFileDescriptor?)
        fun setIsRunning(running: Boolean)
        fun setRealTimeNodeName(name: String?)
        fun setVpnLinkValidated(validated: Boolean)
        fun setNoPhysicalNetworkWarningLogged(logged: Boolean)
        fun setDefaultInterfaceName(name: String)
        fun setNetworkCallbackReady(ready: Boolean)
        fun setLastKnownNetwork(network: android.net.Network?)
        fun clearUnderlyingNetworks()

        // 注释已清理。
        fun getPendingStartConfigPath(): String?
        fun clearPendingStartConfigPath()
        fun startVpn(configPath: String)

        fun hasExistingTunInterface(): Boolean
    }

    /**
     * 注释已清理。
     */
    data class ShutdownOptions(
        val stopService: Boolean,
        val preserveTunInterface: Boolean = !stopService,
        val proxyPort: Int = 0,
        val strictPortRelease: Boolean = false
    )

    /**
     * 注释已清理。
     */
    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
    fun stopVpn(
        options: ShutdownOptions,
        coreManager: CoreManager,
        commandManager: CommandManager,
        trafficMonitor: TrafficMonitor,
        networkManager: NetworkManager?,
        notificationManager: VpnNotificationManager,
        selectorManager: SelectorManager,
        platformInterfaceImpl: PlatformInterfaceImpl,
        callbacks: Callbacks
    ): Job {
        val stopService = options.stopService
        val proxyPort = options.proxyPort

        val jobToJoin = callbacks.cancelStartVpnJob()
        callbacks.cancelVpnHealthJob()
        callbacks.cancelRemoteStateUpdateJob()
        callbacks.cancelRouteGroupAutoSelectJob()

        VpnKeepaliveWorker.cancel(context)
        Log.i(TAG, "VPN keepalive worker cancelled")

        notificationManager.resetState()

        // 注释已清理。
        trafficMonitor.stop()

        // 注释已清理。
        networkManager?.reset()

        // 注释已清理。
        callbacks.stopForeignVpnMonitor()

        callbacks.setVpnLinkValidated(false)
        callbacks.setNoPhysicalNetworkWarningLogged(false)
        callbacks.setDefaultInterfaceName("")

        if (stopService) {
            callbacks.setNetworkCallbackReady(false)
            callbacks.setLastKnownNetwork(null)
            callbacks.clearUnderlyingNetworks()
        } else {
            callbacks.setNetworkCallbackReady(false)
        }

        callbacks.tryClearRunningServiceForLibbox()

        // 注释已清理。

        CoreSelectorManager.clear()
        selectorManager.clear()

        Log.i(TAG, "stopVpn(stopService=$stopService, proxyPort=$proxyPort)")

        // 注释已清理。
        callbacks.setRealTimeNodeName(null)
        callbacks.setIsRunning(false)
        NetworkClient.onVpnStateChanged(false)

        val listener = callbacks.getCurrentInterfaceListener()

        val interfaceToClose: ParcelFileDescriptor?
        if (stopService) {
            interfaceToClose = callbacks.getVpnInterface()
            callbacks.setVpnInterface(null)
            coreManager.setVpnInterface(null)
        } else {
            interfaceToClose = null
            Log.i(TAG, "Keeping vpnInterface for reuse")
        }

        // 注释已清理。
        if (stopService) {
            coreManager.releaseLocks()
            callbacks.unregisterScreenStateReceiver()
        }

        return cleanupScope.launch(NonCancellable) {
            try {
                jobToJoin?.join()
            } catch (_: Exception) {}

            if (stopService) {
                withContext(Dispatchers.Main) {
                    callbacks.stopForegroundService()
                    runCatching {
                        val manager = context.getSystemService(NotificationManager::class.java)
                        manager.cancel(VpnNotificationManager.NOTIFICATION_ID)
                    }
                    VpnTileService.persistVpnState(context, false)
                    VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
                    VpnTileService.persistVpnPending(context, "")
                    callbacks.updateServiceState(ServiceState.STOPPED)
                    callbacks.updateTileState()
                }
            }

            // 注释已清理。
            val serviceCloseStart = SystemClock.elapsedRealtime()
            runCatching { coreManager.stopService() }
                .onFailure { e -> Log.w(TAG, "CoreManager.stopService failed: ${e.message}") }
            Log.i(TAG, "CoreManager service stopped in ${SystemClock.elapsedRealtime() - serviceCloseStart}ms")

            commandManager.stopAndWaitPortRelease(
                proxyPort = proxyPort,
                waitTimeoutMs = FAST_PORT_RELEASE_WAIT_MS,
                forceKillOnTimeout = stopService, // ·庣懓鑻崣蹇涘磻濠婂嫷鍓鹃柡·硾瀹搁亶宕氶懜鍨祷閺夆晜·撻埢鑲╂兜椤旇崵绠界紒鏃戝灠瑜版盯鏌屾繝·╂澒
                enforceReleaseOnTimeout = false
            ).onFailure { e ->
                Log.w(TAG, "Error closing command server/client", e)
            }

            if (stopService) {
                try {
                    platformInterfaceImpl.closeDefaultInterfaceMonitor(listener)
                } catch (_: Exception) {}
            }

            try {
                withTimeout(2000L) {
                    if (interfaceToClose != null) {
                        try { interfaceToClose.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Graceful close failed or timed out", e)
            }

            withContext(Dispatchers.Main) {
                if (stopService) {
                    callbacks.stopSelf()
                    Log.i(TAG, "VPN stopped")
                } else {
                    Log.i(TAG, "Config reload: boxService closed, keeping TUN and foreground")
                }
            }

            val startAfterStop = callbacks.getPendingStartConfigPath()
            callbacks.clearPendingStartConfigPath()

            if (!startAfterStop.isNullOrBlank()) {

                val hasExistingTun = callbacks.hasExistingTunInterface()
                if (!hasExistingTun) {
                    waitForSystemVpnDown(callbacks.getConnectivityManager(), 1500L)
                } else {
                    Log.i(TAG, "Skipping waitForSystemVpnDown: TUN interface preserved")
                }
                withContext(Dispatchers.Main) {
                    callbacks.startVpn(startAfterStop)
                }
            }
        }
    }

    private suspend fun waitForSystemVpnDown(cm: ConnectivityManager?, timeoutMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || cm == null) return

        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val hasVpn = runCatching {
                @Suppress("DEPRECATION")
                cm.allNetworks.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            }.getOrDefault(false)

            if (!hasVpn) return
            delay(50)
        }
    }
}
