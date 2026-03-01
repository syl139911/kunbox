package com.kunk.singbox.lifecycle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppLifecycleObserver : DefaultLifecycleObserver {
    private const val TAG = "AppLifecycleObserver"

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    @Volatile
    private var isRegistered = false

    @Volatile
    private var backgroundTimeoutMs: Long = BackgroundPowerSavingDelay.MINUTES_30.delayMs

    @Volatile
    private var backgroundAtMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var killProcessRunnable: Runnable? = null

    fun register() {
        if (isRegistered) return
        isRegistered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Log.i(TAG, "AppLifecycleObserver registered with ProcessLifecycleOwner")
    }

    /**
     */
    fun setBackgroundTimeout(timeoutMs: Long) {
        backgroundTimeoutMs = timeoutMs
        val displayMin = if (timeoutMs == Long.MAX_VALUE) "NEVER" else "${timeoutMs / 1000 / 60}min"
        Log.i(TAG, "Background timeout set to $displayMin")
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.i(TAG, "App entered FOREGROUND")
        _isAppInForeground.value = true
        backgroundAtMs = 0L

        cancelKillProcess()

        SingBoxRemote.notifyAppLifecycle(isForeground = true)
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.i(TAG, "App entered BACKGROUND")
        _isAppInForeground.value = false
        backgroundAtMs = SystemClock.elapsedRealtime()

        SingBoxRemote.notifyAppLifecycle(isForeground = false)

        scheduleKillProcess()
    }

    /**
     */
    private fun scheduleKillProcess() {
        if (backgroundTimeoutMs == Long.MAX_VALUE) {
            Log.d(TAG, "Power saving disabled, skip scheduling kill process")
            return
        }

        if (!VpnStateStore.getActive()) {
            Log.d(TAG, "VPN not running (VpnStateStore), skip scheduling kill process")
            return
        }

        cancelKillProcess()

        killProcessRunnable = Runnable {

            if (!_isAppInForeground.value && VpnStateStore.getActive()) {
                Log.i(TAG, ">>> Background timeout reached, killing main process to save power")
                Log.i(TAG, ">>> VPN will continue running in :bg process")

                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        mainHandler.postDelayed(killProcessRunnable!!, backgroundTimeoutMs)
        Log.i(TAG, "Scheduled kill process in ${backgroundTimeoutMs / 1000 / 60}min")
    }

    /**
     */
    private fun cancelKillProcess() {
        killProcessRunnable?.let {
            mainHandler.removeCallbacks(it)
            killProcessRunnable = null
            Log.d(TAG, "Cancelled pending kill process")
        }
    }
}
