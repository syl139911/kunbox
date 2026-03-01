package com.kunk.singbox.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 注释已清理。
 *
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 *
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 */
class VpnKeepaliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "VpnKeepaliveWorker"
        private const val WORK_NAME = "vpn_keepalive"

        private const val CHECK_INTERVAL_MINUTES = 15L

        /**
         * 注释已清理。
         *
         * 注释已清理。
         * 注释已清理。
         * 注释已清理。
         * 注释已清理。
         * 注释已清理。
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true) // ·汇垻鏁婚崳娲礂閸涙澘鍠曢柡·╁劶缁诲秶鎮?
                .build()

            val workRequest = PeriodicWorkRequestBuilder<VpnKeepaliveWorker>(
                repeatInterval = CHECK_INTERVAL_MINUTES,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // 注释已清理。
                workRequest
            )

            Log.i(TAG, "VPN keepalive worker scheduled (interval: ${CHECK_INTERVAL_MINUTES}min)")
        }

        /**
         * 注释已清理。
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "VPN keepalive worker cancelled")
        }

        /**
         * 注释已清理。
         */
        private fun isBackgroundProcessAlive(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = activityManager.runningAppProcesses ?: return false

            val bgProcessName = "${context.packageName}:bg"
            return processes.any { it.processName == bgProcessName }
        }
    }

    override suspend fun doWork(): Result {
        return try {

            val isManuallyStopped = VpnStateStore.isManuallyStopped()
            if (isManuallyStopped) {
                return Result.success()
            }
            val currentMode = VpnStateStore.getMode()
            if (currentMode == VpnStateStore.CoreMode.NONE) {
                return Result.success()
            }

            val bgProcessAlive = isBackgroundProcessAlive(applicationContext)

            if (!bgProcessAlive) {
                Log.w(TAG, "Detected background process died unexpectedly, attempting recovery...")
                attemptVpnRecovery(currentMode)
            } else {
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "VPN keepalive check failed", e)

            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     */
    private suspend fun attemptVpnRecovery(mode: VpnStateStore.CoreMode) {
        try {
            Log.i(TAG, "Attempting to recover VPN service (mode: $mode)...")

            // 注释已清理。
            val settingsRepo = SettingsRepository.getInstance(applicationContext)
            val settings = settingsRepo.settings.first()

            // 注释已清理。
            val intent = when (mode) {
                VpnStateStore.CoreMode.VPN -> {
                    Intent(applicationContext, SingBoxService::class.java).apply {
                        action = SingBoxService.ACTION_START
                        putExtra(SingBoxService.EXTRA_CONFIG_PATH,
                            applicationContext.filesDir.resolve("config.json").absolutePath)
                    }
                }
                VpnStateStore.CoreMode.PROXY -> {
                    Intent(applicationContext, ProxyOnlyService::class.java).apply {
                        action = ProxyOnlyService.ACTION_START
                        putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH,
                            applicationContext.filesDir.resolve("config.json").absolutePath)
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown mode: $mode, skip recovery")
                    return
                }
            }

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
                Log.i(TAG, "VPN service recovery triggered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN service during recovery", e)

                VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
                VpnTileService.persistVpnState(applicationContext, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN recovery failed", e)
        }
    }
}
