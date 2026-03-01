package com.kunk.singbox

import android.app.ActivityManager
import android.app.Application
import android.net.ConnectivityManager
import android.os.Process
import androidx.work.Configuration
import androidx.work.WorkManager
import com.kunk.singbox.lifecycle.AppLifecycleObserver
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.RuleSetAutoUpdateWorker
import com.kunk.singbox.service.SubscriptionAutoUpdateWorker
import com.kunk.singbox.service.VpnKeepaliveWorker
import com.kunk.singbox.utils.DefaultNetworkListener
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SingBoxApplication : Application(), Configuration.Provider {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        MMKV.initialize(this)

        if (!isWorkManagerInitialized()) {
            WorkManager.initialize(this, workManagerConfiguration)
        }

        LogRepository.init(this)

        cleanupOrphanedTempFiles()

        if (isMainProcess()) {
            AppLifecycleObserver.register()

            applicationScope.launch {

                try {
                    val settings = SettingsRepository.getInstance(this@SingBoxApplication).settings.value
                    AppLifecycleObserver.setBackgroundTimeout(settings.backgroundPowerSavingDelay.delayMs)
                } catch (e: Exception) {
                    android.util.Log.w("SingBoxApp", "Failed to read power saving setting", e)
                }

                val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    DefaultNetworkListener.start(cm, this@SingBoxApplication) { network ->
                        android.util.Log.d("SingBoxApp", "Underlying network updated: $network")
                    }
                }

                // 注释已清理。
                SubscriptionAutoUpdateWorker.rescheduleAll(this@SingBoxApplication)
                // 注释已清理。
                RuleSetAutoUpdateWorker.rescheduleAll(this@SingBoxApplication)

                VpnKeepaliveWorker.schedule(this@SingBoxApplication)
            }
        }
    }

    private fun isWorkManagerInitialized(): Boolean {
        return try {
            WorkManager.getInstance(this)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val processName = activityManager.runningAppProcesses?.find { it.pid == pid }?.processName
        return processName == packageName
    }

    /**
     * 注释已清理。
     * 注释已清理。
     */
    private fun cleanupOrphanedTempFiles() {
        try {
            val tempDir = java.io.File(cacheDir, "singbox_temp")
            if (!tempDir.exists() || !tempDir.isDirectory) return

            val cleaned = mutableListOf<String>()
            tempDir.listFiles()?.forEach { file ->

                if (file.name.startsWith("test_") || file.name.startsWith("batch_test_")) {
                    if (file.delete()) {
                        cleaned.add(file.name)
                    }
                }
            }

            if (cleaned.isNotEmpty()) {
                android.util.Log.i("SingBoxApp", "Cleaned ${cleaned.size} orphaned temp files: ${cleaned.take(5).joinToString()}")
            }
        } catch (e: Exception) {
            android.util.Log.w("SingBoxApp", "Failed to cleanup orphaned temp files", e)
        }
    }
}
