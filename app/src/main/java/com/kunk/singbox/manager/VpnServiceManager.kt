package com.kunk.singbox.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService

/**
 *
 *
 */
object VpnServiceManager {
    private const val TAG = "VpnServiceManager"

    @Volatile
    private var cachedTunEnabled: Boolean? = null

    @Volatile
    private var lastTunCheckTime: Long = 0L

    private const val CACHE_VALIDITY_MS = 5_000L

    /**
     *
     */
    fun isRunning(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(
            PREFS_VPN_STATE,
            Context.MODE_PRIVATE
        )
        val persistedActive = prefs.getBoolean(KEY_VPN_ACTIVE, false)
        val pending = prefs.getString(KEY_VPN_PENDING, "") ?: ""

        if (pending.isNotEmpty()) {
            return persistedActive || pending == "starting"
        }

        if (persistedActive) {
            return true
        }

        return SingBoxRemote.isRunning.value
    }

    private const val PREFS_VPN_STATE = "vpn_state"
    private const val KEY_VPN_ACTIVE = "vpn_active"
    private const val KEY_VPN_PENDING = "vpn_pending"

    /**
     */
    fun isStarting(): Boolean {
        return SingBoxRemote.isStarting.value
    }

    /**
     *
     * @return "tun" | "proxy" | null
     */
    fun getActiveService(context: Context): String? {
        if (!isRunning(context)) return null

        return if (isTunEnabled()) "tun" else "proxy"
    }

    /**
     *
     */
    fun toggleVpn(context: Context): Result<Unit> {
        return if (isRunning(context)) {
            stopVpn(context)
        } else {
            startVpn(context)
        }
    }

    /**
     *
     */
    fun startVpn(context: Context): Result<Unit> {
        val tunEnabled = isTunEnabled(context)
        return startVpn(context, tunEnabled)
    }

    /**
     *
     */
    fun startVpn(context: Context, tunMode: Boolean): Result<Unit> {
        Log.d(TAG, "startVpn: tunMode=$tunMode")

        val serviceClass = if (tunMode) {
            SingBoxService::class.java
        } else {
            ProxyOnlyService::class.java
        }

        val intent = Intent(context, serviceClass).apply {
            action = if (tunMode) {
                SingBoxService.ACTION_START
            } else {
                ProxyOnlyService.ACTION_START
            }
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Unit
        }
            .onFailure { Log.e(TAG, "Failed to start VPN service", it) }
    }

    /**
     *
     */
    fun stopVpn(context: Context): Result<Unit> {
        Log.d(TAG, "stopVpn")

        return runCatching {
            val mode = VpnStateStore.getMode()
            val stopTun = when (mode) {
                VpnStateStore.CoreMode.VPN -> true
                VpnStateStore.CoreMode.PROXY -> false
                VpnStateStore.CoreMode.NONE -> isTunEnabled(context)
            }

            val intent = if (stopTun) {
                Intent(context, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_STOP
                }
            } else {
                Intent(context, ProxyOnlyService::class.java).apply {
                    action = ProxyOnlyService.ACTION_STOP
                }
            }
            context.startService(intent)
            Unit
        }
            .onFailure { Log.e(TAG, "Failed to stop VPN service", it) }
    }

    /**
     *
     */
    fun restartVpn(context: Context) {
        Log.d(TAG, "restartVpn")

        val currentTunMode = isTunEnabled(context)
        stopVpn(context)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startVpn(context, currentTunMode)
        }, 500)
    }

    /**
     *
     */
    private fun isTunEnabled(context: Context? = null): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedTunEnabled

        if (cached != null && (now - lastTunCheckTime) < CACHE_VALIDITY_MS) {
            return cached
        }

        if (context != null) {
            val prefs = context.applicationContext.getSharedPreferences(
                "com.kunk.singbox_preferences",
                Context.MODE_PRIVATE
            )
            val tunEnabled = prefs.getBoolean("tun_enabled", true)

            cachedTunEnabled = tunEnabled
            lastTunCheckTime = now

            return tunEnabled
        }

        return cached ?: true
    }

    fun refreshTunSetting(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(
            "com.kunk.singbox_preferences",
            Context.MODE_PRIVATE
        )
        val tunEnabled = prefs.getBoolean("tun_enabled", true)

        cachedTunEnabled = tunEnabled
        lastTunCheckTime = System.currentTimeMillis()

        Log.d(TAG, "refreshTunSetting: tunEnabled=$tunEnabled")
    }

    /**
     */
    fun getCurrentConfig(context: Context): String {
        return buildString {
            append("isRunning: ${isRunning(context)}\n")
            append("isStarting: ${isStarting()}\n")
            append("activeService: ${getActiveService(context)}\n")
            append("cachedTunEnabled: $cachedTunEnabled\n")
            append("activeLabel: ${SingBoxRemote.activeLabel.value}\n")
        }
    }
}
