package com.kunk.singbox.service.manager

import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.repository.LogRepository
import kotlinx.coroutines.CoroutineScope

/**
 * 注释已清理。
 *
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 */
class BackgroundPowerManager(
    @Suppress("unused")
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "BackgroundPowerManager"

        /* 注释已清理。 */
        const val DEFAULT_BACKGROUND_THRESHOLD_MS = 30 * 60 * 1000L

        /* 注释已清理。 */
        const val MIN_THRESHOLD_MS = 5 * 60 * 1000L

        /* 注释已清理。 */
        const val MAX_THRESHOLD_MS = 2 * 60 * 60 * 1000L

        /* 注释已清理。 */
        private const val MIN_RECOVERY_AWAY_MS = 1_000L

        /* 注释已清理。 */
        private const val FORCE_RECOVERY_AWAY_MS_APP_FOREGROUND = 3_000L

        /* 注释已清理。 */
        private const val FORCE_RECOVERY_AWAY_MS_SCREEN_ON = 8_000L

        /* 注释已清理。 */
        private const val RETURN_RECOVERY_COALESCE_MS = 2_500L
    }

    /**
     * 注释已清理。
     */
    enum class PowerMode {
        NORMAL,
        POWER_SAVING
    }

    /**
     * 注释已清理。
     */
    interface Callbacks {
        /* 注释已清理。 */
        val isVpnRunning: Boolean

        /* 注释已清理。 */
        fun suspendNonEssentialProcesses()

        /* 注释已清理。 */
        fun resumeNonEssentialProcesses()

        /* 注释已清理。 */
        fun requestCoreNetworkRecovery(reason: String, force: Boolean = false)
    }

    private var callbacks: Callbacks? = null
    private var backgroundThresholdMs: Long = DEFAULT_BACKGROUND_THRESHOLD_MS

    @Volatile
    private var currentMode: PowerMode = PowerMode.NORMAL

    @Volatile
    private var userAwayAtMs: Long = 0L

    @Volatile
    private var isAppInBackground: Boolean = false

    @Volatile
    private var isScreenOff: Boolean = false

    @Volatile
    private var backgroundStartTimeMs: Long = 0L

    @Volatile
    private var lastReturnRecoveryAtMs: Long = 0L

    @Volatile
    private var lastReturnRecoverySource: String = ""

    private val logRepo by lazy { LogRepository.getInstance() }

    private fun logState(message: String) {
        Log.i(TAG, message)
        runCatching { logRepo.addLog("INFO [Power] $message") }
    }

    /**
     * 注释已清理。
     */
    val powerMode: PowerMode get() = currentMode

    /**
     * 注释已清理。
     */
    val isPowerSaving: Boolean get() = currentMode == PowerMode.POWER_SAVING

    /**
     * 注释已清理。
     */
    private val isUserAway: Boolean get() = isAppInBackground || isScreenOff

    /**
     * 注释已清理。
     */
    fun init(callbacks: Callbacks, thresholdMs: Long = DEFAULT_BACKGROUND_THRESHOLD_MS) {
        this.callbacks = callbacks
        this.backgroundThresholdMs = if (thresholdMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            thresholdMs.coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)
        }
        val thresholdDisplay = if (backgroundThresholdMs == Long.MAX_VALUE) "NEVER" else "${backgroundThresholdMs / 1000 / 60}min"
        Log.i(TAG, "BackgroundPowerManager initialized as state-recorder only (threshold=$thresholdDisplay)")
    }

    /**
     * 注释已清理。
     */
    fun setThreshold(thresholdMs: Long) {
        backgroundThresholdMs = if (thresholdMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            thresholdMs.coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)
        }
        val thresholdDisplay = if (backgroundThresholdMs == Long.MAX_VALUE) "NEVER" else "${backgroundThresholdMs / 1000 / 60}min"
        Log.i(TAG, "Threshold updated to $thresholdDisplay")
    }

    /**
     * 注释已清理。
     */
    fun onAppBackground() {
        if (isAppInBackground) return
        isAppInBackground = true
        backgroundStartTimeMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "[IPC] App entered background at $backgroundStartTimeMs")
        evaluateUserPresence()
    }

    /**
     * 注释已清理。
     */
    fun onAppForeground() {
        if (!isAppInBackground) {
            logState("[IPC] App foreground ignored: state mismatch (isAppInBackground=false)")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val backgroundDuration = if (backgroundStartTimeMs > 0) {
            now - backgroundStartTimeMs
        } else {
            0L
        }
        val awayDuration = if (userAwayAtMs > 0) {
            now - userAwayAtMs
        } else {
            0L
        }

        isAppInBackground = false

        maybeRequestRecoveryOnReturn(
            source = "app_foreground",
            eventLabel = "[IPC] App returned to foreground",
            eventDurationMs = backgroundDuration,
            awayDurationMs = awayDuration
        )

        backgroundStartTimeMs = 0L
        evaluateUserPresence()
    }

    // 注释已清理。

    /**
     * 注释已清理。
     */
    fun onScreenOff() {
        if (isScreenOff) return
        isScreenOff = true
        Log.i(TAG, "[Screen] Screen turned OFF")
        evaluateUserPresence()
    }

    /**
     * 注释已清理。
     */
    fun onScreenOn() {
        if (!isScreenOff) return

        val now = SystemClock.elapsedRealtime()
        val awayDuration = if (userAwayAtMs > 0) {
            now - userAwayAtMs
        } else {
            0L
        }

        isScreenOff = false

        maybeRequestRecoveryOnReturn(
            source = "screen_on",
            eventLabel = "[Screen] Screen turned ON",
            eventDurationMs = awayDuration,
            awayDurationMs = awayDuration
        )

        evaluateUserPresence()
    }

    /**
     * 注释已清理。
     */
    private fun maybeRequestRecoveryOnReturn(
        source: String,
        eventLabel: String,
        eventDurationMs: Long,
        awayDurationMs: Long
    ) {
        val cb = callbacks
        val skipReason = when {
            cb == null -> "callbacks is null"
            !cb.isVpnRunning -> "vpn not running"
            awayDurationMs < MIN_RECOVERY_AWAY_MS -> {
                "away ${awayDurationMs}ms < ${MIN_RECOVERY_AWAY_MS}ms"
            }
            shouldCoalesceReturnRecovery(source) -> {
                "coalesced_with=$lastReturnRecoverySource within ${RETURN_RECOVERY_COALESCE_MS}ms"
            }
            else -> null
        }

        if (skipReason != null) {
            logState("$eventLabel after ${eventDurationMs / 1000}s, skip recovery: $skipReason")
            return
        }

        val forceThreshold = when (source) {
            "app_foreground" -> FORCE_RECOVERY_AWAY_MS_APP_FOREGROUND
            "screen_on" -> FORCE_RECOVERY_AWAY_MS_SCREEN_ON
            else -> FORCE_RECOVERY_AWAY_MS_SCREEN_ON
        }
        val forceRecovery = awayDurationMs > forceThreshold

        markReturnRecovery(source)

        logState(
            "$eventLabel after ${eventDurationMs / 1000}s, " +
                "request recovery(source=$source, force=$forceRecovery, " +
                "away=${awayDurationMs}ms, threshold=${forceThreshold}ms)"
        )
        cb?.requestCoreNetworkRecovery(source, force = forceRecovery)
    }

    /**
     * 注释已清理。
     */
    private fun shouldCoalesceReturnRecovery(source: String): Boolean {
        val lastAt = lastReturnRecoveryAtMs
        val elapsed = SystemClock.elapsedRealtime() - lastAt
        val withinWindow = lastAt > 0L && elapsed in 0 until RETURN_RECOVERY_COALESCE_MS
        if (!withinWindow) return false

        val preferAppForeground = lastReturnRecoverySource == "screen_on" && source == "app_foreground"
        return !preferAppForeground
    }

    private fun markReturnRecovery(source: String) {
        lastReturnRecoveryAtMs = SystemClock.elapsedRealtime()
        lastReturnRecoverySource = source
    }

    private fun evaluateUserPresence() {
        if (isUserAway) {
            if (userAwayAtMs == 0L) {
                userAwayAtMs = SystemClock.elapsedRealtime()
                val thresholdDisplay = if (backgroundThresholdMs == Long.MAX_VALUE) {
                    "NEVER"
                } else {
                    "${backgroundThresholdMs / 1000 / 60}min"
                }
                Log.i(
                    TAG,
                    "User away (background=$isAppInBackground, " +
                        "screenOff=$isScreenOff), threshold=$thresholdDisplay (state-only)"
                )
            }
            return
        }

        val wasAway = userAwayAtMs > 0
        if (wasAway) {
            val awayDuration = SystemClock.elapsedRealtime() - userAwayAtMs
            Log.i(TAG, "User returned after ${awayDuration / 1000}s (state-only)")
        }
        userAwayAtMs = 0L

        if (currentMode == PowerMode.POWER_SAVING) {
            Log.i(TAG, "Resetting legacy POWER_SAVING state to NORMAL (no-op)")
            currentMode = PowerMode.NORMAL
        }
    }

    /**
     * 注释已清理。
     */
    private fun enterPowerSavingMode() {
        Log.d(TAG, "enterPowerSavingMode ignored: state-recorder-only mode")
    }

    /**
     * 注释已清理。
     */
    private fun exitPowerSavingMode() {
        Log.d(TAG, "exitPowerSavingMode ignored: state-recorder-only mode")
    }

    /**
     * 注释已清理。
     */
    fun forceEnterPowerSaving() {
        enterPowerSavingMode()
    }

    /**
     * 注释已清理。
     */
    fun forceExitPowerSaving() {
        exitPowerSavingMode()
    }

    /**
     * 注释已清理。
     */
    fun cleanup() {
        currentMode = PowerMode.NORMAL
        isAppInBackground = false
        isScreenOff = false
        userAwayAtMs = 0L
        backgroundStartTimeMs = 0L
        lastReturnRecoveryAtMs = 0L
        lastReturnRecoverySource = ""
        callbacks = null
        Log.i(TAG, "BackgroundPowerManager cleaned up")
    }

    /**
     * 注释已清理。
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "currentMode" to currentMode.name,
            "isAppInBackground" to isAppInBackground,
            "isScreenOff" to isScreenOff,
            "isUserAway" to isUserAway,
            "thresholdMin" to if (backgroundThresholdMs == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                backgroundThresholdMs / 1000 / 60
            },
            "awayDurationSec" to if (userAwayAtMs > 0) {
                (SystemClock.elapsedRealtime() - userAwayAtMs) / 1000
            } else {
                0L
            },
            "backgroundDurationSec" to if (backgroundStartTimeMs > 0) {
                (SystemClock.elapsedRealtime() - backgroundStartTimeMs) / 1000
            } else {
                0L
            }
        )
    }
}
