package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.ConnectionStats
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.viewmodel.shared.NodeDisplaySettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    private val configRepository = ConfigRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val singBoxCore = SingBoxCore.getInstance(application)

    // 浣跨敤鍏变韩鐨勮缃姸鎬侊紝鍜?NodesViewModel 鍏变韩鍚屼竴浠芥暟鎹?
    private val displaySettings = NodeDisplaySettings.getInstance(application)

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Stats
    private val _statsBase = MutableStateFlow(ConnectionStats(0, 0, 0, 0, 0))
    private val _connectedAtElapsedMs = MutableStateFlow<Long?>(null)

    private val durationMsFlow: Flow<Long> = connectionState.flatMapLatest { state ->
        if (state == ConnectionState.Connected) {
            flow {
                while (true) {
                    val start = _connectedAtElapsedMs.value
                    emit(if (start != null) SystemClock.elapsedRealtime() - start else 0L)
                    delay(1000)
                }
            }
        } else {
            flowOf(0L)
        }
    }

    fun setActiveProfile(profileId: String) {
        configRepository.setActiveProfile(profileId)
        val name = profiles.value.find { it.id == profileId }?.name
        if (!name.isNullOrBlank()) {
            viewModelScope.launch {
                val msg = getApplication<Application>().getString(R.string.node_switch_success, name)
                _actionStatus.value = msg
                delay(1500)
                if (_actionStatus.value == msg) {
                    _actionStatus.value = null
                }
            }
        }

        // 2025-fix: 濡傛灉VPN姝ｅ湪杩愯锛屽垏鎹㈤厤缃悗闇€瑕佽Е鍙戠儹鍒囨崲/閲嶅惎浠ュ姞杞芥柊閰嶇疆
        // 鍚﹀垯VPN浠嶇劧浣跨敤鏃ч厤缃紝瀵艰嚧鐢ㄦ埛鐪嬪埌"閫変腑"浜嗘柊閰嶇疆鐨勮妭鐐逛絾瀹為檯娌＄綉
        if (SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value) {
            viewModelScope.launch {
                delay(100)
                // 鑾峰彇鏂伴厤缃殑褰撳墠閫変腑鑺傜偣
                val currentNodeId = configRepository.activeNodeId.value
                if (currentNodeId != null) {
                    Log.i(TAG, "Profile switched while VPN running, triggering node switch for: $currentNodeId")
                    configRepository.setActiveNodeWithResult(currentNodeId)
                }
            }
        }
    }

    fun setActiveNode(nodeId: String) {
        // 2025-fix: 鍏堝悓姝ユ洿鏂?activeNodeId锛岄伩鍏嶇珵鎬佹潯浠?
        configRepository.setActiveNodeIdOnly(nodeId)

        viewModelScope.launch {
            val node = nodes.value.find { it.id == nodeId }
            val result = configRepository.setActiveNodeWithResult(nodeId)

            if (SingBoxRemote.isRunning.value && node != null) {
                val msg = when (result) {
                    is ConfigRepository.NodeSwitchResult.Success,
                    is ConfigRepository.NodeSwitchResult.NotRunning -> getApplication<Application>().getString(R.string.node_switch_success, node.name)

                    is ConfigRepository.NodeSwitchResult.Failed ->
                        getApplication<Application>().getString(R.string.node_switch_failed, node.name)
                }
                _actionStatus.value = msg
                delay(1500)
                if (_actionStatus.value == msg) {
                    _actionStatus.value = null
                }
            }
        }
    }

    val stats: StateFlow<ConnectionStats> = combine(_statsBase, durationMsFlow) { base, duration ->
        base.copy(duration = duration)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConnectionStats(0, 0, 0, 0, 0)
    )

    // Ping 娴嬭瘯鐘舵€侊細true = 姝ｅ湪娴嬭瘯涓?
    private val _isPingTesting = MutableStateFlow(false)
    val isPingTesting: StateFlow<Boolean> = _isPingTesting.asStateFlow()

    private var pingTestJob: Job? = null
    private var lastErrorToastJob: Job? = null
    private var startMonitorJob: Job? = null

    // 鐢ㄤ簬骞虫粦娴侀噺鏄剧ず鐨勭紦瀛?
    private var lastUploadSpeed: Long = 0
    private var lastDownloadSpeed: Long = 0

    // Active profile and node from ConfigRepository
    val activeProfileId: StateFlow<String?> = configRepository.activeProfileId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val activeNodeId: StateFlow<String?> = configRepository.activeNodeId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val activeNodeLatency = kotlinx.coroutines.flow.combine(configRepository.nodes, activeNodeId) { nodes, id ->
        nodes.find { it.id == id }?.latencyMs
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val nodes: StateFlow<List<NodeUi>> = combine(
        configRepository.nodes,
        displaySettings.nodeFilter,
        displaySettings.sortType,
        displaySettings.customOrder,
        configRepository.activeNodeId
    ) { nodes: List<NodeUi>, filter: NodeFilter, sortType: NodeSortType, customOrder: List<String>, _ ->
        val filtered = when (filter.filterMode) {
            FilterMode.NONE -> nodes
            FilterMode.INCLUDE -> {
                val keywords = filter.effectiveIncludeKeywords
                if (keywords.isEmpty()) nodes
                else nodes.filter { node -> keywords.any { keyword -> node.displayName.contains(keyword, ignoreCase = true) } }
            }
            FilterMode.EXCLUDE -> {
                val keywords = filter.effectiveExcludeKeywords
                if (keywords.isEmpty()) nodes
                else nodes.filter { node -> keywords.none { keyword -> node.displayName.contains(keyword, ignoreCase = true) } }
            }
        }

        // 搴旂敤鎺掑簭
        val sorted = when (sortType) {
            NodeSortType.DEFAULT -> filtered
            NodeSortType.LATENCY -> filtered.sortedWith(compareBy<NodeUi> {
                val l = it.latencyMs
                // 灏嗘湭娴嬭瘯(null)鍜岃秴鏃?澶辫触(<=0)鐨勮妭鐐规帓鍒版渶鍚?
                if (l == null || l <= 0) Long.MAX_VALUE else l
            })
            NodeSortType.NAME -> filtered.sortedBy { it.name }
            NodeSortType.REGION -> filtered.sortedWith(compareBy<NodeUi> {
                getRegionWeight(it.regionFlag)
            }.thenBy { it.name })
            NodeSortType.CUSTOM -> {
                val orderMap = customOrder.withIndex().associate { it.value to it.index }
                filtered.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
            }
        }

        sorted
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private var trafficSmoothingJob: Job? = null
    private var trafficBaseTxBytes: Long = 0
    private var trafficBaseRxBytes: Long = 0
    private var lastTrafficTxBytes: Long = 0
    private var lastTrafficRxBytes: Long = 0
    private var lastTrafficSampleAtElapsedMs: Long = 0

    // Status
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    private val _testStatus = MutableStateFlow<String?>(null)
    val testStatus: StateFlow<String?> = _testStatus.asStateFlow()

    private val _actionStatus = MutableStateFlow<String?>(null)
    val actionStatus: StateFlow<String?> = _actionStatus.asStateFlow()

    // VPN 鏉冮檺璇锋眰缁撴灉
    private val _vpnPermissionNeeded = MutableStateFlow(false)
    val vpnPermissionNeeded: StateFlow<Boolean> = _vpnPermissionNeeded.asStateFlow()

    // 2025-fix-v12: 鐢ㄤ簬纭繚鐘舵€佺洃鍚櫒鍙惎鍔ㄤ竴娆?
    @Volatile private var stateCollectorStarted = false

    // 2025-fix: 鏍囪鏄惁鍦ㄥ惎鍔ㄦ椂妫€娴嬪埌浜嗙郴缁?VPN
    // 鐢ㄤ簬杩囨护 IPC 杩炴帴鍒濇湡鐨勮櫄鍋?STOPPED 鐘舵€?
    private var systemVpnDetectedOnBoot = false

    // 2025-fix: 浣跨敤鏇村仴澹殑 IPC 缁戝畾閫昏緫
    // 鍘熷洜: 鍘熸潵鐨勭瓑寰呭彧鏈?1000ms锛屽湪绯荤粺璐熻浇楂樻椂鍙兘涓嶅
    // 鏀硅繘: 澧炲姞閲嶈瘯娆℃暟 + 姣忔閲嶈瘯鍓嶅厛灏濊瘯 ensureBound
    init {
        viewModelScope.launch {
            // 绗竴闃舵锛氱‘淇?IPC 缁戝畾锛堝甫閲嶈瘯锛?
            for (attempt in 1..5) {
                runCatching { SingBoxRemote.ensureBound(getApplication()) }
                delay(300) // 姣忔绛夊緟 300ms锛屾€诲叡鏈€澶?1500ms
                if (SingBoxRemote.isBound()) {
                    Log.i(TAG, "IPC bound successfully on attempt $attempt")
                    break
                }
                Log.w(TAG, "IPC not bound, attempt $attempt/5")
            }

            // 绗簩闃舵锛氬悓姝ュ垵濮嬬姸鎬侊紙浠?MMKV 鍏滃簳锛?
            runCatching {
                val context = getApplication<Application>()
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val hasSystemVpn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm?.allNetworks?.any { network ->
                        val caps = cm.getNetworkCapabilities(network) ?: return@any false
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    } == true
                } else {
                    false
                }

                if (hasSystemVpn) {
                    systemVpnDetectedOnBoot = true
                }

                val persisted = context.getSharedPreferences("vpn_state", Context.MODE_PRIVATE)
                    .getBoolean("vpn_active", false)

                if (!hasSystemVpn && persisted) {
                    VpnTileService.persistVpnState(context, false)
                }

                if (hasSystemVpn && persisted) {
                    _connectionState.value = ConnectionState.Connected
                    _connectedAtElapsedMs.value = SystemClock.elapsedRealtime()
                } else if (!SingBoxRemote.isStarting.value) {
                    _connectionState.value = ConnectionState.Idle
                }
            }

            // 绗笁闃舵锛氱‘淇濈姸鎬佹敹闆嗗櫒鍚姩锛堝叧閿慨澶嶏級
            // 鍘熸潵鍙湪缁戝畾鎴愬姛鍚庢墠鍚姩锛岀幇鍦ㄦ棤璁虹粦瀹氭槸鍚︽垚鍔熼兘鍚姩
            // 杩欐牱鍗充娇 IPC 缁戝畾澶辫触锛孧MKV 鐘舵€佷篃鑳芥寔缁洿鏂?UI
            startStateCollector()
        }

        // Surface service-level startup errors on UI
        viewModelScope.launch {
            SingBoxRemote.lastError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _testStatus.value = err
                    lastErrorToastJob?.cancel()
                    lastErrorToastJob = viewModelScope.launch {
                        delay(3000)
                        if (_testStatus.value == err) {
                            _testStatus.value = null
                        }
                    }
                }
            }
        }

        // 鑺傜偣鍙樺寲鏃舵竻鐞嗛椤电紦瀛樺欢杩燂紝閬垮厤鏃у€奸暱鏈熻鐩栬妭鐐瑰垪琛ㄤ腑鐨勬渶鏂板欢杩?
        viewModelScope.launch {
            activeNodeId
                .drop(1)
                .distinctUntilChanged()
                .collect {
                    stopPingTest()
                }
        }
    }

    /**
     * 2025-fix-v12: 鍚姩鐘舵€佺洃鍚櫒
     * 纭繚鍙湪 IPC 缁戝畾瀹屾垚鍚庤皟鐢ㄤ竴娆?
     * 娉ㄦ剰: 鐜板湪鍏佽閲嶅璋冪敤锛堝箓绛夛級锛屽唴閮ㄤ細妫€鏌ユ槸鍚﹀凡鍚姩
     */
    // 2025-fix: 鐢ㄤ簬澶勭悊杩炴帴鐘舵€佸彉鏇寸殑闃叉姈 Job
    private var pendingIdleJob: Job? = null
    private var startGraceUntilElapsedMs: Long? = null

    /**
     * 鍚姩鐘舵€佹敹闆嗗櫒锛堝箓绛夋柟娉曪級
     * 2025-fix-v12: 纭繚鍙惎鍔ㄤ竴娆★紝浣嗕繚璇佸湪 init 鍜?refreshState 涓兘浼氳璋冪敤
     * 鍏抽敭淇: 浣跨敤 synchronized 纭繚绾跨▼瀹夊叏锛屽悓鏃跺厑璁稿湪蹇呰鏃堕噸鏂板惎鍔?
     */
    private fun startStateCollector() {
        // 浣跨敤 synchronized 纭繚鍙惎鍔ㄤ竴娆?
        if (stateCollectorStarted) {
            Log.d(TAG, "startStateCollector: already started, skipping")
            return
        }

        synchronized(this) {
            if (stateCollectorStarted) return
            stateCollectorStarted = true
        }

        // 鏀堕泦鍣?: 鐩戝惉 SingBoxService 鐘舵€佸彉鍖?
        val stateFlow = SingBoxRemote.state
        viewModelScope.launch {
            stateFlow.collect { state ->
                when (state) {
                    ServiceState.RUNNING -> {
                        systemVpnDetectedOnBoot = false
                        setConnectionState(ConnectionState.Connected)
                    }
                    ServiceState.STARTING -> {
                        systemVpnDetectedOnBoot = false
                        setConnectionState(ConnectionState.Connecting)
                    }
                    ServiceState.STOPPING -> {
                        systemVpnDetectedOnBoot = false
                        setConnectionState(ConnectionState.Disconnecting)
                    }
                    ServiceState.STOPPED -> {
                        setConnectionState(ConnectionState.Idle)
                    }
                }
            }
        }

        // 瑙ｅ喅閫氱煡鏍忓垏鎹㈣妭鐐瑰悗棣栭〉鏄剧ず鏃ц妭鐐圭殑闂
        viewModelScope.launch {
            SingBoxRemote.activeLabel
                .filter { it.isNotBlank() }
                .distinctUntilChanged()
                .collect { nodeName ->
                    Log.d(TAG, "activeLabel changed from service: $nodeName")
                    configRepository.syncActiveNodeFromProxySelection(nodeName)
                }
        }

        Log.i(TAG, "startStateCollector: collectors launched")
    }

    /**
     */
    private fun setConnectionState(newState: ConnectionState) {
        if (newState == ConnectionState.Disconnecting && _connectionState.value == ConnectionState.Connecting) {
            val graceUntil = startGraceUntilElapsedMs
            if (graceUntil != null && SystemClock.elapsedRealtime() < graceUntil) {
                return
            }
        }
        when (newState) {
            ConnectionState.Connected -> {
                // 濡傛灉鏈夋寕璧风殑"鍙樻洿涓篒dle"鐨勪换鍔★紝绔嬪嵆鍙栨秷锛岃鏄庢槸铏氭儕涓€鍦?
                pendingIdleJob?.cancel()
                pendingIdleJob = null
                startGraceUntilElapsedMs = null

                if (_connectionState.value != ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Connected
                    _connectedAtElapsedMs.value = SystemClock.elapsedRealtime()
                    startTrafficMonitor()
                }
            }
            ConnectionState.Idle -> {
                // 濡傛灉褰撳墠鏄凡杩炴帴锛屼笉瑕佺珛鍗虫柇寮€锛岃€屾槸寤惰繜鎵ц
                if (_connectionState.value == ConnectionState.Connected) {
                    // 濡傛灉宸茬粡鍦ㄧ瓑寰呮柇寮€锛屼笉瑕侀噸澶嶅垱寤?
                    if (pendingIdleJob?.isActive == true) return

                    pendingIdleJob = viewModelScope.launch {
                        // 2025-fix-v7: 濡傛灉 MMKV 璁板綍 VPN 姝ｅ湪杩愯锛岀粰鏇撮暱瀹介檺鏈熺瓑 IPC 鎭㈠
                        // 閬垮厤 IPC 杩樺湪缁戝畾涓椂璇Е鍙戞柇杩烇紙浠?300ms 寤堕暱鍒?3000ms锛?
                        val delayTime = when {
                            VpnStateStore.getActive() -> 3000L
                            systemVpnDetectedOnBoot -> 1000L
                            else -> 300L
                        }
                        delay(delayTime)

                        // 瀹介檺鏈熻繃锛屽啀娆℃鏌?SingBoxRemote 鐘舵€?
                        // 鍙湁褰撴湇鍔＄渚濈劧鍧氭寔鏄?STOPPED 鏃讹紝鎵嶇湡姝ｆ柇寮€ UI
                        if (SingBoxRemote.state.value == ServiceState.STOPPED) {
                            performDisconnect()
                        }
                        // 瀹介檺鏈熺粨鏉燂紝鏍囪澶辨晥
                        systemVpnDetectedOnBoot = false
                        pendingIdleJob = null
                    }
                } else if (_connectionState.value == ConnectionState.Connecting) {
                    val graceUntil = startGraceUntilElapsedMs
                    if (graceUntil != null) {
                        val now = SystemClock.elapsedRealtime()
                        val remaining = graceUntil - now
                        if (remaining > 0) {
                            if (pendingIdleJob?.isActive == true) return
                            pendingIdleJob = viewModelScope.launch {
                                delay(remaining)
                                if (SingBoxRemote.state.value == ServiceState.STOPPED) {
                                    performDisconnect()
                                }
                                pendingIdleJob = null
                            }
                            return
                        }
                    }
                    performDisconnect()
                } else {
                    // 褰撳墠涓嶆槸杩炴帴鐘舵€侊紝鐩存帴鏇存柊
                    performDisconnect()
                }
            }
            else -> {
                // 鍏朵粬鐘舵€侊紙Connecting/Disconnecting/Error锛夌洿鎺ユ洿鏂?
                pendingIdleJob?.cancel()
                if (newState == ConnectionState.Connecting) {
                    startGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 800L
                } else {
                    startGraceUntilElapsedMs = null
                }
                if (_connectionState.value != newState) {
                    _connectionState.value = newState
                }
            }
        }
    }

    private fun performDisconnect() {
        if (_connectionState.value != ConnectionState.Idle) {
            _connectionState.value = ConnectionState.Idle
            _connectedAtElapsedMs.value = null
            stopTrafficMonitor()
            stopPingTest()
            _statsBase.value = ConnectionStats(0, 0, 0, 0, 0)
        }
    }

    /**
     * 2025-fix-v12: 鍒锋柊 VPN 鐘舵€?(涓夐樁娈垫仮澶?
     *
     * Phase 1: 鍗虫椂鎭㈠ (< 1ms)
     * - 浠?MMKV 璇诲彇 VPN 鐘舵€侊紝绔嬪嵆鏇存柊 UI
     * - 寮傛楠岃瘉/閲嶅缓 IPC锛堜笉闃诲锛屼笉寮哄埗 rebind锛?
     *
     * Phase 2: 寮傛绮剧‘鍚屾 (鍚庡彴瀹屾垚锛岀敤鎴锋棤鎰?
     * - 绛夊緟 IPC 缁戝畾瀹屾垚
     * - 浠呭綋 AIDL 杩斿洖鐨勭姸鎬佷笌 MMKV 涓€鑷存垨鏇村彲淇℃椂鎵嶈鐩?UI
     * - 濡傛灉 IPC 瓒呮椂鏈粦瀹氫絾 MMKV 鏄剧ず active锛屼繚鎸?Connected 涓嶅洖閫€
     *
     * Phase 3: 寮哄埗纭繚鐘舵€佹敹闆嗗櫒鍚姩 (鍏抽敭淇)
     * - 鏃犺 IPC 鏄惁缁戝畾鎴愬姛锛岀‘淇?startStateCollector() 琚皟鐢?
     * - 闃叉 init 鍧楄秴鏃跺鑷寸姸鎬佺洃鍚櫒姘镐笉鍚姩
     */
    fun refreshState() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            // Phase 1: 鍗虫椂鎭㈠ (< 1ms锛屼粠 MMKV 璇荤姸鎬?+ 寮傛楠岃瘉 IPC)
            SingBoxRemote.instantRecovery(context)

            // 缁熶竴鍓嶅彴鎭㈠鍏ュ彛锛氱敱 AppLifecycleObserver -> IPC -> :bg 缃戝叧澶勭悊
            // 杩欓噷涓嶅啀涓诲姩 rebind锛岄伩鍏嶄笌鐢熷懡鍛ㄦ湡閫氱煡绔炰簤瀵艰嚧閲嶅鎭㈠/鐘舵€佹姈鍔?
            if (VpnStateStore.getActive()) {
                SingBoxRemote.notifyAppLifecycle(isForeground = true)
            }

            // 绔嬪嵆浠?MMKV 鐘舵€佹洿鏂?UI锛堜笉绛?IPC锛?
            val isActive = VpnStateStore.getActive()
            val phase1State = when {
                isActive -> ConnectionState.Connected
                SingBoxRemote.isStarting.value -> ConnectionState.Connecting
                else -> ConnectionState.Idle
            }
            setConnectionState(phase1State)

            // Phase 2: IPC 灏辩华鍚庣簿纭悓姝ワ紙鍚庡彴闈欓粯瀹屾垚锛岀敤鎴锋棤鎰燂級
            // 2025-fix-v12: 澧炲姞绛夊緟娆℃暟锛屼粠 50 娆″鍔犲埌 80 娆★紙鎬诲叡 8 绉掞級
            // 鍘熷洜: 鍦ㄤ綆鎬ц兘璁惧鎴栫郴缁熻礋杞介珮鏃讹紝IPC 缁戝畾鍙兘闇€瑕佹洿闀挎椂闂?
            launch {
                var retries = 0
                val maxRetries = 80 // 80 * 100ms = 8 绉?
                while (!SingBoxRemote.isBound() && retries < maxRetries) {
                    delay(100)
                    retries++
                }

                if (SingBoxRemote.isBound()) {
                    val state = SingBoxRemote.state.value
                    Log.i(TAG, "refreshState Phase 2: state=$state, bound=true, retries=$retries")
                    when (state) {
                        ServiceState.RUNNING -> setConnectionState(ConnectionState.Connected)
                        ServiceState.STARTING -> setConnectionState(ConnectionState.Connecting)
                        ServiceState.STOPPING -> setConnectionState(ConnectionState.Disconnecting)
                        ServiceState.STOPPED -> {
                            // 鍏抽敭淇濇姢锛氬鏋?MMKV 浠嶇劧鏄剧ず active锛岃鏄?AIDL 鍙兘杩樻病鍚屾瀹屾垚
                            // 锛堝垰 rebind 鍚?onServiceConnected 鐨勫垵濮嬪悓姝ュ彲鑳借繕娌″埌杈撅級
                            // 姝ゆ椂涓嶈鍥為€€鍒?Idle锛岀瓑鍚庣画鍥炶皟鑷劧鏇存柊
                            if (VpnStateStore.getActive()) {
                                Log.w(
                                    TAG,
                                    "refreshState Phase 2: AIDL says STOPPED but MMKV says active, " +
                                        "keeping Connected (wait for callback)"
                                )
                            } else {
                                setConnectionState(ConnectionState.Idle)
                            }
                        }
                    }
                } else {
                    // IPC 瓒呮椂鏈粦瀹氾紝浣嗗鏋?MMKV 鏄剧ず active锛屼繚鎸?Connected
                    if (isActive) {
                        Log.w(TAG, "refreshState Phase 2: IPC not bound but MMKV active, keeping Connected")
                    } else {
                        Log.w(TAG, "refreshState Phase 2: IPC not bound and MMKV inactive")
                        // 2025-fix-v12: 瓒呮椂鍚庢槑纭缃负 Idle锛岄伩鍏?UI 鍗′綇
                        setConnectionState(ConnectionState.Idle)
                    }
                }
            }

            // Phase 3: 寮哄埗纭繚鐘舵€佹敹闆嗗櫒鍚姩 (鍏抽敭淇)
            // 鏃犺 IPC 缁戝畾鏄惁鎴愬姛锛岄兘瑕佺‘淇?startStateCollector 琚皟鐢?
            // 杩欐牱鍗充娇鎵€鏈夌瓑寰呴兘瓒呮椂锛孧MKV 鐘舵€佹洿鏂颁篃鑳芥纭紶閫掑埌 UI
            startStateCollector()
        }
    }

    fun toggleConnection() {
        viewModelScope.launch {
            when (_connectionState.value) {
                ConnectionState.Idle, ConnectionState.Error -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 800L
                    _connectionState.value = ConnectionState.Connecting
                    startCore()
                }
                ConnectionState.Connecting -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = null
                    _connectionState.value = ConnectionState.Disconnecting
                    stopVpn()
                }
                ConnectionState.Connected, ConnectionState.Disconnecting -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = null
                    _connectionState.value = ConnectionState.Disconnecting
                    stopVpn()
                }
            }
        }
    }

    fun restartVpn() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            val settings = SettingsRepository.getInstance(context).settings.first()
            if (settings.tunEnabled) {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    _vpnPermissionNeeded.value = true
                    return@launch
                }
            }

            val configResult = withContext(Dispatchers.IO) {
                val settingsRepository = SettingsRepository.getInstance(context)
                settingsRepository.checkAndMigrateRuleSets()
                configRepository.generateConfigFile()
            }

            if (configResult == null) {
                _testStatus.value = getApplication<Application>().getString(R.string.dashboard_config_generation_failed)
                delay(2000)
                _testStatus.value = null
                return@launch
            }

            val useTun = settings.tunEnabled
            val perAppSettingsChanged = VpnStateStore.hasPerAppVpnSettingsChanged(
                appMode = settings.vpnAppMode.name,
                allowlist = settings.vpnAllowlist,
                blocklist = settings.vpnBlocklist
            )

            logRestartDebugInfo(settings)

            val tunSettingsChanged = VpnStateStore.hasTunSettingsChanged(
                tunStack = settings.tunStack.name,
                tunMtu = settings.tunMtu,
                autoRoute = settings.autoRoute,
                strictRoute = settings.strictRoute,
                proxyPort = settings.proxyPort
            )

            val requiresFullRestart = perAppSettingsChanged || tunSettingsChanged

            if (useTun && SingBoxRemote.isRunning.value && !requiresFullRestart) {
                Log.i(TAG, "Settings are hot-reloadable, attempting kernel hot reload")
                if (tryHotReload(configResult.path)) {
                    Log.i(TAG, "Hot reload succeeded, settings applied without VPN reconnection")
                    return@launch
                }
                Log.w(TAG, "Hot reload failed, falling back to full restart")
            } else {
                if (requiresFullRestart) {
                    Log.i(
                        TAG,
                        "Full restart required: perAppChanged=$perAppSettingsChanged, tunChanged=$tunSettingsChanged"
                    )
                }
            }

            performRestart(context, configResult.path, useTun, perAppSettingsChanged)
        }
    }

    private fun logRestartDebugInfo(settings: AppSettings) {
        Log.d(
            TAG,
            "restartVpn: useTun=${settings.tunEnabled}, isRunning=${SingBoxRemote.isRunning.value}"
        )
        Log.d(
            TAG,
            "restartVpn: currentMode=${settings.vpnAppMode.name}, " +
                "allowlist=${settings.vpnAllowlist.take(100)}, blocklist=${settings.vpnBlocklist.take(100)}"
        )
    }

    private suspend fun tryHotReload(configPath: String): Boolean {
        val configContent = withContext(Dispatchers.IO) {
            runCatching { java.io.File(configPath).readText() }.getOrNull()
        }

        if (!configContent.isNullOrEmpty()) {
            Log.i(TAG, "Attempting kernel hot reload via IPC...")

            val result = withContext(Dispatchers.IO) {
                SingBoxRemote.hotReloadConfig(configContent)
            }

            when (result) {
                SingBoxRemote.HotReloadResult.SUCCESS -> {
                    Log.i(TAG, "Hot reload succeeded via IPC")
                    return true
                }
                SingBoxRemote.HotReloadResult.IPC_ERROR -> {
                    Log.w(TAG, "Hot reload IPC failed, falling back to traditional restart")
                }
                else -> {
                    Log.w(TAG, "Hot reload failed (code=$result), falling back to traditional restart")
                }
            }
        }
        return false
    }

    private suspend fun performRestart(
        context: Context,
        configPath: String,
        useTun: Boolean,
        perAppSettingsChanged: Boolean
    ) {
        if (perAppSettingsChanged && useTun && SingBoxRemote.isRunning.value) {
            Log.i(TAG, "Per-app settings changed, using full restart to rebuild TUN")
            val intent = Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_FULL_RESTART
                putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
            }
            startServiceCompat(context, intent)
            return
        }

        runCatching {
            if (!com.kunk.singbox.ipc.VpnStateStore.shouldTriggerPrepareRestart(1500L)) {
                Log.d(TAG, "PREPARE_RESTART suppressed (sender throttle)")
            } else {
                context.startService(Intent(context, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_PREPARE_RESTART
                    putExtra(
                        SingBoxService.EXTRA_PREPARE_RESTART_REASON,
                        "DashboardViewModel:restartVpn"
                    )
                })
            }
        }

        delay(150)

        val intent = if (useTun) {
            Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_START
                putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            }
        } else {
            Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_START
                putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, configPath)
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            }
        }

        startServiceCompat(context, intent)
    }

    private fun startServiceCompat(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun startCore() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            val settings = runCatching {
                SettingsRepository.getInstance(context).settings.first()
            }.getOrNull()

            val desiredMode = if (settings?.tunEnabled == true) {
                VpnStateStore.CoreMode.VPN
            } else {
                VpnStateStore.CoreMode.PROXY
            }

            if (settings?.tunEnabled == true) {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    _vpnPermissionNeeded.value = true
                    return@launch
                }
            }

            _connectionState.value = ConnectionState.Connecting

            // Ensure only one core instance is running at a time to avoid local port conflicts.
            // Do not rely on VpnStateStore here (multi-process timing); just stop the opposite service.
            val needToStopOpposite = when (desiredMode) {
                VpnStateStore.CoreMode.VPN -> {
                    runCatching {
                        context.startService(Intent(context, ProxyOnlyService::class.java).apply {
                            action = ProxyOnlyService.ACTION_STOP
                        })
                    }
                    true
                }
                VpnStateStore.CoreMode.PROXY -> {
                    runCatching {
                        context.startService(Intent(context, SingBoxService::class.java).apply {
                            action = SingBoxService.ACTION_STOP
                        })
                    }
                    true
                }
                else -> false
            }

            // 濡傛灉闇€瑕佸仠姝㈠绔嬫湇鍔★紝绛夊緟鍏跺畬鍏ㄥ仠姝?
            if (needToStopOpposite) {
                // 鍏堟鏌ュ绔嬫湇鍔℃槸鍚︽鍦ㄨ繍琛?
                val oppositeWasRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
                if (oppositeWasRunning) {
                    try {
                        // 澧炲姞瓒呮椂鏃堕棿锛欱oxService.close() 鍙兘闇€瑕佽緝闀挎椂闂撮噴鏀剧鍙?
                        withTimeout(8000L) {
                            // 浣跨敤 drop(1) 璺宠繃褰撳墠鍊硷紝绛夊緟鐪熸鐨勭姸鎬佸彉鍖?
                            SingBoxRemote.state
                                .drop(1)
                                .first { it == ServiceState.STOPPED }
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "Timeout waiting for opposite service to stop")
                    }
                }
                // 鍘熷洜: BoxService.close() 鍚庣鍙ｉ噴鏀惧彲鑳芥湁寤惰繜
                delay(500)
            }

            // 鐢熸垚閰嶇疆鏂囦欢骞跺惎鍔?VPN 鏈嶅姟
            try {
                // 鍦ㄧ敓鎴愰厤缃墠鍏堟墽琛屽己鍒惰縼绉伙紝淇鍙兘瀵艰嚧 404 鐨勬棫閰嶇疆
                val configResult = withContext(Dispatchers.IO) {
                    val settingsRepository = com.kunk.singbox.repository.SettingsRepository.getInstance(context)
                    settingsRepository.checkAndMigrateRuleSets()
                    configRepository.generateConfigFile()
                }
                if (configResult == null) {
                    _connectionState.value = ConnectionState.Error
                    _testStatus.value = getApplication<Application>().getString(R.string.dashboard_config_generation_failed)
                    delay(2000)
                    _testStatus.value = null
                    return@launch
                }

                val useTun = desiredMode == VpnStateStore.CoreMode.VPN
                val intent = if (useTun) {
                    Intent(context, SingBoxService::class.java).apply {
                        action = SingBoxService.ACTION_START
                        putExtra(SingBoxService.EXTRA_CONFIG_PATH, configResult.path)
                        // 浠庡仠姝㈢姸鎬佸惎鍔ㄦ椂锛屽己鍒舵竻鐞嗙紦瀛橈紝纭繚浣跨敤閰嶇疆鏂囦欢涓€変腑鐨勮妭鐐?
                        // 淇 bug: App 鏇存柊鍚?cache.db 淇濈暀浜嗘棫鐨勯€変腑鑺傜偣锛屽鑷?UI 涓婇€変腑鐨勬柊鑺傜偣鏃犳晥
                        putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                    }
                } else {
                    Intent(context, ProxyOnlyService::class.java).apply {
                        action = ProxyOnlyService.ACTION_START
                        putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, configResult.path)
                        // 鍚岀悊锛孭roxy 妯″紡涔熼渶瑕佹竻鐞嗙紦瀛?
                        putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                // 2) 鍚庣画鍙湪鏈嶅姟绔槑纭け璐ワ紙lastErrorFlow锛夋垨鏈嶅姟寮傚父閫€鍑烘椂鎵嶇疆 Error
                startMonitorJob?.cancel()
                startMonitorJob = viewModelScope.launch {
                    val startTime = System.currentTimeMillis()
                    val quickFeedbackMs = 1000L
                    var showedStartingHint = false

                    while (true) {
                        if (SingBoxRemote.isRunning.value) {
                            _connectionState.value = ConnectionState.Connected
                            startTrafficMonitor()
                            return@launch
                        }

                        val err = SingBoxRemote.lastError.value
                        if (!err.isNullOrBlank()) {
                            _connectionState.value = ConnectionState.Error
                            _testStatus.value = err
                            delay(3000)
                            _testStatus.value = null
                            return@launch
                        }

                        val elapsed = System.currentTimeMillis() - startTime
                        if (!showedStartingHint && elapsed >= quickFeedbackMs) {
                            showedStartingHint = true
                            _testStatus.value = getApplication<Application>().getString(R.string.connection_connecting)
                            lastErrorToastJob?.cancel()
                            lastErrorToastJob = viewModelScope.launch {
                                delay(1200)
                                if (_testStatus.value == getApplication<Application>().getString(R.string.connection_connecting)) {
                                    _testStatus.value = null
                                }
                            }
                        }

                        val intervalMs = when {
                            elapsed < 10_000L -> 200L
                            elapsed < 60_000L -> 1000L
                            else -> 5000L
                        }
                        delay(intervalMs)
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error
                _testStatus.value = getApplication<Application>().getString(R.string.node_start_failed, e.message ?: "")
                delay(2000)
                _testStatus.value = null
            }
        }
    }

    private fun stopVpn() {
        val context = getApplication<Application>()
        startMonitorJob?.cancel()
        startMonitorJob = null
        stopTrafficMonitor()
        stopPingTest()
        // Immediately set to Idle for responsive UI
        _connectionState.value = ConnectionState.Idle
        _connectedAtElapsedMs.value = null
        _statsBase.value = ConnectionStats(0, 0, 0, 0, 0)

        val mode = VpnStateStore.getMode()
        val intent = when (mode) {
            VpnStateStore.CoreMode.PROXY -> Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_STOP
            }
            else -> Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_STOP
            }
        }
        context.startService(intent)
    }

    private fun startPingTest() {
        if (_connectionState.value != ConnectionState.Connected) return
        if (_isPingTesting.value) return

        val targetNodeId = activeNodeId.value
        if (targetNodeId.isNullOrBlank()) {
            Log.w(TAG, "No active node to test ping")
            return
        }

        stopPingTest()
        pingTestJob = viewModelScope.launch {
            _isPingTesting.value = true
            try {
                configRepository.testAllNodesLatency(targetNodeIds = listOf(targetNodeId))
            } catch (e: Exception) {
                Log.e(TAG, "Error during ping test", e)
            } finally {
                _isPingTesting.value = false
            }
        }
    }

    private fun stopPingTest() {
        pingTestJob?.cancel()
        pingTestJob = null
        _isPingTesting.value = false
    }

    fun retestCurrentNodePing() {
        startPingTest()
    }

    fun onVpnPermissionResult(granted: Boolean) {
        _vpnPermissionNeeded.value = false
        if (granted) {
            startCore()
        }
    }

    fun updateAllSubscriptions() {
        viewModelScope.launch {
            _updateStatus.value = getApplication<Application>().getString(R.string.common_loading)

            val result = configRepository.updateAllProfiles()

            // 鏍规嵁缁撴灉鏄剧ず涓嶅悓鐨勬彁绀?
            _updateStatus.value = result.toDisplayMessage(getApplication())
            delay(2500)
            _updateStatus.value = null
        }
    }

    fun testAllNodesLatency() {
        viewModelScope.launch {
            _testStatus.value = getApplication<Application>().getString(R.string.common_loading)
            val targetIds = nodes.value.map { it.id }
            configRepository.testAllNodesLatency(targetIds)
            _testStatus.value = getApplication<Application>().getString(R.string.dashboard_test_complete)
            delay(2000)
            _testStatus.value = null
        }
    }

    private fun startTrafficMonitor() {
        stopTrafficMonitor()

        lastUploadSpeed = 0
        lastDownloadSpeed = 0

        val uid = Process.myUid()
        val tx0 = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
        val rx0 = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }
        trafficBaseTxBytes = tx0
        trafficBaseRxBytes = rx0
        lastTrafficTxBytes = tx0
        lastTrafficRxBytes = rx0
        lastTrafficSampleAtElapsedMs = SystemClock.elapsedRealtime()

        // 璁板綍 BoxWrapper 鍒濆娴侀噺鍊?(鐢ㄤ簬璁＄畻鏈浼氳瘽娴侀噺)
        wrapperBaseUpload = if (BoxWrapperManager.isAvailable()) {
            BoxWrapperManager.getUploadTotal().let { if (it >= 0) it else 0L }
        } else {
            0L
        }
        wrapperBaseDownload = if (BoxWrapperManager.isAvailable()) {
            BoxWrapperManager.getDownloadTotal().let { if (it >= 0) it else 0L }
        } else {
            0L
        }

        trafficSmoothingJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)

                val nowElapsed = SystemClock.elapsedRealtime()

                val sample = if (BoxWrapperManager.isAvailable()) {
                    val wrapperUp = BoxWrapperManager.getUploadTotal()
                    val wrapperDown = BoxWrapperManager.getDownloadTotal()
                    if (wrapperUp >= 0 && wrapperDown >= 0) {
                        // 璁＄畻鏈浼氳瘽娴侀噺
                        val sessionUp = (wrapperUp - wrapperBaseUpload).coerceAtLeast(0L)
                        val sessionDown = (wrapperDown - wrapperBaseDownload).coerceAtLeast(0L)
                        Quadruple(wrapperUp, wrapperDown, sessionUp, sessionDown)
                    } else {
                        // BoxWrapper 杩斿洖鏃犳晥鍊硷紝鍥為€€鍒?TrafficStats
                        val sysTx = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
                        val sysRx = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }
                        Quadruple(sysTx, sysRx, (sysTx - trafficBaseTxBytes).coerceAtLeast(0L), (sysRx - trafficBaseRxBytes).coerceAtLeast(0L))
                    }
                } else {
                    // BoxWrapper 涓嶅彲鐢紝浣跨敤 TrafficStats
                    val sysTx = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
                    val sysRx = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }
                    Quadruple(sysTx, sysRx, (sysTx - trafficBaseTxBytes).coerceAtLeast(0L), (sysRx - trafficBaseRxBytes).coerceAtLeast(0L))
                }

                val dtMs = (nowElapsed - lastTrafficSampleAtElapsedMs).coerceAtLeast(1L)
                val dTx = (sample.tx - lastTrafficTxBytes).coerceAtLeast(0L)
                val dRx = (sample.rx - lastTrafficRxBytes).coerceAtLeast(0L)

                val up = (dTx * 1000L) / dtMs
                val down = (dRx * 1000L) / dtMs

                // 浼樺寲: 浣跨敤鑷€傚簲骞虫粦鍥犲瓙锛屾牴鎹€熷害鍙樺寲骞呭害鍔ㄦ€佽皟鏁?
                val uploadSmoothFactor = calculateAdaptiveSmoothFactor(up, lastUploadSpeed)
                val downloadSmoothFactor = calculateAdaptiveSmoothFactor(down, lastDownloadSpeed)

                val smoothedUp = if (lastUploadSpeed == 0L) up
                else (lastUploadSpeed * (1 - uploadSmoothFactor) + up * uploadSmoothFactor).toLong()
                val smoothedDown = if (lastDownloadSpeed == 0L) down
                else (lastDownloadSpeed * (1 - downloadSmoothFactor) + down * downloadSmoothFactor).toLong()

                lastUploadSpeed = smoothedUp
                lastDownloadSpeed = smoothedDown

                _statsBase.update { current ->
                    current.copy(
                        uploadSpeed = smoothedUp,
                        downloadSpeed = smoothedDown,
                        uploadTotal = sample.totalTx,
                        downloadTotal = sample.totalRx
                    )
                }

                lastTrafficTxBytes = sample.tx
                lastTrafficRxBytes = sample.rx
                lastTrafficSampleAtElapsedMs = nowElapsed
            }
        }
    }

    // 鐢ㄤ簬鍙屾簮娴侀噺缁熻鐨勮緟鍔╂暟鎹被
    private data class Quadruple(val tx: Long, val rx: Long, val totalTx: Long, val totalRx: Long)

    // BoxWrapper 娴侀噺鍩哄噯鍊?(鐢ㄤ簬璁＄畻鏈浼氳瘽娴侀噺)
    private var wrapperBaseUpload: Long = 0
    private var wrapperBaseDownload: Long = 0

    private fun stopTrafficMonitor() {
        trafficSmoothingJob?.cancel()
        trafficSmoothingJob = null
        lastUploadSpeed = 0
        lastDownloadSpeed = 0
        trafficBaseTxBytes = 0
        trafficBaseRxBytes = 0
        lastTrafficTxBytes = 0
        lastTrafficRxBytes = 0
        lastTrafficSampleAtElapsedMs = 0
        wrapperBaseUpload = 0
        wrapperBaseDownload = 0
    }

    /**
     * 璁＄畻鑷€傚簲骞虫粦鍥犲瓙
     * @param current 褰撳墠閫熷害
     * @param previous 涓婁竴娆￠€熷害
     * @return 骞虫粦鍥犲瓙 (0.0-1.0),鍊艰秺澶у搷搴旇秺蹇?
     */
    private fun calculateAdaptiveSmoothFactor(current: Long, previous: Long): Double {
        if (previous <= 0) return 1.0

        // 璁＄畻鍙樺寲骞呭害姣斾緥
        val change = kotlin.math.abs(current - previous).toDouble()
        val ratio = change / previous

        // 鏍规嵁鍙樺寲骞呭害杩斿洖涓嶅悓鐨勫钩婊戝洜瀛?
        return when {
            ratio > 2.0 -> 0.7 // 澶у箙鍙樺寲(200%+),蹇€熷搷搴?
            ratio > 0.5 -> 0.4 // 涓瓑鍙樺寲(50%-200%),骞宠　鍝嶅簲
            ratio > 0.1 -> 0.25 // 灏忓箙鍙樺寲(10%-50%),閫傚害骞虫粦
            else -> 0.15 // 寰皬鍙樺寲(<10%),楂樺害骞虫粦
        }
    }

    private fun getRegionWeight(flag: String?): Int {
        if (flag.isNullOrBlank()) return 9999
        // Priority order: CN, HK, MO, TW, JP, KR, SG, US, Others
        return when (flag) {
            "馃嚚馃嚦" -> 0 // China
            "馃嚟馃嚢" -> 1 // Hong Kong
            "馃嚥馃嚧" -> 2 // Macau
            "馃嚬馃嚰" -> 3 // Taiwan
            "馃嚡馃嚨" -> 4 // Japan
            "馃嚢馃嚪" -> 5 // South Korea
            "馃嚫馃嚞" -> 6 // Singapore
            "馃嚭馃嚫" -> 7 // USA
            "馃嚮馃嚦" -> 8 // Vietnam
            "馃嚬馃嚟" -> 9 // Thailand
            "馃嚨馃嚟" -> 10 // Philippines
            "馃嚥馃嚲" -> 11 // Malaysia
            "馃嚠馃嚛" -> 12 // Indonesia
            "馃嚠馃嚦" -> 13 // India
            "馃嚪馃嚭" -> 14 // Russia
            "馃嚬馃嚪" -> 15 // Turkey
            "馃嚠馃嚬" -> 16 // Italy
            "馃嚛馃嚜" -> 17 // Germany
            "馃嚝馃嚪" -> 18 // France
            "馃嚦馃嚤" -> 19 // Netherlands
            "馃嚞馃嚙" -> 20 // UK
            "馃嚘馃嚭" -> 21 // Australia
            "馃嚚馃嚘" -> 22 // Canada
            "馃嚙馃嚪" -> 23 // Brazil
            "馃嚘馃嚪" -> 24 // Argentina
            else -> 1000 // Others
        }
    }

    /**
     * 鑾峰彇娲昏穬閰嶇疆鐨勫悕绉?
     */
    fun getActiveProfileName(): String? {
        val activeId = activeProfileId.value ?: return null
        return profiles.value.find { it.id == activeId }?.name
    }

    /**
     * 鑾峰彇娲昏穬鑺傜偣鐨勫悕绉?
     * 浣跨敤鏀硅繘鐨?getNodeById 鏂规硶纭繚鍗充娇閰嶇疆鍒囨崲鎴栬妭鐐瑰垪琛ㄦ湭瀹屽叏鍔犺浇鏃朵篃鑳芥纭樉绀?
     */
    fun getActiveNodeName(): String? {
        val activeId = activeNodeId.value ?: return null
        return configRepository.getNodeById(activeId)?.displayName
    }

    override fun onCleared() {
        super.onCleared()
        startMonitorJob?.cancel()
        startMonitorJob = null
        stopTrafficMonitor()
        stopPingTest()
    }
}
