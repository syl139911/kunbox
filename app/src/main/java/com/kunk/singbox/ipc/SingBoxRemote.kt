package com.kunk.singbox.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.service.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

@Suppress("TooManyFunctions")
object SingBoxRemote {
    private const val TAG = "SingBoxRemote"

    private const val RECONNECT_DELAY_MS = 100L
    private const val MAX_RECONNECT_ATTEMPTS = 10

    private const val CALLBACK_TIMEOUT_MS = 8_000L

    private val _state = MutableStateFlow(ServiceState.STOPPED)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _activeLabel = MutableStateFlow("")
    val activeLabel: StateFlow<String> = _activeLabel.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val _manuallyStopped = MutableStateFlow(false)
    val manuallyStopped: StateFlow<Boolean> = _manuallyStopped.asStateFlow()

    @Volatile
    private var service: ISingBoxService? = null

    @Volatile
    private var connectionActive = false

    @Volatile
    private var bound = false

    @Volatile
    private var callbackRegistered = false

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var contextRef: WeakReference<Context>? = null

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var lastSyncTimeMs = 0L

    @Volatile
    private var lastCallbackReceivedAtMs = 0L

    @Volatile
    private var pendingAppLifecycle: Boolean? = null

    @Volatile
    private var pendingLifecycleVersion: Long = 0L

    @Volatile
    private var sentLifecycleVersion: Long = 0L

    @Volatile
    private var pendingLifecycleRetry: Runnable? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : ISingBoxServiceCallback.Stub() {
        override fun onStateChanged(state: Int, activeLabel: String?, lastError: String?, manuallyStopped: Boolean) {
            lastCallbackReceivedAtMs = SystemClock.elapsedRealtime()
            val st = ServiceState.values().getOrNull(state)
                ?: ServiceState.STOPPED
            val oldState = _state.value
            updateState(st, activeLabel, lastError, manuallyStopped)
            Log.i(TAG, "[UI] Callback received: $oldState -> $st, activeLabel=$activeLabel")
        }
    }

    private fun updateState(
        st: ServiceState,
        activeLabel: String? = null,
        lastError: String? = null,
        manuallyStopped: Boolean? = null
    ) {
        _state.value = st
        _isRunning.value = st == ServiceState.RUNNING
        _isStarting.value = st == ServiceState.STARTING
        activeLabel?.let { _activeLabel.value = it }
        lastError?.let { _lastError.value = it }
        manuallyStopped?.let { _manuallyStopped.value = it }
        lastSyncTimeMs = System.currentTimeMillis()
    }

    /**
     */
    private fun syncStateFromStore() {
        val isActive = VpnStateStore.getActive()
        val storedLabel = VpnStateStore.getActiveLabel()
        val storedError = VpnStateStore.getLastError()
        val storedManuallyStopped = VpnStateStore.isManuallyStopped()

        val newState = if (isActive) {
            ServiceState.RUNNING
        } else {
            ServiceState.STOPPED
        }

        Log.i(TAG, "syncStateFromStore: isActive=$isActive, label=$storedLabel")
        updateState(newState, storedLabel, storedError, storedManuallyStopped)
    }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            Log.w(TAG, "Binder died, performing immediate reconnect")
            service = null
            callbackRegistered = false

            mainHandler.post {
                val ctx = contextRef?.get()
                if (ctx != null && !SagerConnection_restartingApp) {
                    disconnect(ctx)
                    connect(ctx)
                }
            }
        }
    }

    @Volatile
    private var SagerConnection_restartingApp = false

    private fun clearPendingLifecycleRetry() {
        pendingLifecycleRetry?.let { mainHandler.removeCallbacks(it) }
        pendingLifecycleRetry = null
    }

    private fun tryNotifyLifecycle(version: Long, pending: Boolean): Boolean {
        val s = service ?: return false
        if (!connectionActive || !bound) return false

        runCatching {
            s.notifyAppLifecycle(pending)
            sentLifecycleVersion = version
            pendingAppLifecycle = null
            clearPendingLifecycleRetry()
            Log.w(TAG, "notifyAppLifecycle retried: isForeground=$pending")
        }.onFailure {
            Log.w(TAG, "notifyAppLifecycle retry failed", it)
            schedulePendingLifecycleRetry(version)
        }
        return true
    }

    private fun ensureBindIfNeeded() {
        val ctx = contextRef?.get() ?: return
        val needsBind = !connectionActive || !bound || service == null
        if (needsBind) {
            ensureBound(ctx)
        }
    }

    private fun schedulePendingLifecycleRetry(version: Long) {
        clearPendingLifecycleRetry()
        val retryTask = Runnable {
            if (pendingLifecycleVersion != version) return@Runnable
            val pending = pendingAppLifecycle ?: return@Runnable

            if (tryNotifyLifecycle(version, pending)) return@Runnable

            ensureBindIfNeeded()
            schedulePendingLifecycleRetry(version)
        }
        pendingLifecycleRetry = retryTask
        mainHandler.postDelayed(retryTask, RECONNECT_DELAY_MS)
    }

    private fun rebindAndNotifyLifecycle(context: Context, isForeground: Boolean, version: Long) {
        pendingAppLifecycle = isForeground
        pendingLifecycleVersion = version
        sentLifecycleVersion = minOf(sentLifecycleVersion, version - 1)
        if (!connectionActive) {
            rebind(context)
        }
    }

    private fun flushPendingAppLifecycle(tag: String = "pending") {
        val pending = pendingAppLifecycle ?: return
        val version = pendingLifecycleVersion
        if (version <= sentLifecycleVersion) {
            pendingAppLifecycle = null
            return
        }
        val s = service
        if (s == null || !connectionActive || !bound) {
            val ctx = contextRef?.get()
            if (ctx != null) {
                rebindAndNotifyLifecycle(ctx, pending, version)
            }
            schedulePendingLifecycleRetry(version)
            return
        }

        runCatching {
            s.notifyAppLifecycle(pending)
            sentLifecycleVersion = version
            pendingAppLifecycle = null
            clearPendingLifecycleRetry()
            Log.d(TAG, "notifyAppLifecycle ($tag): isForeground=$pending")
        }.onFailure {
            Log.w(TAG, "Failed to notify $tag app lifecycle", it)
            val ctx = contextRef?.get()
            if (ctx != null) {
                rebindAndNotifyLifecycle(ctx, pending, version)
            }
            schedulePendingLifecycleRetry(version)
        }
    }

    private fun cleanupConnection() {
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        service = null
        bound = false
        callbackRegistered = false
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Service connected")
            this@SingBoxRemote.binder = binder
            reconnectAttempts = 0

            runCatching { binder?.linkToDeath(deathRecipient, 0) }

            val s = ISingBoxService.Stub.asInterface(binder)
            service = s
            bound = true

            if (s != null && !callbackRegistered) {
                runCatching {
                    s.registerCallback(callback)
                    callbackRegistered = true
                }
            }

            syncStateFromService(s)

            flushPendingAppLifecycle()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected")
            unregisterCallback()
            service = null
            bound = false

            val ctx = contextRef?.get()

            val mmkvActive = VpnStateStore.getActive()
            val systemVpn = ctx != null && hasSystemVpn(ctx)
            if (systemVpn || mmkvActive) {
                Log.i(
                    TAG,
                    "Service disconnected but VPN likely active " +
                        "(systemVpn=$systemVpn, mmkvActive=$mmkvActive), keeping state and reconnecting"
                )
                scheduleReconnect()
            } else {
                updateState(ServiceState.STOPPED, "", "", false)
            }
        }
    }

    private fun unregisterCallback() {
        val s = service
        if (s != null && callbackRegistered) {
            runCatching { s.unregisterCallback(callback) }
        }
        callbackRegistered = false
    }

    private fun syncStateFromService(s: ISingBoxService?) {
        if (s == null) return
        runCatching {
            val st = ServiceState.values().getOrNull(s.state)
                ?: ServiceState.STOPPED
            updateState(st, s.activeLabel.orEmpty(), s.lastError.orEmpty(), s.isManuallyStopped)
            Log.i(TAG, "State synced: $st, running=${_isRunning.value}")
        }.onFailure {
            Log.e(TAG, "Failed to sync state from service", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun hasSystemVpn(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                cm?.allNetworks?.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check system VPN", e)
            false
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            return
        }

        val ctx = contextRef?.get() ?: return
        reconnectAttempts++
        val delay = RECONNECT_DELAY_MS * reconnectAttempts

        mainHandler.postDelayed({
            if (!bound && contextRef?.get() != null) {
                Log.i(TAG, "Reconnect attempt #$reconnectAttempts")
                doBindService(ctx)
            }
        }, delay)
    }

    private fun doBindService(context: Context) {
        val intent = Intent(context, SingBoxIpcService::class.java)
        runCatching {
            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.e(TAG, "Failed to bind service", it)
        }
    }

    fun connect(context: Context) {
        if (connectionActive) {
            Log.d(TAG, "connect: already active, skip")
            return
        }
        connectionActive = true
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0
        doBindService(context)
    }

    fun disconnect(context: Context) {
        unregisterCallback()
        clearPendingLifecycleRetry()
        if (connectionActive) {
            runCatching { context.applicationContext.unbindService(conn) }
        }
        connectionActive = false
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        service = null
        bound = false
    }

    fun ensureBound(context: Context) {
        contextRef = WeakReference(context.applicationContext)

        if (connectionActive && bound && service != null) {
            val isAlive = runCatching { service?.state }.isSuccess
            if (isAlive) return

            Log.w(TAG, "Service connection stale, rebinding...")
        }

        if (!connectionActive) {
            connect(context)
        } else if (!bound || service == null) {
            disconnect(context)
            connect(context)
        }
    }

    /**
     *
     */
    fun queryAndSyncState(context: Context): Boolean {
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        val s = service
        if (connectionActive && bound && s != null) {
            val synced = runCatching {
                syncStateFromService(s)
                true
            }.getOrDefault(false)

            if (synced) {
                Log.i(TAG, "queryAndSyncState: synced from service")
                return true
            } else {
                Log.w(TAG, "queryAndSyncState: sync failed")
                return false
            }
        }

        val ctx = contextRef?.get() ?: return false
        val hasVpn = hasSystemVpn(ctx)

        if (hasVpn && !connectionActive) {
            Log.i(TAG, "queryAndSyncState: system VPN active but not connected, connecting")
            connect(ctx)

            if (_state.value != ServiceState.RUNNING) {
                updateState(ServiceState.RUNNING)
            }
            return true
        }

        if (!hasVpn && _state.value == ServiceState.RUNNING) {
            Log.i(TAG, "queryAndSyncState: no system VPN but state is RUNNING, correcting")
            updateState(ServiceState.STOPPED)
        }

        if (!connectionActive) {
            connect(ctx)
        }

        return connectionActive
    }

    /**
     */
    fun rebind(context: Context) {
        Log.i(TAG, "rebind: forcing disconnect -> connect cycle")
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        disconnect(context)
        connect(context)

        syncStateFromStore()
    }

    /**
     *
     *
     */
    fun rebindAndNotifyForeground(context: Context) {
        Log.i(TAG, "rebindAndNotifyForeground: start (atomic rebind + foreground)")
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        pendingAppLifecycle = true

        disconnect(context)

        connect(context)

        syncStateFromStore()
    }

    fun isCallbackStale(): Boolean {
        if (lastCallbackReceivedAtMs == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - lastCallbackReceivedAtMs
        return elapsed > CALLBACK_TIMEOUT_MS
    }

    /**
     */
    fun forceStoreSync() {
        syncStateFromStore()
    }

    /**
     */
    fun instantRecovery(context: Context) {

        syncStateFromStore()
        Log.i(TAG, "instantRecovery: Phase 1 done, state=${_state.value}")

        contextRef = WeakReference(context.applicationContext)

        if (!connectionActive) {

            Log.i(TAG, "instantRecovery: IPC not active, connecting (not rebinding)")
            connect(context)
            return
        }

        if (!bound || service == null) {

            Log.i(TAG, "instantRecovery: connection in progress, skip rebind")
            return
        }

        mainHandler.post {
            val s = service ?: run {
                Log.w(TAG, "instantRecovery: service became null, rebinding")
                rebind(context)
                return@post
            }

            val ok = runCatching {
                syncStateFromService(s)
                true
            }.getOrDefault(false)

            if (ok) {
                Log.i(TAG, "instantRecovery: Phase 2 AIDL verify ok")
                return@post
            }

            Log.w(TAG, "instantRecovery: AIDL verify failed, rebinding")
            rebind(context)
        }
    }

    fun isBound(): Boolean = connectionActive && bound && service != null

    fun isConnectionActive(): Boolean = connectionActive

    fun unbind(context: Context) {
        disconnect(context)
    }

    fun getLastSyncAge(): Long = System.currentTimeMillis() - lastSyncTimeMs

    /**
     */
    fun notifyAppLifecycle(isForeground: Boolean) {
        val version = pendingLifecycleVersion + 1
        pendingLifecycleVersion = version
        pendingAppLifecycle = isForeground

        val s = service
        if (s != null && connectionActive && bound) {
            flushPendingAppLifecycle(tag = "immediate")
            return
        }

        val ctx = contextRef?.get()
        if (ctx != null) {
            rebindAndNotifyLifecycle(ctx, isForeground, version)
        }
        schedulePendingLifecycleRetry(version)
        Log.d(TAG, "notifyAppLifecycle: queued version=$version isForeground=$isForeground")
    }

    object HotReloadResult {
        const val SUCCESS = 0
        const val VPN_NOT_RUNNING = 1
        const val KERNEL_ERROR = 2
        const val UNKNOWN_ERROR = 3
        const val IPC_ERROR = 4
    }

    fun urlTestNodeDelay(groupTag: String, nodeTag: String, timeoutMs: Int): Int? {
        val s = service ?: return null
        if (!connectionActive || !bound) return null

        return runCatching {
            val delay = s.urlTestNodeDelay(groupTag, nodeTag, timeoutMs)
            if (delay > 0) delay else null
        }.getOrNull()
    }

    fun hotReloadConfig(configContent: String): Int {
        val s = service
        if (s == null || !connectionActive || !bound) {
            Log.w(TAG, "hotReloadConfig: service not connected")
            return HotReloadResult.IPC_ERROR
        }

        return runCatching {
            val result = s.hotReloadConfig(configContent)
            Log.i(TAG, "hotReloadConfig: result=$result")
            result
        }.getOrElse { e ->
            Log.e(TAG, "hotReloadConfig: IPC failed", e)
            HotReloadResult.IPC_ERROR
        }
    }
}
