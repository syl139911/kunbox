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

/**
 * SingBoxRemote - IPC 瀹㈡埛绔?
 *
 * 2025-fix-v6: 瑙ｅ喅鍚庡彴鎭㈠鍚?UI 涓€鐩村姞杞戒腑鐨勯棶棰?
 *
 * 鏍稿績鏀硅繘:
 * 1. VpnStateStore 鍙岄噸楠岃瘉 - 鍥炶皟澶辨晥鏃朵粠 MMKV 璇诲彇鐪熷疄鐘舵€?
 * 2. 鍥炶皟蹇冭烦妫€娴?- 妫€娴嬪洖璋冮€氶亾鏄惁姝ｅ父宸ヤ綔
 * 3. 寮哄埗閲嶈繛鏈哄埗 - rebind() 鏃剁洿鎺ユ柇寮€鍐嶉噸杩烇紝涓嶅皾璇曞鐢?
 * 4. 鐘舵€佸悓姝ヨ秴鏃?- 濡傛灉鍥炶皟瓒呰繃闃堝€兼湭鏇存柊锛屼富鍔ㄤ粠 VpnStateStore 鎭㈠
 */
@Suppress("TooManyFunctions")
object SingBoxRemote {
    private const val TAG = "SingBoxRemote"
    // 闄嶄綆閲嶈繛寤惰繜浠ユ洿蹇仮澶嶅墠鍙伴€氱煡锛?00ms * 10娆?= 1s 鏈€澶氾級
    private const val RECONNECT_DELAY_MS = 100L
    private const val MAX_RECONNECT_ATTEMPTS = 10
    // 鍥炶皟瓒呮椂闃堝€硷紝瓒呰繃姝ゆ椂闂存湭鏀跺埌鍥炶皟鍒欒涓哄洖璋冮€氶亾澶辨晥
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

    // 2025-fix-v6: 涓婃鏀跺埌鍥炶皟鐨勬椂闂?(鍩轰簬 SystemClock.elapsedRealtime)
    @Volatile
    private var lastCallbackReceivedAtMs = 0L

    // App 鐢熷懡鍛ㄦ湡閫氱煡鍙兘鍙戠敓鍦?bind 瀹屾垚鍓嶏紙渚嬪 MainActivity.onStart 鍏?rebind 鍐?notify锛?
    // 杩欓噷缂撳瓨鏈€杩戜竴娆′簨浠讹紝绛?onServiceConnected 鍚庤ˉ鍙戯紝閬垮厤鈥滆烦杩囧鑷存仮澶嶄笉瑙﹀彂鈥濄€?
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
            // 2025-fix-v6: 璁板綍鍥炶皟鎺ユ敹鏃堕棿
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
     * 2025-fix-v6: 浠?VpnStateStore 鍚屾鐘舵€?(涓嶄緷璧?AIDL 鍥炶皟)
     * 褰撳洖璋冮€氶亾澶辨晥鏃讹紝鐩存帴浠?MMKV 璇诲彇璺ㄨ繘绋嬪叡浜殑鐪熷疄鐘舵€?
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
            // 淇濇姢锛氬鏋滅郴缁?VPN 浠嶅湪杩愯锛屾垨 MMKV 璁板綍 VPN 娲昏穬锛屼笉瑕佸洖閫€鍒?STOPPED
            // 杩欓伩鍏嶄簡 rebind 杩囩▼涓?disconnect鈫抩nServiceDisconnected 瀵艰嚧鐨勭姸鎬侀棯鐑?
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
     * 涓诲姩鏌ヨ骞跺悓姝ョ姸鎬?
     * 鐢ㄤ簬 Activity onResume 鏃剁‘淇?UI 涓庢湇鍔＄姸鎬佷竴鑷?
     *
     * 2025-fix-v5: 澧炲己鐗?- 濡傛灉杩炴帴 stale 鍒欏己鍒堕噸杩?
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
     * 寮哄埗閲嶆柊缁戝畾
     * 鐩存帴鏂紑鍐嶉噸杩烇紝涓嶅皾璇曞鐢?stale 杩炴帴
     */
    fun rebind(context: Context) {
        Log.i(TAG, "rebind: forcing disconnect -> connect cycle")
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        // 2025-fix-v6: 涓嶅啀灏濊瘯澶嶇敤鐜版湁杩炴帴锛岀洿鎺ユ柇寮€鍐嶉噸杩?
        // 鍘熸潵鐨勯€昏緫鏄厛妫€鏌ヨ繛鎺ユ湁鏁堟€у啀鍐冲畾鏄惁閲嶈繛锛屼絾杩欐棤娉曟娴嬪洖璋冮€氶亾澶辨晥
        disconnect(context)
        connect(context)

        // 2025-fix-v6: 鍦ㄩ噸杩炴湡闂达紝鍏堜粠 VpnStateStore 鎭㈠鐘舵€?
        // 杩欐牱 UI 涓嶄細鏄剧ず杩囨椂鐘舵€侊紝鍗充娇鍥炶皟杩樻病鍒拌揪
        syncStateFromStore()
    }

    /**
     * 2025-fix-v10: 鍘熷瓙鍖?rebind + foreground 閫氱煡
     *
     * 瑙ｅ喅绔炴€佹潯浠? rebind() 鏄紓姝ョ殑锛宯otifyAppLifecycle() 鍦?IPC 鏈繛鎺ユ椂鎵ц浼氬鑷?
     * pendingAppLifecycle 鍙兘鍦?onServiceConnected 涔嬪墠/涔嬪悗琚缃紝閫犳垚鎭㈠閫氱煡涓㈠け銆?
     *
     * 姝ゆ柟娉曠‘淇?
     * 1. 鍏堣缃?pendingAppLifecycle = true锛岀‘淇濅笉涓㈠け
     * 2. 鍐嶆柇寮€骞堕噸杩?IPC
     * 3. onServiceConnected 浼氬鐞?pendingAppLifecycle 骞惰Е鍙戞仮澶?
     */
    fun rebindAndNotifyForeground(context: Context) {
        Log.i(TAG, "rebindAndNotifyForeground: start (atomic rebind + foreground)")
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        // 1. 鍏堣缃?pending 鏍囪锛岀‘淇濅笉涓㈠け
        // 杩欐槸鍏抽敭: 鍦?disconnect 涔嬪墠璁剧疆锛岄伩鍏嶇珵鎬?
        pendingAppLifecycle = true

        // 2. 鏂紑鏃ц繛鎺?
        disconnect(context)

        // 3. 閲嶆柊杩炴帴 (onServiceConnected 浼氬鐞?pendingAppLifecycle)
        connect(context)

        // 4. 鍚屾鐘舵€佸厹搴?- UI 绔嬪嵆鏄剧ず姝ｇ‘鐘舵€?
        syncStateFromStore()
    }

    /**
     * 2025-fix-v6: 妫€娴嬪洖璋冮€氶亾鏄惁瓒呮椂
     * 濡傛灉瓒呰繃闃堝€兼湭鏀跺埌鍥炶皟锛岃繑鍥?true
     */
    fun isCallbackStale(): Boolean {
        if (lastCallbackReceivedAtMs == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - lastCallbackReceivedAtMs
        return elapsed > CALLBACK_TIMEOUT_MS
    }

    /**
     * 2025-fix-v6: 寮哄埗浠?VpnStateStore 鍚屾鐘舵€?
     * 鐢ㄤ簬 Activity onResume 鏃剁‘淇?UI 鏄剧ず姝ｇ‘鐘舵€?
     */
    fun forceStoreSync() {
        syncStateFromStore()
    }

    /**
     * 鍗虫椂鎭㈠ - 鍓嶅彴鍥炴潵鏃惰皟鐢?
     * Phase 1: 鍚屾浠?MMKV 鎭㈠鐘舵€?(< 1ms, 涓嶄緷璧?IPC)
     * Phase 2: 寮傛楠岃瘉 IPC锛屼粎鍦ㄧ‘璁ゅけ鏁堟椂鎵嶉噸杩烇紙閬垮厤涓嶅繀瑕佺殑 rebind 瀵艰嚧 STOPPED 闂儊锛?
     */
    fun instantRecovery(context: Context) {
        // Phase 1: 绔嬪嵆浠?MMKV 璇诲彇鐘舵€侊紙寰绾э級
        syncStateFromStore()
        Log.i(TAG, "instantRecovery: Phase 1 done, state=${_state.value}")

        // Phase 2: 寮傛纭繚 IPC 鍙敤锛堜笉闃诲璋冪敤鑰咃級
        contextRef = WeakReference(context.applicationContext)

        if (!connectionActive) {
            // IPC 瀹屽叏涓嶅瓨鍦紝鐢?connect锛堜笉鏄?rebind锛夐伩鍏嶅浣?disconnect
            Log.i(TAG, "instantRecovery: IPC not active, connecting (not rebinding)")
            connect(context)
            return
        }

        if (!bound || service == null) {
            // connectionActive 浣?bound/service 涓㈠け锛岃鏄庢鍦ㄩ噸杩炰腑锛屼笉瑕佹墦鏂?
            Log.i(TAG, "instantRecovery: connection in progress, skip rebind")
            return
        }

        // 杩炴帴鐪嬩技瀛樻椿锛屽紓姝ラ獙璇?+ 鍚屾锛堝湪涓荤嚎绋?post 閬垮厤骞跺彂闂锛?
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
     * 閫氱煡 :bg 杩涚▼ App 鐢熷懡鍛ㄦ湡鍙樺寲
     * 鐢ㄤ簬瑙﹀彂鐪佺數妯″紡
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
