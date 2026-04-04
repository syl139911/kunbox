package com.kunk.singbox.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback

class SingBoxIpcService : Service() {

    companion object {
        private const val TAG = "SingBoxIpcService"
    }

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "IPC binder died")
        SingBoxIpcHub.onServiceBinderDied()
    }

    private val binder = object : ISingBoxService.Stub() {
        override fun getState(): Int = SingBoxIpcHub.getStateOrdinal()

        override fun getActiveLabel(): String = SingBoxIpcHub.getActiveLabel()

        override fun getLastError(): String = SingBoxIpcHub.getLastError()

        override fun isManuallyStopped(): Boolean = SingBoxIpcHub.isManuallyStopped()

        override fun registerCallback(callback: ISingBoxServiceCallback?) {
            if (callback == null) return
            SingBoxIpcHub.registerCallback(callback)
        }

        override fun unregisterCallback(callback: ISingBoxServiceCallback?) {
            if (callback == null) return
            SingBoxIpcHub.unregisterCallback(callback)
        }

        override fun notifyAppLifecycle(isForeground: Boolean) {
            SingBoxIpcHub.onAppLifecycle(isForeground)
        }

        override fun hotReloadConfig(configContent: String?): Int {
            if (configContent.isNullOrEmpty()) {
                return SingBoxIpcHub.HotReloadResult.UNKNOWN_ERROR
            }
            return SingBoxIpcHub.hotReloadConfig(configContent)
        }

        override fun requestUrlTestNodeDelay(requestId: Long, groupTag: String?, nodeTag: String?, timeoutMs: Int) {
            if (groupTag.isNullOrBlank() || nodeTag.isNullOrBlank()) {
                SingBoxIpcHub.requestUrlTestNodeDelayResult(requestId, -1)
                return
            }
            SingBoxIpcHub.requestUrlTestNodeDelay(requestId, groupTag, nodeTag, timeoutMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        SingBoxIpcHub.registerService(this)
        try {
            binder.asBinder().linkToDeath(deathRecipient, 0)
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to link binder death recipient", e)
            SingBoxIpcHub.onServiceBinderDied()
        }
    }

    override fun onDestroy() {
        try {
            binder.asBinder().unlinkToDeath(deathRecipient, 0)
        } catch (_: NoSuchElementException) {
            Log.d(TAG, "Death recipient already unlinked")
        }
        SingBoxIpcHub.unregisterService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
