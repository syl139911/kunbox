package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Intent
import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.*
import com.kunk.singbox.model.PingResultCode
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.repository.config.OutboundFixer
import com.kunk.singbox.repository.config.InboundBuilder
import com.kunk.singbox.repository.config.NodeLinkExporter
import com.kunk.singbox.utils.parser.SubscriptionManager
import com.kunk.singbox.utils.TcpPing
import com.kunk.singbox.database.AppDatabase
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.utils.StringBuilderPool
import com.kunk.singbox.utils.dns.DnsResolver
import com.kunk.singbox.utils.dns.DnsResolveStore
import com.tencent.mmkv.MMKV

class ConfigRepository(private val context: Context) {

    private data class NodeTestInfo(
        val outbound: Outbound,
        val nodeId: String,
        val profileId: String
    )

    private data class SubscriptionAttemptContext(
        val host: String,
        val userAgent: String,
        val isRemembered: Boolean
    )

    companion object {
        private const val TAG = "ConfigRepository"
        private const val PARALLEL_CONCURRENCY = 8
        private const val SUBSCRIPTION_CONNECT_TIMEOUT_SECONDS = 5L
        private const val SUBSCRIPTION_READ_TIMEOUT_SECONDS = 8L
        private const val SUBSCRIPTION_WRITE_TIMEOUT_SECONDS = 8L
        private const val SUBSCRIPTION_CALL_TIMEOUT_SECONDS = 10L
        private const val SUBSCRIPTION_FAILURE_THRESHOLD = 1
        private const val SUBSCRIPTION_CIRCUIT_BREAKER_WINDOW_MS = 10 * 60 * 1000L
        private val REGEX_TRAFFIC = Regex("([\\d.]+)\\s*([KMGTPE]?)B?")
        private val REGEX_KV_PAIRS =
            Regex("(?i)\\b(upload|download|total|expire)\\b\\s*[:=]\\s*\"?([^,;\\s\\n\\r}]+)\"?")
        private val REGEX_SUBSCRIPTION_USERINFO = Regex("(?i)subscription[-_]userinfo\\s*[:=]\\s*\"?([^\"\\n\\r]+)\"?")
        private val REGEX_TOTAL = Regex("TOT:([\\d.]+[KMGTPE]?)B?")
        private val REGEX_EXPIRE_DATE = Regex("Expires:(\\d{4}-\\d{2}-\\d{2})")
        private val REGEX_TRAFFIC_VALUE = Regex("([\\d.]+[KMGTPE]?)B?")
        private val REGEX_REMAINING =
            Regex("(?i)(remaining|balance)\\s*[:=]?\\s*([\\d.]+\\s*[KMGTPE]?)\\s*B?")
        private val REGEX_EXPIRE = Regex("(?i)(expiry|expires?|expire)\\s*[:=]?\\s*([^\\s,;]+)")
        private val REGEX_SANITIZE_UUID = Regex("(?i)uuid\\s*[:=]\\s*[^\\\\n]+")
        private val REGEX_SANITIZE_PASSWORD = Regex("(?i)password\\s*[:=]\\s*[^\\\\n]+")
        private val REGEX_SANITIZE_TOKEN = Regex("(?i)token\\s*[:=]\\s*[^\\\\n]+")
        private val REGEX_WHITESPACE_DASH = Regex("[\\s\\-_]")

        private val TYPE_SAVED_PROFILES_DATA = object : TypeToken<SavedProfilesData>() {}.type
        private val TYPE_OUTBOUND_LIST = object : TypeToken<List<Outbound>>() {}.type
        private const val MAX_NODE_ID_CACHE_SIZE = 2000
        private val nodeIdCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(MAX_NODE_ID_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > MAX_NODE_ID_CACHE_SIZE
                }
            }
        )

        fun stableNodeId(profileId: String, outboundTag: String): String {
            val key = "$profileId|$outboundTag"
            synchronized(nodeIdCache) {
                nodeIdCache[key]?.let { return it }
                val id = StringBuilderPool.use { sb ->
                    sb.append(profileId).append('|').append(outboundTag)
                    java.util.UUID.nameUUIDFromBytes(sb.toString().toByteArray(Charsets.UTF_8)).toString()
                }
                nodeIdCache[key] = id
                return id
            }
        }

        internal fun buildBootstrapDnsRules(
            serverAddresses: List<String>,
            bootstrapV4Tag: String,
            bootstrapV6Tag: String,
            bootstrapTag: String
        ): List<DnsRule> {
            val bootstrapDomains = serverAddresses
                .mapNotNull { extractHostFromAddress(it) }
                .map { it.trim() }
                .filter { it.isNotEmpty() && !isIpAddressValue(it) && !it.equals("local", ignoreCase = true) }
                .distinct()

            if (bootstrapDomains.isEmpty()) {
                return emptyList()
            }

            return listOf(
                DnsRule(
                    domain = bootstrapDomains,
                    queryType = listOf("A"),
                    action = "route",
                    server = bootstrapV4Tag
                ),
                DnsRule(
                    domain = bootstrapDomains,
                    queryType = listOf("AAAA"),
                    action = "route",
                    server = bootstrapV6Tag
                ),
                DnsRule(
                    domain = bootstrapDomains,
                    action = "route",
                    server = bootstrapTag
                )
            )
        }

        internal fun isIpAddressValue(address: String?): Boolean {
            if (address.isNullOrBlank()) return false
            return (address.count { it == '.' } == 3 &&
                address.all { it.isDigit() || it == '.' }) ||
                address.contains(":")
        }

        @Suppress("ReturnCount")
        internal fun extractHostFromAddress(address: String): String? {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return null

            extractHostByUri(trimmed)?.let { return it }
            extractHostByUri("dns://$trimmed")?.let { return it }

            if (trimmed.startsWith("[") && trimmed.contains("]")) {
                return trimmed.substringAfter('[').substringBefore(']')
            }

            val colonCount = trimmed.count { it == ':' }
            if (colonCount == 1 && !trimmed.contains('/')) {
                return trimmed.substringBefore(':').takeIf { it.isNotBlank() }
            }

            return trimmed
        }

        private fun extractHostByUri(address: String): String? {
            return try {
                val uri = URI(address)
                uri.host
            } catch (_: Exception) {
                null
            }
        }
        private val REGEX_HTML_SUBSCRIPTION_INPUT = Regex(
            """<input[^>]+id=["']sub_url["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val REGEX_HTML_INPUT_VALUE = Regex(
            """value=["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        private val USER_AGENTS = listOf(
            "ClashMeta/1.18.0",
            "Clash.Meta/1.18.0",
            "Clash/1.18.0",
            "sing-box/1.13.1",
            "sing-box/1.13.0",
            "SFA/1.13.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        internal fun extractSubscriptionUrlFromHtml(html: String): String? {
            return REGEX_HTML_SUBSCRIPTION_INPUT.find(html)
                ?.value
                ?.let { inputTag -> REGEX_HTML_INPUT_VALUE.find(inputTag)?.groupValues?.getOrNull(1) }
                ?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }

        internal fun looksLikeHtmlSubscriptionPage(contentType: String?, body: String): Boolean {
            val normalizedContentType = contentType.orEmpty().lowercase()
            if ("text/html" in normalizedContentType) {
                return true
            }
            val trimmed = body.trimStart()
            return trimmed.startsWith("<!DOCTYPE html>", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true)
        }

        internal fun extractSubscriptionHost(url: String): String? {
            return runCatching { URI(url).host?.lowercase() }.getOrNull()
        }

        internal fun prioritizeUserAgents(preferredUserAgent: String?): List<String> {
            if (preferredUserAgent.isNullOrBlank()) return USER_AGENTS
            return buildList {
                add(preferredUserAgent)
                USER_AGENTS.forEach { userAgent ->
                    if (!userAgent.equals(preferredUserAgent, ignoreCase = true)) {
                        add(userAgent)
                    }
                }
            }
        }

        internal fun filterCircuitBrokenUserAgents(
            userAgents: List<String>,
            circuitBrokenUserAgents: Set<String>
        ): List<String> {
            if (circuitBrokenUserAgents.isEmpty()) return userAgents
            val available = userAgents.filterNot { userAgent ->
                circuitBrokenUserAgents.any { blocked ->
                    blocked.equals(userAgent, ignoreCase = true)
                }
            }
            return if (available.isNotEmpty()) available else userAgents
        }

        internal fun shouldRecordSubscriptionNetworkFailure(exception: Exception): Boolean {
            if (exception is ConnectException || exception is SocketTimeoutException) {
                return true
            }
            val message = exception.message.orEmpty().lowercase()
            return "failed to connect" in message || "timeout" in message
        }

        @Volatile
        private var instance: ConfigRepository? = null

        fun getInstance(context: Context): ConfigRepository {
            return instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val singBoxCore = SingBoxCore.getInstance(context)
    private val settingsRepository = SettingsRepository.getInstance(context)
    private val database = AppDatabase.getInstance(context)
    private val profileDao = database.profileDao()
    private val activeStateDao = database.activeStateDao()
    private val nodeLatencyDao = database.nodeLatencyDao()

    @Volatile
    private var cachedSettings: AppSettings? = null

    private fun getEffectiveTunStack(userSelected: TunStack): TunStack {
        val model = Build.MODEL
        if (model.contains("SM-G986U", ignoreCase = true)) {
            Log.w(TAG, "Device $model detected, forcing GVISOR stack (ignoring user selection: ${userSelected.name})")
            return TunStack.GVISOR
        }

        return userSelected
    }

    private fun getEffectiveTunMtu(settings: AppSettings): Int {
        val configuredMtu = settings.tunMtu
        if (!settings.tunMtuAuto) return configuredMtu

        val caps = getNetworkCapabilities() ?: return configuredMtu

        // Throughput-first for Wi-Fi/Ethernet; conservative for cellular.
        // QUIC-based proxies + QUIC traffic = double encapsulation,
        // requiring higher MTU to avoid fragmentation blackholes.
        val recommendedMtu = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1480
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1480
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1400
            else -> configuredMtu
        }

        // Auto MTU should never be more aggressive than user-configured MTU.
        return minOf(configuredMtu, recommendedMtu)
    }

    @Suppress("DEPRECATION")
    private fun getNetworkCapabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val physicalCaps = cm.allNetworks
            .asSequence()
            .mapNotNull { cm.getNetworkCapabilities(it) }
            .firstOrNull {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        return physicalCaps ?: cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    }
    private fun getClient(): okhttp3.OkHttpClient {
        val settings = cachedSettings ?: AppSettings()
        val timeout = settings.subscriptionUpdateTimeout.toLong()

        return NetworkClient.createClientWithoutRetry(
            connectTimeoutSeconds = timeout,
            readTimeoutSeconds = timeout,
            writeTimeoutSeconds = timeout
        )
    }

    private fun getSubscriptionClient(): OkHttpClient {
        return NetworkClient.createClientWithoutRetry(
            connectTimeoutSeconds = SUBSCRIPTION_CONNECT_TIMEOUT_SECONDS,
            readTimeoutSeconds = SUBSCRIPTION_READ_TIMEOUT_SECONDS,
            writeTimeoutSeconds = SUBSCRIPTION_WRITE_TIMEOUT_SECONDS,
            callTimeoutSeconds = SUBSCRIPTION_CALL_TIMEOUT_SECONDS
        )
    }

    private fun getProxyClient(): okhttp3.OkHttpClient? {
        val settings = cachedSettings ?: AppSettings()
        if (!com.kunk.singbox.ipc.VpnStateStore.getActive() || settings.proxyPort <= 0) {
            return null
        }
        val timeout = settings.subscriptionUpdateTimeout.toLong()
        return NetworkClient.createClientWithProxy(
            proxyPort = settings.proxyPort,
            connectTimeoutSeconds = timeout,
            readTimeoutSeconds = timeout,
            writeTimeoutSeconds = timeout
        )
    }

    private fun getSubscriptionProxyClient(): OkHttpClient? {
        val settings = cachedSettings ?: AppSettings()
        if (!VpnStateStore.getActive() || settings.proxyPort <= 0) {
            return null
        }
        return NetworkClient.createClientWithProxy(
            proxyPort = settings.proxyPort,
            connectTimeoutSeconds = SUBSCRIPTION_CONNECT_TIMEOUT_SECONDS,
            readTimeoutSeconds = SUBSCRIPTION_READ_TIMEOUT_SECONDS,
            writeTimeoutSeconds = SUBSCRIPTION_WRITE_TIMEOUT_SECONDS,
            callTimeoutSeconds = SUBSCRIPTION_CALL_TIMEOUT_SECONDS
        )
    }

    private fun getRememberedSubscriptionUserAgent(url: String): String? {
        val host = extractSubscriptionHost(url) ?: return null
        return subscriptionUaMemoryMmkv.decodeString(host, null)
    }

    private fun rememberSuccessfulSubscriptionUserAgent(url: String, userAgent: String) {
        val host = extractSubscriptionHost(url) ?: return
        subscriptionUaMemoryMmkv.encode(host, userAgent)
    }

    private fun buildSubscriptionUaHealthKey(host: String, userAgent: String, suffix: String): String {
        return "$host|$userAgent|$suffix"
    }

    private fun getCircuitBrokenUserAgents(host: String, nowMs: Long = System.currentTimeMillis()): Set<String> {
        return USER_AGENTS.filter { userAgent ->
            val blockedUntilKey = buildSubscriptionUaHealthKey(host, userAgent, "blocked_until")
            subscriptionUaHealthMmkv.decodeLong(blockedUntilKey, 0L) > nowMs
        }.toSet()
    }

    private fun clearSubscriptionUserAgentFailure(host: String, userAgent: String) {
        val failureCountKey = buildSubscriptionUaHealthKey(host, userAgent, "fail_count")
        val blockedUntilKey = buildSubscriptionUaHealthKey(host, userAgent, "blocked_until")
        subscriptionUaHealthMmkv.removeValueForKey(failureCountKey)
        subscriptionUaHealthMmkv.removeValueForKey(blockedUntilKey)
    }

    private fun recordSubscriptionUserAgentFailure(
        host: String,
        userAgent: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val failureCountKey = buildSubscriptionUaHealthKey(host, userAgent, "fail_count")
        val blockedUntilKey = buildSubscriptionUaHealthKey(host, userAgent, "blocked_until")
        val nextFailureCount = subscriptionUaHealthMmkv.decodeInt(failureCountKey, 0) + 1
        subscriptionUaHealthMmkv.encode(failureCountKey, nextFailureCount)
        if (nextFailureCount >= SUBSCRIPTION_FAILURE_THRESHOLD) {
            subscriptionUaHealthMmkv.encode(
                blockedUntilKey,
                nowMs + SUBSCRIPTION_CIRCUIT_BREAKER_WINDOW_MS
            )
        }
    }

    private fun buildSubscriptionUserAgents(url: String): List<String> {
        val prioritized = prioritizeUserAgents(getRememberedSubscriptionUserAgent(url))
        val host = extractSubscriptionHost(url) ?: return prioritized
        val circuitBrokenUserAgents = getCircuitBrokenUserAgents(host)
        return filterCircuitBrokenUserAgents(prioritized, circuitBrokenUserAgents)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nodeLinkParser = NodeLinkParser(gson)

    private val subscriptionManager = SubscriptionManager(listOf(
        SingBoxParser(gson),
        com.kunk.singbox.utils.parser.ClashYamlParser(),
        Base64Parser { nodeLinkParser.parse(it) }
    ))
    private val dnsResolver = DnsResolver()
    private val dnsResolveStore = DnsResolveStore.getInstance()

    private val _profiles = MutableStateFlow<List<ProfileUi>>(emptyList())
    val profiles: StateFlow<List<ProfileUi>> = _profiles.asStateFlow()

    private val _nodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val nodes: StateFlow<List<NodeUi>> = _nodes.asStateFlow()

    private val _allNodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val allNodes: StateFlow<List<NodeUi>> = _allNodes.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _activeNodeId = MutableStateFlow<String?>(null)
    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()

    private val maxConfigCacheSize = 10
    private val configCache: MutableMap<String, SingBoxConfig> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SingBoxConfig>(maxConfigCacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SingBoxConfig>?): Boolean {
                return size > maxConfigCacheSize
            }
        }
    )
    private val profileNodes = ConcurrentHashMap<String, List<NodeUi>>()
    private val profileResetJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val inFlightLatencyTests = ConcurrentHashMap<String, Deferred<Long>>()
    private val savedNodeLatencies = ConcurrentHashMap<String, Long>()
    @Volatile private var saveProfilesJob: kotlinx.coroutines.Job? = null
    private val saveDebounceMs = 300L
    private val saveProfilesMutex = Mutex()

    private val allNodesUiActiveCount = AtomicInteger(0)
    @Volatile private var allNodesLoadedForUi: Boolean = false

    @Volatile private var lastTagToNodeName: Map<String, String> = emptyMap()
    @Volatile private var lastRunOutboundTags: Set<String>? = null
    @Volatile private var lastRunProfileId: String? = null
    private val nodeSwitchInFlight = AtomicBoolean(false)
    private val profileLastSelectedNode = ConcurrentHashMap<String, String>()
    private val profileNodeMemoryMmkv: MMKV by lazy {
        MMKV.mmkvWithID("profile_node_memory", MMKV.SINGLE_PROCESS_MODE)
    }
    private val subscriptionUaMemoryMmkv: MMKV by lazy {
        MMKV.mmkvWithID("subscription_ua_memory", MMKV.SINGLE_PROCESS_MODE)
    }
    private val subscriptionUaHealthMmkv: MMKV by lazy {
        MMKV.mmkvWithID("subscription_ua_health", MMKV.SINGLE_PROCESS_MODE)
    }

    fun resolveNodeNameFromOutboundTag(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        if (tag.equals("PROXY", ignoreCase = true)) return null
        return when (tag) {
            "direct" -> context.getString(R.string.outbound_tag_direct)
            "block" -> context.getString(R.string.outbound_tag_block)
            else -> {
                lastTagToNodeName[tag]
                    ?: _allNodes.value.firstOrNull { it.name == tag }?.name
            }
        }
    }

    private val configDir: File
        get() = File(context.filesDir, "configs").also { it.mkdirs() }
    private val profilesFileJson: File
        get() = File(context.filesDir, "profiles.json")

    init {
        loadProfileNodeMemory()
        loadSavedProfiles()
        scope.launch {
            settingsRepository.settings.collect { settings ->
                cachedSettings = settings
            }
        }
    }

    private fun loadProfileNodeMemory() {
        profileNodeMemoryMmkv.allKeys()?.forEach { profileId ->
            val nodeId = profileNodeMemoryMmkv.decodeString(profileId, null)
            if (!nodeId.isNullOrBlank()) {
                profileLastSelectedNode[profileId] = nodeId
            }
        }
    }

    private fun saveProfileNodeMemory(profileId: String, nodeId: String) {
        profileLastSelectedNode[profileId] = nodeId
        profileNodeMemoryMmkv.encode(profileId, nodeId)
    }

    private fun getProfileLastSelectedNode(profileId: String): String? {
        return profileLastSelectedNode[profileId]
    }

    private fun loadConfig(profileId: String): SingBoxConfig? {
        configCache[profileId]?.let { return it }

        val configFile = File(configDir, "$profileId.json")
        if (!configFile.exists()) return null

        return try {
            val configJson = configFile.readText()
            var config = gson.fromJson(configJson, SingBoxConfig::class.java)
            config = deduplicateTags(config)
            cacheConfig(profileId, config)
            config
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config for profile: $profileId", e)
            null
        }
    }

    private fun cacheConfig(profileId: String, config: SingBoxConfig) {
        configCache[profileId] = config
    }

    private fun removeCachedConfig(profileId: String) {
        configCache.remove(profileId)
    }

    private fun saveProfiles() {
        saveProfilesJob?.cancel()
        saveProfilesJob = scope.launch {
            delay(saveDebounceMs)
            saveProfilesInternal()
        }
    }

    private fun saveProfilesImmediate() {
        saveProfilesJob?.cancel()
        saveProfilesJob = scope.launch {
            saveProfilesInternal()
        }
    }

    private suspend fun saveProfilesInternal() {
        saveProfilesMutex.withLock {
            try {
                val startTime = System.currentTimeMillis()
                val profiles = _profiles.value
                val activeProfileId = _activeProfileId.value
                val activeNodeId = _activeNodeId.value
                val latencies = mutableMapOf<String, Long>()
                profileNodes.values.flatten().forEach { node ->
                    node.latencyMs?.let { latencies[node.id] = it }
                }
                try {
                    activeStateDao.saveSync(ActiveStateEntity(
                        id = 1,
                        activeProfileId = activeProfileId,
                        activeNodeId = activeNodeId
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save active state synchronously", e)
                }

                val entities = profiles.mapIndexed { index, profile ->
                    ProfileEntity.fromUiModel(profile, sortOrder = index)
                }
                profileDao.insertAll(entities)
                if (latencies.isNotEmpty()) {
                    val latencyEntities = latencies.map { (nodeId, latency) ->
                        NodeLatencyEntity(nodeId = nodeId, latencyMs = latency)
                    }
                    nodeLatencyDao.insertAll(latencyEntities)
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Saved ${profiles.size} profiles to Room in ${elapsed}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save profiles", e)
            }
        }
    }

    private fun writeConfigFileOrThrow(profileId: String, config: SingBoxConfig) {
        val configFile = File(configDir, "$profileId.json")
        try {
            configFile.writeText(gson.toJson(config))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write config file for profile: $profileId", e)
            throw IllegalStateException("Failed to write config for profile $profileId", e)
        }
    }

    private suspend fun preResolveDomainsForProfileBestEffort(
        profileId: String,
        config: SingBoxConfig,
        dnsServer: String?
    ) {
        runCatching {
            preResolveDomainsForProfile(profileId, config, dnsServer)
        }.onFailure { e ->
            Log.w(TAG, "DNS pre-resolve failed for profile $profileId", e)
        }
    }

    private fun rollbackTransientProfileFile(profileId: String) {
        if (_profiles.value.any { it.id == profileId }) {
            return
        }
        removeCachedConfig(profileId)
        profileNodes.remove(profileId)
        val configFile = File(configDir, "$profileId.json")
        if (configFile.exists() && !configFile.delete()) {
            Log.w(TAG, "Failed to delete transient profile config: ${configFile.absolutePath}")
        }
    }

    private fun updateAllNodesAndGroups() {
        if (allNodesUiActiveCount.get() <= 0) {
            _allNodes.value = emptyList()
            return
        }

        val all = profileNodes.values.flatten()
        _allNodes.value = all
    }

    private suspend fun loadAllNodesSnapshot(): List<NodeUi> = withContext(Dispatchers.IO) {
        val profiles = _profiles.value
        if (profiles.isEmpty()) return@withContext emptyList()
        profiles.map { p ->
            async {
                val cfg = loadConfig(p.id) ?: return@async emptyList()
                extractNodesFromConfig(cfg, p.id)
            }
        }.awaitAll().flatten()
    }

    fun setAllNodesUiActive(active: Boolean) {
        if (active) {
            val after = allNodesUiActiveCount.incrementAndGet()
            if (after == 1 && !allNodesLoadedForUi) {
                scope.launch {
                    val profiles = _profiles.value
                    for (p in profiles) {
                        val cfg = loadConfig(p.id) ?: continue
                        val nodes = extractNodesFromConfig(cfg, p.id)
                        val nodesWithLatency = nodes.map { node ->
                            val latency = savedNodeLatencies[node.id]
                            if (latency != null) node.copy(latencyMs = latency) else node
                        }
                        profileNodes[p.id] = nodesWithLatency
                    }
                    updateAllNodesAndGroups()
                    allNodesLoadedForUi = true
                }
            }
        } else {
            while (true) {
                val cur = allNodesUiActiveCount.get()
                if (cur <= 0) break
                if (allNodesUiActiveCount.compareAndSet(cur, cur - 1)) break
            }
            if (allNodesUiActiveCount.get() <= 0) {
                allNodesLoadedForUi = false
                val activeId = _activeProfileId.value
                val keep = activeId?.let { profileNodes[it] }
                profileNodes.clear()
                if (activeId != null && keep != null) {
                    profileNodes[activeId] = keep
                }
                _allNodes.value = emptyList()
            }
        }
    }

    private fun updateLatencyInAllNodes(nodeId: String, latency: Long) {
        val latencyValue = normalizeLatencyValue(latency)
        savedNodeLatencies[nodeId] = latencyValue
        _allNodes.update { list ->
            list.map {
                if (it.id == nodeId) it.copy(latencyMs = latencyValue) else it
            }
        }
        scope.launch {
            try {
                nodeLatencyDao.upsert(nodeId, latencyValue)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist latency for $nodeId", e)
            }
        }
    }

    private suspend fun tcpLatencyFallback(outbound: Outbound): Long {
        if (!LatencyProbePolicy.shouldUseTcpFallback(outbound)) return -1L
        val host = outbound.server?.trim().orEmpty()
        if (host.isBlank()) return -1L
        val port = outbound.serverPort ?: 443
        val timeout = settingsRepository.settings.first().latencyTestTimeout
        return TcpPing.connect(host = host, port = port, timeout = timeout)
    }

    private suspend fun ipv6TcpLatencyFallback(outbound: Outbound): Long {
        val host = outbound.server?.trim().orEmpty()
        if (host.isBlank()) return -1L
        val port = outbound.serverPort ?: 443
        val timeout = settingsRepository.settings.first().latencyTestTimeout
        return TcpPing.connect(host = host, port = port, timeout = timeout)
    }

    private suspend fun testNodeLatencyViaRunningService(nodeTag: String): Long {
        val timeoutMs = settingsRepository.settings.first().latencyTestTimeout
        SingBoxRemote.ensureBound(context)
        val delay = SingBoxRemote.urlTestNodeDelay(
            groupTag = "PROXY",
            nodeTag = nodeTag,
            timeoutMs = timeoutMs
        )
        return normalizeLatencyValue(delay?.toLong() ?: -1L)
    }

    private fun normalizeLatencyValue(latency: Long): Long {
        return when {
            latency > 0L -> latency
            latency == PingResultCode.UNAVAILABLE -> PingResultCode.UNAVAILABLE
            latency == PingResultCode.IPV6_ONLY -> PingResultCode.IPV6_ONLY
            latency == 0L -> PingResultCode.UNAVAILABLE
            else -> PingResultCode.FAILED_TIMEOUT
        }
    }

    private fun resolveIpv6OnlyStatus(outbound: Outbound, latency: Long): Long {
        val normalized = normalizeLatencyValue(latency)
        if (normalized != PingResultCode.UNAVAILABLE) return normalized
        if (!isLikelyIpv6OnlyDomain(outbound.server)) return normalized
        return PingResultCode.IPV6_ONLY
    }

    private suspend fun prepareOfflineProbeOutbound(outbound: Outbound): Outbound {
        val host = outbound.server?.trim().orEmpty()
        if (host.isBlank() || isIpAddress(host)) return outbound
        return withContext(Dispatchers.IO) {
            val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return@withContext outbound
            val hasV4 = addresses.any { it is Inet4Address }
            val v6 = addresses.firstOrNull { it is Inet6Address } as? Inet6Address ?: return@withContext outbound
            if (hasV4) {
                outbound
            } else {
                val literal = v6.hostAddress?.substringBefore('%')
                if (literal.isNullOrBlank()) outbound else outbound.copy(server = literal)
            }
        }
    }

    private fun isLikelyIpv6OnlyDomain(server: String?): Boolean {
        val host = server?.trim().orEmpty()
        if (host.isBlank()) return false
        if (isIpAddress(host)) return false
        return runCatching {
            val addresses = InetAddress.getAllByName(host)
            val hasV6 = addresses.any { it is Inet6Address }
            val hasV4 = addresses.any { it is Inet4Address }
            hasV6 && !hasV4
        }.getOrDefault(false)
    }

    private fun applyLatencyResult(
        info: NodeTestInfo,
        latency: Long,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
    ) {
        val latencyValue = normalizeLatencyValue(latency)

        _nodes.update { list ->
            list.map {
                if (it.id == info.nodeId) it.copy(latencyMs = latencyValue) else it
            }
        }

        profileNodes[info.profileId] = profileNodes[info.profileId]?.map {
            if (it.id == info.nodeId) it.copy(latencyMs = latencyValue) else it
        } ?: emptyList()

        updateLatencyInAllNodes(info.nodeId, latency)
        onNodeComplete?.invoke(info.nodeId, latencyValue)
    }

    private fun buildNodeTestInfos(nodes: List<NodeUi>): List<NodeTestInfo> {
        return nodes.mapNotNull { node ->
            val config = loadConfig(node.sourceProfileId) ?: return@mapNotNull null
            val outbound = config.outbounds?.find { it.tag == node.name } ?: return@mapNotNull null
            NodeTestInfo(buildOutboundForRuntime(outbound), node.id, node.sourceProfileId)
        }
    }

    @Suppress("CognitiveComplexMethod")
    private suspend fun testRegularOutboundsLatency(
        infos: List<NodeTestInfo>,
        concurrency: Int,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
    ) {
        coroutineScope {
            if (infos.isEmpty()) return@coroutineScope

            if (VpnStateStore.getActive()) {
                if (SingBoxService.instance == null) {
                    val semaphore = Semaphore(concurrency)
                    infos.map { info ->
                        async {
                            semaphore.withPermit {
                                val latency = testNodeLatencyViaRunningService(info.outbound.tag)
                                applyLatencyResult(info, latency, onNodeComplete)
                            }
                        }
                    }.awaitAll()
                    return@coroutineScope
                }

                val tagToInfo = infos.associateBy { it.outbound.tag }
                val outbounds = infos.map { it.outbound }
                singBoxCore.testOutboundsLatency(outbounds) { tag, latency ->
                    val info = tagToInfo[tag] ?: return@testOutboundsLatency
                    applyLatencyResult(info, latency, onNodeComplete)
                }
                return@coroutineScope
            }

            val preparedInfoPairs = infos.map { info ->
                info to prepareOfflineProbeOutbound(info.outbound)
            }
            val infoByTag = preparedInfoPairs.associate { (info, outbound) -> outbound.tag to Pair(info, outbound) }
            val initialResults = ConcurrentHashMap<String, Long>()

            singBoxCore.testOutboundsLatency(preparedInfoPairs.map { it.second }) { tag, latency ->
                initialResults[tag] = latency
                if (latency > 0L) {
                    val pair = infoByTag[tag] ?: return@testOutboundsLatency
                    applyLatencyResult(pair.first, latency, onNodeComplete)
                }
            }

            val fallbackSemaphore = Semaphore(concurrency)
            preparedInfoPairs.map { (info, probeOutbound) ->
                async {
                    fallbackSemaphore.withPermit {
                        val latency = initialResults[probeOutbound.tag] ?: -1L
                        if (latency > 0L) return@withPermit

                        val finalLatency = if (latency > 0L) {
                            latency
                        } else {
                            val fallback = ipv6TcpLatencyFallback(probeOutbound)
                            if (fallback > 0L) fallback else resolveIpv6OnlyStatus(probeOutbound, latency)
                        }
                        applyLatencyResult(info, finalLatency, onNodeComplete)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun testTcpFallbackOutboundsLatency(
        infos: List<NodeTestInfo>,
        concurrency: Int,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
    ) {
        coroutineScope {
            if (infos.isEmpty()) return@coroutineScope

            val semaphore = Semaphore(concurrency)
            infos.map { info ->
                async {
                    semaphore.withPermit {
                        val latency = tcpLatencyFallback(info.outbound)
                        applyLatencyResult(info, latency, onNodeComplete)
                    }
                }
            }.awaitAll()
        }
    }

    fun reloadProfiles() {
        loadSavedProfiles()
    }

    private fun loadSavedProfiles() {
        try {
            val startTime = System.currentTimeMillis()
            val profileEntities = profileDao.getAllSync()
            val activeState = activeStateDao.getSync()
            val latencyEntities = nodeLatencyDao.getAllSync()

            if (profileEntities.isNotEmpty()) {
                val profiles = profileEntities.map { it.toUiModel().copy(updateStatus = UpdateStatus.Idle) }
                _profiles.value = profiles
                _activeProfileId.value = activeState?.activeProfileId
                savedNodeLatencies.clear()
                latencyEntities.forEach { savedNodeLatencies[it.nodeId] = it.latencyMs }

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Loaded ${profiles.size} profiles from Room in ${elapsed}ms")
                loadActiveProfileNodes(activeState?.activeProfileId, activeState?.activeNodeId)
                cleanupLegacyProfileFiles()
                return
            }
            val savedData: SavedProfilesData? = if (profilesFileJson.exists()) {
                Log.i(TAG, "Migrating profiles from JSON to Room...")
                val json = profilesFileJson.readText()
                gson.fromJson<SavedProfilesData>(json, TYPE_SAVED_PROFILES_DATA)
            } else {
                null
            }

            if (savedData != null) {
                val profiles = savedData.profiles.map { it.copy(updateStatus = UpdateStatus.Idle) }
                _profiles.value = profiles
                _activeProfileId.value = savedData.activeProfileId

                savedNodeLatencies.clear()
                savedNodeLatencies.putAll(savedData.nodeLatencies)
                val entities = profiles.mapIndexed { index, profile ->
                    ProfileEntity.fromUiModel(profile, sortOrder = index)
                }
                profileDao.insertAllSync(entities)
                if (savedData.activeProfileId != null || savedData.activeNodeId != null) {
                    activeStateDao.saveSync(ActiveStateEntity(
                        id = 1,
                        activeProfileId = savedData.activeProfileId,
                        activeNodeId = savedData.activeNodeId
                    ))
                }
                val latencies = savedData.nodeLatencies.map { (nodeId, latency) ->
                    NodeLatencyEntity(nodeId = nodeId, latencyMs = latency)
                }
                if (latencies.isNotEmpty()) {
                    scope.launch { nodeLatencyDao.insertAll(latencies) }
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Migrated ${profiles.size} profiles to Room in ${elapsed}ms")
                loadActiveProfileNodes(savedData.activeProfileId, savedData.activeNodeId)
                cleanupLegacyProfileFiles()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved profiles", e)
        }
    }

    private fun loadActiveProfileNodes(activeProfileId: String?, activeNodeId: String?) {
        if (activeProfileId == null) return
        val configFile = File(configDir, "$activeProfileId.json")
        if (!configFile.exists()) return

        scope.launch {
            try {
                val configJson = configFile.readText()
                val config = gson.fromJson(configJson, SingBoxConfig::class.java)
                val nodes = extractNodesFromConfig(config, activeProfileId)
                val nodesWithLatency = nodes.map { node ->
                    val latency = savedNodeLatencies[node.id]
                    if (latency != null) node.copy(latencyMs = latency) else node
                }
                profileNodes[activeProfileId] = nodesWithLatency
                cacheConfig(activeProfileId, config)
                if (activeProfileId == _activeProfileId.value) {
                    _nodes.value = nodesWithLatency
                    _activeNodeId.value = when {
                        !activeNodeId.isNullOrBlank() && nodesWithLatency.any { it.id == activeNodeId } -> activeNodeId
                        nodesWithLatency.isNotEmpty() -> nodesWithLatency.first().id
                        else -> null
                    }
                }
                if (allNodesUiActiveCount.get() > 0) {
                    updateAllNodesAndGroups()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config for profile: $activeProfileId", e)
            }
        }
    }

    private fun cleanupLegacyProfileFiles() {
        scope.launch {
            try {
                if (profilesFileJson.exists()) {
                    profilesFileJson.delete()
                    Log.i(TAG, "Deleted legacy JSON profiles file")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup legacy profile files", e)
            }
        }
    }

    data class SubscriptionUserInfo(
        val upload: Long = 0,
        val download: Long = 0,
        val total: Long = 0,
        val expire: Long = 0
    )

    private data class FetchResult(
        val config: SingBoxConfig,
        val userInfo: SubscriptionUserInfo?
    )

    private fun parseTrafficString(value: String): Long {
        val trimmed = value.trim().uppercase()
        val match = REGEX_TRAFFIC.find(trimmed) ?: return 0L

        val (numStr, unit) = match.destructured
        val num = numStr.toDoubleOrNull() ?: return 0L

        val multiplier = when (unit) {
            "K" -> 1024L
            "M" -> 1024L * 1024
            "G" -> 1024L * 1024 * 1024
            "T" -> 1024L * 1024 * 1024 * 1024
            "P" -> 1024L * 1024 * 1024 * 1024 * 1024
            else -> 1L
        }

        return (num * multiplier).toLong()
    }

    private fun parseDateString(value: String): Long {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            (sdf.parse(value.trim())?.time ?: 0L) / 1000 // Convert to seconds
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseExpireValue(raw: String): Long {
        val normalized = raw.trim().trim('"', '\'')
        if (normalized.isBlank()) return 0L
        val lower = normalized.lowercase()
        if (lower.contains("never") || lower.contains("permanent") || lower.contains("forever") || lower.contains("unlimited")) {
            return -1L
        }
        return if (normalized.contains("-")) {
            parseDateString(normalized)
        } else {
            normalized.toLongOrNull() ?: 0L
        }
    }

    private fun parseSubscriptionUserInfo(header: String?, bodyDecoded: String? = null): SubscriptionUserInfo? {
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        var found = false
        var totalSpecified = false

        fun isUnlimitedValue(raw: String): Boolean {
            val normalized = raw.trim().lowercase()
            return normalized == "unlimited" || normalized == "infinite" || normalized == "infinity" || normalized == "inf" || normalized == "INF"
        }

        fun parseTrafficValue(raw: String): Long {
            val normalized = raw.trim().trim('"', '\'')
            return normalized.toLongOrNull() ?: parseTrafficString(normalized)
        }

        fun applyKeyValue(key: String, rawValue: String) {
            when (key.lowercase()) {
                "upload" -> {
                    upload = parseTrafficValue(rawValue)
                    found = true
                }
                "download" -> {
                    download = parseTrafficValue(rawValue)
                    found = true
                }
                "total" -> {
                    totalSpecified = true
                    total = if (isUnlimitedValue(rawValue)) -1L else parseTrafficValue(rawValue)
                    found = true
                }
                "expire" -> {
                    expire = parseExpireValue(rawValue)
                    found = true
                }
            }
        }

        fun parseKeyValuePairs(text: String) {
            REGEX_KV_PAIRS.findAll(text).forEach { match ->
                applyKeyValue(match.groupValues[1], match.groupValues[2])
            }
        }

        fun parseHeaderLike(text: String) {
            text.split(",", ";").forEach { part ->
                val kv = part.trim().split("=", ":", limit = 2)
                if (kv.size == 2) {
                    applyKeyValue(kv[0].trim(), kv[1].trim())
                }
            }
        }
        if (!header.isNullOrBlank()) {
            try {
                parseHeaderLike(header)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Subscription-Userinfo header: $header", e)
            }
        }
        if (bodyDecoded != null && (!found || total == 0L)) {
            try {
                val userInfoIndex = bodyDecoded.indexOf("subscription-userinfo", ignoreCase = true)
                val userInfoAltIndex = if (userInfoIndex >= 0) userInfoIndex else bodyDecoded.indexOf("subscription_userinfo", ignoreCase = true)
                if (userInfoAltIndex >= 0) {
                    val endIndex = (userInfoAltIndex + 800).coerceAtMost(bodyDecoded.length)
                    val snippet = bodyDecoded.substring(userInfoAltIndex, endIndex)
                    val inlineMatch = REGEX_SUBSCRIPTION_USERINFO.find(snippet)
                    if (inlineMatch != null) {
                        parseHeaderLike(inlineMatch.groupValues[1])
                    }
                    parseKeyValuePairs(snippet)
                }

                val firstLine = bodyDecoded.lines().firstOrNull()?.trim()
                if (firstLine != null && (firstLine.startsWith("STATUS=") || firstLine.contains("TOT:") || firstLine.contains("Expires:"))) {
                    val totalMatch = REGEX_TOTAL.find(firstLine)
                    if (totalMatch != null) {
                        totalSpecified = true
                        total = parseTrafficString(totalMatch.groupValues[1])
                        found = true
                    }
                    val expireMatch = REGEX_EXPIRE_DATE.find(firstLine)
                    if (expireMatch != null) {
                        expire = parseDateString(expireMatch.groupValues[1])
                        found = true
                    }
                    var usedAccumulator = 0L
                    val parts = firstLine.substringAfter("STATUS=").split(",")
                    parts.forEach { part ->
                        if (part.contains("TOT:")) return@forEach
                        if (part.contains("Expires:")) return@forEach
                        val match = REGEX_TRAFFIC_VALUE.find(part)
                        if (match != null) {
                            usedAccumulator += parseTrafficString(match.groupValues[1])
                            found = true
                        }
                    }

                    if (usedAccumulator > 0) {
                        download = usedAccumulator
                        upload = 0
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse info from body: ${bodyDecoded.take(100)}", e)
            }
        }

        if (!found) return null
        if (totalSpecified && total <= 0L) {
            total = -1L
        }
        return SubscriptionUserInfo(upload, download, total, expire)
    }

    private fun parseUserInfoFromOutbounds(outbounds: List<Outbound>?): SubscriptionUserInfo? {
        if (outbounds.isNullOrEmpty()) return null
        var remainingBytes: Long? = null
        var expireValue: Long? = null

        outbounds.forEach { outbound ->
            val tag = outbound.tag
            if (remainingBytes == null) {
                val match = REGEX_REMAINING.find(tag)
                if (match != null) {
                    remainingBytes = parseTrafficString(match.groupValues[2])
                }
            }
            if (expireValue == null) {
                val match = REGEX_EXPIRE.find(tag)
                if (match != null) {
                    expireValue = parseExpireValue(match.groupValues[2])
                }
            }
        }

        if (remainingBytes == null && expireValue == null) return null
        return SubscriptionUserInfo(
            upload = 0,
            download = remainingBytes ?: 0,
            total = if (remainingBytes != null) -2L else 0L,
            expire = expireValue ?: 0L
        )
    }

    private fun mergeUserInfo(primary: SubscriptionUserInfo?, fallback: SubscriptionUserInfo?): SubscriptionUserInfo? {
        if (primary == null) return fallback
        if (fallback == null) return primary
        return SubscriptionUserInfo(
            upload = if (primary.upload > 0) primary.upload else fallback.upload,
            download = if (primary.download > 0) primary.download else fallback.download,
            total = if (primary.total != 0L) primary.total else fallback.total,
            expire = if (primary.expire != 0L) primary.expire else fallback.expire
        )
    }

    private fun logHtmlSubscriptionPage(userAgent: String, responseBody: String) {
        val extractedUrl = extractSubscriptionUrlFromHtml(responseBody)
        if (!extractedUrl.isNullOrBlank()) {
            Log.i(
                TAG,
                "Subscription endpoint returned HTML info page with UA '$userAgent', " +
                    "embedded subscription URL: $extractedUrl"
            )
        } else {
            Log.w(TAG, "Subscription endpoint returned HTML info page with UA '$userAgent'")
        }
    }

    private fun parseSubscriptionResponse(
        userAgent: String,
        contentType: String?,
        responseBody: String,
        subscriptionUserInfoHeader: String?
    ): FetchResult? {
        if (looksLikeHtmlSubscriptionPage(contentType, responseBody)) {
            logHtmlSubscriptionPage(userAgent, responseBody)
            return null
        }

        val config = subscriptionManager.parse(responseBody)
        if (config == null || config.outbounds.isNullOrEmpty()) {
            Log.w(TAG, "Failed to parse subscription response with UA '$userAgent'")
            return null
        }

        val headerUserInfo = parseSubscriptionUserInfo(subscriptionUserInfoHeader, responseBody)
        val outboundUserInfo = parseUserInfoFromOutbounds(config.outbounds)
        val userInfo = mergeUserInfo(headerUserInfo, outboundUserInfo)
        return FetchResult(config, userInfo)
    }

    private fun buildSubscriptionRequest(url: String, userAgent: String): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/yaml,text/yaml,text/plain,application/json,*/*")
            .build()
    }

    private fun logSubscriptionAttempt(
        level: Int,
        message: String,
        context: SubscriptionAttemptContext,
        costMs: Long,
        extra: String? = null
    ) {
        val logMessage = buildString {
            append(message)
            append(": host=")
            append(context.host)
            append(", ua='")
            append(context.userAgent)
            append("', cached=")
            append(context.isRemembered)
            append(", cost=")
            append(costMs)
            append("ms")
            if (!extra.isNullOrBlank()) {
                append(", ")
                append(extra)
            }
        }
        when (level) {
            Log.INFO -> Log.i(TAG, logMessage)
            Log.WARN -> Log.w(TAG, logMessage)
            else -> Log.d(TAG, logMessage)
        }
    }

    private fun logSubscriptionParseResult(
        fetchResult: FetchResult?,
        contentType: String?,
        context: SubscriptionAttemptContext,
        costMs: Long
    ) {
        val message = if (fetchResult != null) {
            "Subscription request succeeded"
        } else {
            "Subscription response unusable"
        }
        val level = if (fetchResult != null) Log.INFO else Log.WARN
        logSubscriptionAttempt(
            level = level,
            message = message,
            context = context,
            costMs = costMs,
            extra = "contentType='$contentType'"
        )
    }

    private fun executeSubscriptionAttempt(
        client: OkHttpClient,
        url: String,
        context: SubscriptionAttemptContext,
        onProgress: (String) -> Unit
    ): FetchResult? {
        val startedAt = System.currentTimeMillis()
        val request = buildSubscriptionRequest(url, context.userAgent)

        client.newCall(request).execute().use { response ->
            val costMs = System.currentTimeMillis() - startedAt
            if (!response.isSuccessful) {
                logSubscriptionAttempt(
                    level = Log.WARN,
                    message = "Subscription request failed",
                    context = context,
                    costMs = costMs,
                    extra = "code=${response.code}"
                )
                throw Exception("HTTP ${response.code}: ${response.message}")
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                logSubscriptionAttempt(
                    level = Log.WARN,
                    message = "Empty subscription response",
                    context = context,
                    costMs = costMs
                )
                throw Exception("Subscription response body is empty")
            }

            onProgress("Parsing subscription response...")

            val contentType = response.header("Content-Type")
            val fetchResult = parseSubscriptionResponse(
                userAgent = context.userAgent,
                contentType = contentType,
                responseBody = responseBody,
                subscriptionUserInfoHeader = response.header("Subscription-Userinfo")
            )
            logSubscriptionParseResult(fetchResult, contentType, context, costMs)
            return fetchResult
        }
    }

    private fun fetchAndParseSubscription(
        url: String,
        onProgress: (String) -> Unit = {}
    ): FetchResult? {
        var lastError: Exception? = null
        val host = extractSubscriptionHost(url) ?: "unknown"
        val rememberedUserAgent = getRememberedSubscriptionUserAgent(url)
        val userAgents = buildSubscriptionUserAgents(url)
        val client = getSubscriptionProxyClient() ?: getSubscriptionClient()

        for ((index, userAgent) in userAgents.withIndex()) {
            try {
                onProgress("Trying subscription request with User-Agent (${index + 1}/${userAgents.size})...")
                val attemptContext = SubscriptionAttemptContext(
                    host = host,
                    userAgent = userAgent,
                    isRemembered = rememberedUserAgent.equals(userAgent, ignoreCase = true)
                )
                val fetchResult = executeSubscriptionAttempt(
                    client = client,
                    url = url,
                    context = attemptContext,
                    onProgress = onProgress
                )
                if (fetchResult != null) {
                    rememberSuccessfulSubscriptionUserAgent(url, userAgent)
                    clearSubscriptionUserAgentFailure(host, userAgent)
                    return fetchResult
                }
            } catch (e: Exception) {
                lastError = e
                if (shouldRecordSubscriptionNetworkFailure(e)) {
                    recordSubscriptionUserAgentFailure(host, userAgent)
                }
                Log.w(
                    TAG,
                    "Subscription fetch error: host=$host, ua='$userAgent', " +
                        "cached=${rememberedUserAgent.equals(userAgent, ignoreCase = true)}, error=${e.message}"
                )
                if (index == userAgents.lastIndex) {
                    throw e
                }
            }
        }

        lastError?.let { Log.e(TAG, "All User-Agent attempts failed", it) }
        return null
    }
    @Suppress("LongMethod", "CognitiveComplexMethod")
    suspend fun importFromSubscription(
        name: String,
        url: String,
        autoUpdateInterval: Int = 0,
        dnsPreResolve: Boolean = false,
        dnsServer: String? = null,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        var profileId: String? = null
        try {
            onProgress("Fetching subscription content...")
            val fetchResult = try {
                fetchAndParseSubscription(url, onProgress)
            } catch (e: Exception) {
                Log.e(TAG, "Subscription fetch failed", e)
                return@withContext Result.failure(e)
            }

            if (fetchResult == null) {
                return@withContext Result.failure(Exception(context.getString(R.string.profiles_parse_failed)))
            }

            val config = fetchResult.config
            val userInfo = fetchResult.userInfo

            onProgress(context.getString(R.string.profiles_extracting_nodes, 0, 0))

            profileId = UUID.randomUUID().toString()
            val deduplicatedConfig = deduplicateTags(config)
            val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, onProgress)

            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception(context.getString(R.string.nodes_no_valid_found)))
            }
            writeConfigFileOrThrow(profileId, deduplicatedConfig)
            val profile = ProfileUi(
                id = profileId,
                name = name,
                type = ProfileType.Subscription,
                url = url,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                autoUpdateInterval = autoUpdateInterval,
                updateStatus = UpdateStatus.Idle,
                expireDate = userInfo?.expire ?: 0,
                totalTraffic = userInfo?.total ?: 0,
                usedTraffic = (userInfo?.upload ?: 0) + (userInfo?.download ?: 0),
                dnsPreResolve = dnsPreResolve,
                dnsServer = dnsServer
            )
            cacheConfig(profileId, deduplicatedConfig)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()
            _profiles.update { it + profile }
            saveProfiles()
            if (_activeProfileId.value == null) {
                setActiveProfile(profileId)
            }
            if (autoUpdateInterval > 0) {
                com.kunk.singbox.service.SubscriptionAutoUpdateWorker.schedule(context, profileId, autoUpdateInterval)
            }
            if (dnsPreResolve) {
                onProgress("Pre-resolving domains for imported profile...")
                preResolveDomainsForProfileBestEffort(profileId, deduplicatedConfig, dnsServer)
            }

            onProgress(context.getString(R.string.profiles_import_success, nodes.size.toString()))

            Result.success(profile)
        } catch (e: Exception) {
            profileId?.let { rollbackTransientProfileFile(it) }
            Log.e(TAG, "Subscription import failed", e)
            val msg = when (e) {
                is java.net.SocketTimeoutException -> "Connection timeout, please check your network"
                is java.net.UnknownHostException -> "Failed to resolve domain, please check the link"
                is javax.net.ssl.SSLHandshakeException -> "SSL certificate validation failed"
                else -> e.message ?: context.getString(R.string.profiles_import_failed)
            }
            Result.failure(Exception(msg))
        }
    }

    suspend fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType = ProfileType.Imported,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        var profileId: String? = null
        try {
            onProgress(context.getString(R.string.common_loading))

            val normalized = normalizeImportedContent(content)
            val config = subscriptionManager.parse(normalized)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.profiles_parse_failed)))

            onProgress(context.getString(R.string.profiles_extracting_nodes, 0, 0))

            profileId = UUID.randomUUID().toString()
            val deduplicatedConfig = deduplicateTags(config)
            val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, onProgress)

            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception(context.getString(R.string.nodes_no_valid_found)))
            }

            writeConfigFileOrThrow(profileId, deduplicatedConfig)

            val profile = ProfileUi(
                id = profileId,
                name = name,
                type = profileType,
                url = null,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                updateStatus = UpdateStatus.Idle
            )

            cacheConfig(profileId, deduplicatedConfig)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()

            _profiles.update { it + profile }
            saveProfiles()

            if (_activeProfileId.value == null) {
                setActiveProfile(profileId)
            }

            onProgress(context.getString(R.string.profiles_import_success, nodes.size.toString()))

            Result.success(profile)
        } catch (e: Exception) {
            profileId?.let { rollbackTransientProfileFile(it) }
            Log.e(TAG, "Failed to import profile from content", e)
            Result.failure(e)
        }
    }

    private fun normalizeImportedContent(content: String): String {
        val trimmed = content.trim().trimStart('\uFEFF')
        val lines = trimmed.lines().toMutableList()

        fun isFenceLine(line: String): Boolean {
            val t = line.trim()
            if (t.startsWith("```")) return true
            return t.length >= 2 && t.all { it == '`' }
        }

        if (lines.isNotEmpty() && isFenceLine(lines.first())) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty() && isFenceLine(lines.last())) {
            lines.removeAt(lines.lastIndex)
        }

        return lines.joinToString("\n").trim()
    }

    private fun tryDecodeBase64(content: String): String? {
        val s = content.trim().trimStart('\uFEFF')
        if (s.isBlank()) return null
        val candidates = arrayOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = Base64.decode(s, flags)
                val text = String(decoded)
                if (text.isNotBlank()) return text
            } catch (e: Exception) {
                Log.v(TAG, "Base64 decode attempt failed with flags=$flags", e)
            }
        }
        return null
    }

    private fun extractOutboundsOnly(config: SingBoxConfig): SingBoxConfig {
        val outbounds = config.outbounds ?: config.proxies ?: emptyList()
        return SingBoxConfig(outbounds = outbounds)
    }

    private fun extractOutboundsFromJson(jsonContent: String): List<Outbound>? {
        val trimmed = jsonContent.trim()
        if (!trimmed.startsWith("{")) return null

        return try {
            val jsonObject = JsonParser.parseString(trimmed).asJsonObject
            val outboundsElement = jsonObject.get("outbounds") ?: jsonObject.get("proxies")
            if (outboundsElement != null && outboundsElement.isJsonArray) {
                val outbounds: List<Outbound> = gson.fromJson(outboundsElement, TYPE_OUTBOUND_LIST)
                if (outbounds.isNotEmpty()) {
                    return outbounds
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "extractOutboundsFromJson failed: ${e.message}")
            null
        }
    }

    private fun sanitizeSubscriptionSnippet(content: String): String {
        val snippet = content.take(200)
        return REGEX_SANITIZE_UUID.replace(
            REGEX_SANITIZE_PASSWORD.replace(
                REGEX_SANITIZE_TOKEN.replace(snippet, "token=***"),
                "password=***"
            ),
            "uuid=***"
        )
    }

    private val clashYamlParser = com.kunk.singbox.utils.parser.ClashYamlParser()

    private fun parseClashYamlConfig(content: String): SingBoxConfig? {
        return if (clashYamlParser.canParse(content)) {
            clashYamlParser.parse(content)
        } else {
            null
        }
    }

    private fun parseSubscriptionResponse(content: String): SingBoxConfig? {
        val normalizedContent = normalizeImportedContent(content)
        try {
            val outbounds = extractOutboundsFromJson(normalizedContent)
            if (outbounds != null && outbounds.isNotEmpty()) {
                return SingBoxConfig(outbounds = outbounds)
            } else {
                Log.w(TAG, "Parsed as JSON but outbounds/proxies is empty/null. content snippet: ${sanitizeSubscriptionSnippet(normalizedContent)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract outbounds from JSON: ${e.message}")
        }
        try {
            val yamlConfig = parseClashYamlConfig(normalizedContent)
            if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                return extractOutboundsOnly(yamlConfig)
            }
        } catch (_: Exception) {
        }
        try {
            val decoded = tryDecodeBase64(normalizedContent)
            if (decoded.isNullOrBlank()) {
                throw IllegalStateException("base64 decode failed")
            }
            try {
                val outbounds = extractOutboundsFromJson(decoded)
                if (outbounds != null && outbounds.isNotEmpty()) {
                    return SingBoxConfig(outbounds = outbounds)
                } else {
                    Log.w(TAG, "Parsed decoded Base64 as JSON but outbounds is empty/null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract outbounds from decoded Base64 JSON: ${e.message}")
            }

            try {
                val yamlConfig = parseClashYamlConfig(decoded)
                if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                    return extractOutboundsOnly(yamlConfig)
                }
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
        }
        try {
            val lines = normalizedContent.trim().lines().filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                val decoded = tryDecodeBase64(normalizedContent) ?: normalizedContent

                val decodedLines = decoded.trim().lines().filter { it.isNotBlank() }
                val outbounds = mutableListOf<Outbound>()

                for (line in decodedLines) {
                    val cleanedLine = line.trim()
                        .removePrefix("- ")
                        .removePrefix("\"")
                        .trim()
                        .trim('`', '"', '\'')
                    val outbound = parseNodeLink(cleanedLine)
                    if (outbound != null) {
                        outbounds.add(outbound)
                    }
                }

                if (outbounds.isNotEmpty()) {
                    return SingBoxConfig(
                        outbounds = outbounds
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse subscription response as node links", e)
        }

        return null
    }

    private fun parseNodeLink(link: String): Outbound? {
        return nodeLinkParser.parse(link)
    }

    private suspend fun extractNodesFromConfig(
        config: SingBoxConfig,
        profileId: String,
        onProgress: ((String) -> Unit)? = null
    ): List<NodeUi> = withContext(Dispatchers.Default) {
        val outbounds = config.outbounds ?: return@withContext emptyList()
        val trafficRepo = TrafficRepository.getInstance(context)
        val groupOutbounds = outbounds.filter {
            it.type == "selector" || it.type == "urltest"
        }
        val nodeToGroup = mutableMapOf<String, String>()
        groupOutbounds.forEach { group ->
            group.outbounds?.forEach { nodeName ->
                nodeToGroup[nodeName] = group.tag
            }
        }
        val proxyTypes = setOf(
            "shadowsocks", "vmess", "vless", "trojan",
            "hysteria", "hysteria2", "tuic", "wireguard",
            "shadowtls", "ssh", "anytls", "naive", "http", "socks"
        )
        val detourTags = outbounds.mapNotNull { it.detour }.toSet()

        val validOutbounds = outbounds.filter {
            it.type in proxyTypes && it.tag !in detourTags
        }
        if (validOutbounds.isEmpty()) return@withContext emptyList()

        val total = validOutbounds.size
        val completed = AtomicInteger(0)
        val semaphore = Semaphore(PARALLEL_CONCURRENCY)

        val deferredNodes = validOutbounds.map { outbound ->
            async {
                semaphore.withPermit {
                    val node = createNodeUi(outbound, profileId, nodeToGroup, trafficRepo)
                    val done = completed.incrementAndGet()
                    if (done % 100 == 0 || done == total) {
                        onProgress?.invoke(context.getString(R.string.profiles_extracting_nodes, done, total))
                    }
                    node
                }
            }
        }

        deferredNodes.awaitAll().filterNotNull()
    }

    private fun extractNodesFromConfigSync(
        config: SingBoxConfig,
        profileId: String
    ): List<NodeUi> {
        val outbounds = config.outbounds ?: return emptyList()
        val trafficRepo = TrafficRepository.getInstance(context)
        val groupOutbounds = outbounds.filter {
            it.type == "selector" || it.type == "urltest"
        }
        val nodeToGroup = mutableMapOf<String, String>()
        groupOutbounds.forEach { group ->
            group.outbounds?.forEach { nodeName ->
                nodeToGroup[nodeName] = group.tag
            }
        }
        val proxyTypes = setOf(
            "shadowsocks", "vmess", "vless", "trojan",
            "hysteria", "hysteria2", "tuic", "wireguard",
            "shadowtls", "ssh", "anytls", "naive", "http", "socks"
        )
        val detourTags = outbounds.mapNotNull { it.detour }.toSet()

        val validOutbounds = outbounds.filter {
            it.type in proxyTypes && it.tag !in detourTags
        }
        if (validOutbounds.isEmpty()) return emptyList()

        return validOutbounds.mapNotNull { outbound ->
            createNodeUi(outbound, profileId, nodeToGroup, trafficRepo)
        }
    }

    private fun createNodeUi(
        outbound: Outbound,
        profileId: String,
        nodeToGroup: Map<String, String>,
        trafficRepo: TrafficRepository
    ): NodeUi? {
        if (outbound.tag.isBlank()) return null

        var group = nodeToGroup[outbound.tag] ?: "Default"
        if (group.contains("://") || group.length > 50) {
            group = "Default"
        }

        val id = stableNodeId(profileId, outbound.tag)

        return NodeUi(
            id = id,
            name = outbound.tag,
            protocol = outbound.type,
            group = group,
            latencyMs = null,
            isFavorite = false,
            sourceProfileId = profileId,
            trafficUsed = trafficRepo.getMonthlyTotal(id),
            tags = buildList {
                outbound.tls?.let {
                    if (it.enabled == true) add("TLS")
                    it.reality?.let { r -> if (r.enabled == true) add("Reality") }
                }
                outbound.transport?.type?.let { add(it.uppercase()) }
            }
        )
    }

    fun setActiveProfile(profileId: String, targetNodeId: String? = null) {
        val currentProfileId = _activeProfileId.value
        val currentNodeId = _activeNodeId.value
        if (currentProfileId != null && currentNodeId != null && currentProfileId != profileId) {
            saveProfileNodeMemory(currentProfileId, currentNodeId)
        }

        _activeProfileId.value = profileId
        val cached = profileNodes[profileId]

        fun updateState(nodes: List<NodeUi>) {
            _nodes.value = nodes

            val currentActiveId = _activeNodeId.value

            if (targetNodeId != null && nodes.any { it.id == targetNodeId }) {
                _activeNodeId.value = targetNodeId
            } else if (currentActiveId != null && nodes.any { it.id == currentActiveId }) {
                // keep current
            } else {
                val rememberedNodeId = getProfileLastSelectedNode(profileId)
                if (rememberedNodeId != null && nodes.any { it.id == rememberedNodeId }) {
                    _activeNodeId.value = rememberedNodeId
                } else if (nodes.isNotEmpty()) {
                    _activeNodeId.value = nodes.first().id
                }
            }
        }

        if (cached != null) {
            updateState(cached)
        } else {
            _nodes.value = emptyList()
            scope.launch {
                val cfg = loadConfig(profileId) ?: return@launch
                val nodes = extractNodesFromConfig(cfg, profileId)
                val nodesWithLatency = nodes.map { node ->
                    val latency = savedNodeLatencies[node.id]
                    if (latency != null) node.copy(latencyMs = latency) else node
                }
                profileNodes[profileId] = nodesWithLatency

                updateState(nodesWithLatency)

                if (allNodesUiActiveCount.get() > 0) {
                    updateAllNodesAndGroups()
                }
            }
        }
        saveProfilesImmediate()
    }

    sealed class NodeSwitchResult {
        object Success : NodeSwitchResult()
        object NotRunning : NodeSwitchResult()
        data class Failed(val reason: String) : NodeSwitchResult()
    }

    fun setActiveNodeIdOnly(nodeId: String) {
        _activeNodeId.value = nodeId
        _activeProfileId.value?.let { profileId ->
            saveProfileNodeMemory(profileId, nodeId)
        }
        saveProfilesImmediate()
    }

    suspend fun setActiveNode(nodeId: String): Boolean {
        val result = setActiveNodeWithResult(nodeId)
        return result is NodeSwitchResult.Success || result is NodeSwitchResult.NotRunning
    }

    suspend fun setActiveNodeWithResult(nodeId: String): NodeSwitchResult {
        if (!nodeSwitchInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "setActiveNodeWithResult: switch already in-flight, skip duplicate request for $nodeId")
            return NodeSwitchResult.Success
        }

        try {
            val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()

            // Check for cross-profile switch
            val targetNode = allNodesSnapshot.find { it.id == nodeId }
            if (targetNode != null && targetNode.sourceProfileId != _activeProfileId.value) {
                Log.i(TAG, "Cross-profile switch detected: ${_activeProfileId.value} -> ${targetNode.sourceProfileId}")

                // 2025-fix: Ensure profile is loaded synchronously before switching
                // This prevents race condition where _nodes is empty during generateConfigFile
                val profileId = targetNode.sourceProfileId
                withContext(Dispatchers.IO) {
                    if (profileNodes[profileId] == null) {
                        Log.i(TAG, "Pre-loading profile nodes for $profileId")
                        loadConfig(profileId)?.let { cfg ->
                            val nodes = extractNodesFromConfig(cfg, profileId)
                            val nodesWithLatency = nodes.map { node ->
                                val latency = savedNodeLatencies[node.id]
                                if (latency != null) node.copy(latencyMs = latency) else node
                            }
                            profileNodes[profileId] = nodesWithLatency
                        }
                    }
                }

                setActiveProfile(targetNode.sourceProfileId, nodeId)
            }

            _activeNodeId.value = nodeId
            saveProfilesImmediate()

            val remoteRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
            if (!remoteRunning) {
                Log.i(TAG, "setActiveNodeWithResult: VPN not running, skip hot switch")
                return NodeSwitchResult.NotRunning
            }

            return withContext(Dispatchers.IO) {
                var node = _nodes.value.find { it.id == nodeId }
                if (node == null) {
                    node = allNodesSnapshot.find { it.id == nodeId }
                }

                if (node == null) {
                    val msg = "Target node not found: $nodeId"
                    Log.w(TAG, msg)
                    return@withContext NodeSwitchResult.Failed(msg)
                }

                try {
                    val generationResult = generateConfigFile()
                    if (generationResult == null) {
                        val msg = context.getString(R.string.dashboard_config_generation_failed)
                        Log.e(TAG, msg)
                        return@withContext NodeSwitchResult.Failed(msg)
                    }

                    // ... [Skipping comments for brevity in replacement]
                    runCatching {
                        val oldCacheDb = File(context.filesDir, "cache.db")
                        if (oldCacheDb.exists()) oldCacheDb.delete()
                    }
                    val currentTags = generationResult.outboundTags
                    val currentProfileId = _activeProfileId.value
                    val isFirstSwitchWhileRunning = lastRunProfileId == null && remoteRunning
                    val profileChanged = (lastRunProfileId != null && lastRunProfileId != currentProfileId) || isFirstSwitchWhileRunning
                    val tagsActuallyChanged = lastRunOutboundTags != null && lastRunOutboundTags != currentTags
                    val isVpnStartingNotReady = SingBoxRemote.isStarting.value && !SingBoxRemote.isRunning.value
                    val needsConfigReload = lastRunOutboundTags == null && remoteRunning

                    val tagsChanged = tagsActuallyChanged ||
                        profileChanged ||
                        isVpnStartingNotReady ||
                        needsConfigReload

                    Log.d(
                        TAG,
                        "Switch decision: profileChanged=$profileChanged " +
                            "(last=$lastRunProfileId, cur=$currentProfileId, " +
                            "firstSwitch=$isFirstSwitchWhileRunning), " +
                            "tagsActuallyChanged=$tagsActuallyChanged, " +
                            "isVpnStartingNotReady=$isVpnStartingNotReady, " +
                            "needsConfigReload=$needsConfigReload, tagsChanged=$tagsChanged"
                    )
                    lastRunOutboundTags = currentTags
                    lastRunProfileId = currentProfileId

                    val coreMode = VpnStateStore.getMode()

                    if (tagsChanged && remoteRunning) {
                        Log.i(TAG, "Sending PREPARE_RESTART before VPN restart")
                        if (!VpnStateStore.shouldTriggerPrepareRestart(1500L)) {
                            Log.d(TAG, "PREPARE_RESTART suppressed (sender throttle)")
                        } else {
                            val prepareIntent = if (coreMode == VpnStateStore.CoreMode.PROXY) {
                                Intent(context, ProxyOnlyService::class.java).apply {
                                    action = ProxyOnlyService.ACTION_PREPARE_RESTART
                                    putExtra(
                                        com.kunk.singbox.service.SingBoxService.EXTRA_PREPARE_RESTART_REASON,
                                        "ConfigRepository:switchNode"
                                    )
                                }
                            } else {
                                Intent(context, SingBoxService::class.java).apply {
                                    action = SingBoxService.ACTION_PREPARE_RESTART
                                    putExtra(
                                        com.kunk.singbox.service.SingBoxService.EXTRA_PREPARE_RESTART_REASON,
                                        "ConfigRepository:switchNode"
                                    )
                                }
                            }
                            context.startService(prepareIntent)
                        }
                        delay(200)
                    }

                    val intent = if (coreMode == VpnStateStore.CoreMode.PROXY) {
                        Intent(context, ProxyOnlyService::class.java).apply {
                            if (tagsChanged) {
                                action = ProxyOnlyService.ACTION_START
                                Log.i(TAG, "Outbound tags changed (or first run), forcing RESTART/RELOAD")
                            } else {
                                action = ProxyOnlyService.ACTION_SWITCH_NODE
                                Log.i(TAG, "Outbound tags match, attempting HOT SWITCH")
                            }
                            putExtra("node_id", nodeId)
                            putExtra("outbound_tag", generationResult.activeNodeTag)
                            putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, generationResult.path)
                        }
                    } else {
                        Intent(context, SingBoxService::class.java).apply {
                            if (tagsChanged) {
                                action = SingBoxService.ACTION_START
                                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                                Log.i(
                                    TAG,
                                    "Outbound tags changed (or first run), " +
                                        "forcing RESTART/RELOAD with CACHE CLEAN"
                                )
                            } else {
                                action = SingBoxService.ACTION_SWITCH_NODE
                                Log.i(TAG, "Outbound tags match, attempting HOT SWITCH")
                            }
                            putExtra("node_id", nodeId)
                            putExtra("outbound_tag", generationResult.activeNodeTag)
                            putExtra(SingBoxService.EXTRA_CONFIG_PATH, generationResult.path)
                        }
                    }

                    // Service already running (VPN active). Use startService to avoid foreground-service timing constraints.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && tagsChanged) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }

                    Log.i(TAG, "Requested switch for node: ${node.name} (Tag: ${generationResult.activeNodeTag}, Restart: $tagsChanged)")
                    NodeSwitchResult.Success
                } catch (e: Exception) {

                    val msg = "Switch error: ${e.message ?: "unknown error"}"
                    Log.e(TAG, "Error during hot switch", e)
                    NodeSwitchResult.Failed(msg)
                }
            }
        } finally {
            nodeSwitchInFlight.set(false)
        }
    }

    suspend fun syncActiveNodeFromProxySelection(proxyName: String?): Boolean {
        if (proxyName.isNullOrBlank()) return false

        val activeProfileId = _activeProfileId.value ?: return false
        val candidates = _nodes.value
        val matched = candidates.firstOrNull { it.name == proxyName } ?: return false
        if (matched.sourceProfileId != activeProfileId) return false
        if (_activeNodeId.value == matched.id) return true

        _activeNodeId.value = matched.id
        Log.i(TAG, "Synced active node from service selection: $proxyName -> ${matched.id}")
        return true
    }

    fun deleteProfile(profileId: String) {
        com.kunk.singbox.service.SubscriptionAutoUpdateWorker.cancel(context, profileId)

        _profiles.update { list -> list.filter { it.id != profileId } }
        removeCachedConfig(profileId)
        profileNodes.remove(profileId)
        updateAllNodesAndGroups()
        val configFile = File(configDir, "$profileId.json")
        if (configFile.exists() && !configFile.delete()) {
            Log.w(TAG, "Failed to delete profile config file: ${configFile.absolutePath}")
        }
        scope.launch {
            try {
                profileDao.deleteById(profileId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete profile from Room", e)
            }
        }

        if (_activeProfileId.value == profileId) {
            val newActiveId = _profiles.value.firstOrNull()?.id
            _activeProfileId.value = newActiveId
            if (newActiveId != null) {
                setActiveProfile(newActiveId)
            } else {
                _nodes.value = emptyList()
                _activeNodeId.value = null
            }
        }
        saveProfiles()
    }

    suspend fun importProfileDirectly(profile: ProfileUi, config: SingBoxConfig) {
        val deduplicatedConfig = deduplicateTags(config)
        val sortOrder = (profileDao.getMaxSortOrder() ?: -1) + 1
        val entity = ProfileEntity.fromUiModel(profile, sortOrder = sortOrder)

        profileDao.insert(entity)
        cacheConfig(profile.id, deduplicatedConfig)
        val nodes = extractNodesFromConfigSync(deduplicatedConfig, profile.id)
        profileNodes[profile.id] = nodes
        _profiles.update { list ->
            val filtered = list.filter { it.id != profile.id }
            filtered + profile
        }
        updateAllNodesAndGroups()
        if (_activeProfileId.value == null) {
            setActiveProfile(profile.id)
        }
    }

    fun toggleProfileEnabled(profileId: String) {
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(enabled = !it.enabled) else it
            }
        }
        saveProfiles()
    }

    fun updateProfileMetadata(
        profileId: String,
        newName: String,
        newUrl: String?,
        autoUpdateInterval: Int = 0,
        dnsPreResolve: Boolean = false,
        dnsServer: String? = null
    ) {
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) {
                    it.copy(
                        name = newName,
                        url = newUrl,
                        autoUpdateInterval = autoUpdateInterval,
                        dnsPreResolve = dnsPreResolve,
                        dnsServer = dnsServer
                    )
                } else {
                    it
                }
            }
        }
        saveProfiles()
        com.kunk.singbox.service.SubscriptionAutoUpdateWorker.schedule(context, profileId, autoUpdateInterval)
    }

    suspend fun testNodeLatency(nodeId: String): Long {
        val existing = inFlightLatencyTests[nodeId]
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<Long>()
        val prev = inFlightLatencyTests.putIfAbsent(nodeId, deferred)
        if (prev != null) {
            return prev.await()
        }

        try {
            val result = withContext(Dispatchers.IO) {
                run {
                    try {
                        val node = _nodes.value.find { it.id == nodeId }
                            ?: _allNodes.value.find { it.id == nodeId }
                        if (node == null) {
                            Log.e(TAG, "Node not found: $nodeId")
                            return@withContext -1L
                        }

                        val config = loadConfig(node.sourceProfileId)
                        if (config == null) {
                            Log.e(TAG, "Config not found for profile: ${node.sourceProfileId}")
                            return@withContext -1L
                        }

                        val outbound = config.outbounds?.find { it.tag == node.name }
                        if (outbound == null) {
                            Log.e(TAG, "Outbound not found: ${node.name}")
                            return@withContext -1L
                        }

                        val fixedOutbound = buildOutboundForRuntime(outbound)
                        val allOutbounds = config.outbounds.map { buildOutboundForRuntime(it) }
                        val probeOutbound = if (VpnStateStore.getActive()) {
                            fixedOutbound
                        } else {
                            prepareOfflineProbeOutbound(fixedOutbound)
                        }
                        val latency = if (VpnStateStore.getActive()) {
                            testNodeLatencyViaRunningService(fixedOutbound.tag)
                        } else {
                            singBoxCore.testOutboundLatency(probeOutbound, allOutbounds)
                        }
                        val finalLatency = if (latency > 0) {
                            latency
                        } else {
                            val fallback = ipv6TcpLatencyFallback(probeOutbound)
                            if (fallback > 0) {
                                fallback
                            } else {
                                resolveIpv6OnlyStatus(probeOutbound, latency)
                            }
                        }

                        _nodes.update { list ->
                            list.map {
                                if (it.id == nodeId) {
                                    it.copy(latencyMs = normalizeLatencyValue(finalLatency))
                                } else {
                                    it
                                }
                            }
                        }

                        profileNodes[node.sourceProfileId] = profileNodes[node.sourceProfileId]?.map {
                            if (it.id == nodeId) {
                                it.copy(latencyMs = normalizeLatencyValue(finalLatency))
                            } else {
                                it
                            }
                        } ?: emptyList()
                        updateLatencyInAllNodes(nodeId, finalLatency)
                        saveProfiles()

                        finalLatency
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) {
                            throw e
                        }
                        Log.e(TAG, "Latency test error for $nodeId", e)
                        val nodeName = _nodes.value.find { it.id == nodeId }?.name
                            ?: _allNodes.value.find { it.id == nodeId }?.name
                        LogRepository.getInstance().addLog(context.getString(R.string.nodes_test_failed, nodeName ?: nodeId) + ": ${e.message}")
                        -1L
                    }
                }
            }
            deferred.complete(result)
            return result
        } catch (e: Exception) {
            deferred.complete(-1L)
            return -1L
        } finally {
            inFlightLatencyTests.remove(nodeId, deferred)
        }
    }

    suspend fun clearAllNodesLatency() = withContext(Dispatchers.IO) {
        savedNodeLatencies.clear()

        _nodes.update { list ->
            list.map { it.copy(latencyMs = null) }
        }

        // Update profileNodes map
        profileNodes.keys.forEach { profileId ->
            profileNodes[profileId] = profileNodes[profileId]?.map {
                it.copy(latencyMs = null)
            } ?: emptyList()
        }
        _allNodes.update { list ->
            list.map { it.copy(latencyMs = null) }
        }
    }

    suspend fun testAllNodesLatency(
        targetNodeIds: List<String>? = null,
        useAllNodes: Boolean = false,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val sourceNodes = if (useAllNodes) _allNodes.value else _nodes.value
        val nodes = if (targetNodeIds != null) {
            sourceNodes.filter { it.id in targetNodeIds }
        } else {
            sourceNodes
        }

        val testInfoList = buildNodeTestInfos(nodes)

        if (testInfoList.isEmpty()) {
            Log.w(TAG, "No valid nodes to test")
            return@withContext
        }

        val (tcpFallbackInfos, regularInfos) = testInfoList.partition {
            LatencyProbePolicy.shouldUseTcpFallback(it.outbound)
        }

        val settings = settingsRepository.settings.first()
        val concurrency = settings.latencyTestConcurrency.coerceIn(1, 20)

        coroutineScope {
            val regularJob = async {
                testRegularOutboundsLatency(regularInfos, concurrency, onNodeComplete)
            }
            val tcpFallbackJob = async {
                testTcpFallbackOutboundsLatency(tcpFallbackInfos, concurrency, onNodeComplete)
            }

            regularJob.await()
            tcpFallbackJob.await()
        }

        saveProfiles()
    }

    suspend fun updateAllProfiles(): BatchUpdateResult = withContext(Dispatchers.IO) {
        val enabledProfiles = _profiles.value.filter { it.enabled && it.type == ProfileType.Subscription }

        if (enabledProfiles.isEmpty()) {
            return@withContext BatchUpdateResult()
        }
        val semaphore = Semaphore(3)
        val results = enabledProfiles.map { profile ->
            async {
                semaphore.withPermit {
                    updateProfile(profile.id)
                }
            }
        }.awaitAll()

        BatchUpdateResult(
            successWithChanges = results.count { it is SubscriptionUpdateResult.SuccessWithChanges },
            successNoChanges = results.count { it is SubscriptionUpdateResult.SuccessNoChanges },
            failed = results.count { it is SubscriptionUpdateResult.Failed },
            details = results
        )
    }

    suspend fun updateProfile(profileId: String): SubscriptionUpdateResult {
        val profile = _profiles.value.find { it.id == profileId }
            ?: return SubscriptionUpdateResult.Failed("Unknown Profile", "Profile not found")

        if (profile.url.isNullOrBlank()) {
            return SubscriptionUpdateResult.Failed(profile.name, "Subscription URL is empty")
        }

        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Updating) else it
            }
        }

        val result = try {
            importFromSubscriptionUpdate(profile)
        } catch (e: Exception) {
            SubscriptionUpdateResult.Failed(profile.name, e.message ?: "Subscription update failed")
        }
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(
                    updateStatus = if (result is SubscriptionUpdateResult.Failed) UpdateStatus.Failed else UpdateStatus.Success,
                    lastUpdated = if (result is SubscriptionUpdateResult.Failed) it.lastUpdated else System.currentTimeMillis()
                ) else it
            }
        }
        profileResetJobs.remove(profileId)?.cancel()
        val resetJob = scope.launch {
            kotlinx.coroutines.delay(2000)
            _profiles.update { list ->
                list.map {
                    if (it.id == profileId && it.updateStatus != UpdateStatus.Updating) {
                        it.copy(updateStatus = UpdateStatus.Idle)
                    } else {
                        it
                    }
                }
            }
        }
        resetJob.invokeOnCompletion {
            profileResetJobs.remove(profileId, resetJob)
        }
        profileResetJobs[profileId] = resetJob

        return result
    }

    private suspend fun importFromSubscriptionUpdate(profile: ProfileUi): SubscriptionUpdateResult = withContext(Dispatchers.IO) {
        try {
            val oldNodes = profileNodes[profile.id] ?: emptyList()
            val oldNodeNames = oldNodes.map { it.name }.toSet()
            val profileUrl = profile.url
            if (profileUrl.isNullOrBlank()) {
                return@withContext SubscriptionUpdateResult.Failed(profile.name, "Subscription URL is empty")
            }

            val fetchResult = fetchAndParseSubscription(profileUrl) { }
                ?: return@withContext SubscriptionUpdateResult.Failed(profile.name, "Failed to fetch subscription")

            val config = fetchResult.config
            val userInfo = fetchResult.userInfo

            val deduplicatedConfig = deduplicateTags(config)
            val newNodes = extractNodesFromConfig(deduplicatedConfig, profile.id)
            val newNodeNames = newNodes.map { it.name }.toSet()
            val addedNodes = newNodeNames - oldNodeNames
            val removedNodes = oldNodeNames - newNodeNames
            writeConfigFileOrThrow(profile.id, deduplicatedConfig)

            cacheConfig(profile.id, deduplicatedConfig)
            profileNodes[profile.id] = newNodes
            updateAllNodesAndGroups()
            if (_activeProfileId.value == profile.id) {
                _nodes.value = newNodes
            }
            _profiles.update { list ->
                list.map {
                    if (it.id == profile.id) {
                        it.copy(
                            expireDate = userInfo?.expire ?: it.expireDate,
                            totalTraffic = userInfo?.total ?: it.totalTraffic,
                            usedTraffic = if (userInfo != null) (userInfo.upload + userInfo.download) else it.usedTraffic
                        )
                    } else {
                        it
                    }
                }
            }

            saveProfiles()
            if (profile.dnsPreResolve) {
                preResolveDomainsForProfileBestEffort(profile.id, deduplicatedConfig, profile.dnsServer)
            }
            if (addedNodes.isNotEmpty() || removedNodes.isNotEmpty()) {
                SubscriptionUpdateResult.SuccessWithChanges(
                    profileName = profile.name,
                    addedCount = addedNodes.size,
                    removedCount = removedNodes.size,
                    totalCount = newNodes.size
                )
            } else {
                SubscriptionUpdateResult.SuccessNoChanges(
                    profileName = profile.name,
                    totalCount = newNodes.size
                )
            }
        } catch (e: Exception) {
            SubscriptionUpdateResult.Failed(profile.name, e.message ?: "Subscription update failed")
        }
    }

    data class ConfigGenerationResult(
        val path: String,
        val activeNodeTag: String?,
        val outboundTags: Set<String>
    )

    suspend fun generateConfigFile(): ConfigGenerationResult? = withContext(Dispatchers.IO) {
        try {
            val activeId = _activeProfileId.value
                ?: activeStateDao.getSync()?.activeProfileId
                ?: return@withContext null
            val config = loadConfig(activeId) ?: return@withContext null
            val activeProfile = _profiles.value.find { it.id == activeId }
            val activeNodeId = _activeNodeId.value
                ?: activeStateDao.getSync()?.activeNodeId

            val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()
            val activeNode = _nodes.value.find { it.id == activeNodeId }
                ?: allNodesSnapshot.find { it.id == activeNodeId }
            val settings = settingsRepository.settings.first()
            val log = buildRunLogConfig()
            val experimental = buildRunExperimentalConfig(settings)
            val inbounds = buildRunInbounds(settings)
            val customRuleSets = buildCustomRuleSets(settings)

            val dns = buildRunDns(settings, customRuleSets)

            val outboundsContext = buildRunOutbounds(
                config, activeNode, settings, allNodesSnapshot,
                activeProfile?.dnsPreResolve ?: false, activeId
            )
            val route =
                buildRunRoute(settings, outboundsContext.selectorTag, outboundsContext.outbounds, outboundsContext.nodeTagResolver, customRuleSets)

            lastTagToNodeName = outboundsContext.nodeTagMap.mapNotNull { (nodeId, tag) ->
                val name = allNodesSnapshot.firstOrNull { it.id == nodeId }?.name
                if (name.isNullOrBlank() || tag.isBlank()) null else (tag to name)
            }.toMap()

            val runConfig = config.copy(
                log = log,
                experimental = experimental,
                inbounds = inbounds,
                dns = dns,
                route = route,
                outbounds = outboundsContext.outbounds
            )

            val validation = singBoxCore.validateConfig(runConfig)
            validation.exceptionOrNull()?.let { e ->
                val msg = e.cause?.message ?: e.message ?: "unknown error"
                Log.e(TAG, "Config pre-validation failed: $msg", e)
                throw Exception("Config validation failed: $msg", e)
            }
            val allTags = runConfig.outbounds?.map { it.tag }?.toSet() ?: emptySet()
            val candidateTag = activeNodeId?.let { outboundsContext.nodeTagMap[it] }
                ?: activeNode?.name

            val resolvedTag = when {
                candidateTag == null -> {
                    val proxySelector = runConfig.outbounds?.find { it.tag == "PROXY" }
                    proxySelector?.default ?: proxySelector?.outbounds?.firstOrNull()
                }
                allTags.contains(candidateTag) -> candidateTag
                else -> {
                    Log.e(TAG, "Selected node tag '$candidateTag' not found in runtime outbounds, aborting switch")
                    throw IllegalStateException("Selected node is not available in runtime outbounds: $candidateTag")
                }
            }
            val configFile = File(context.filesDir, "running_config.json")
            configFile.writeText(gson.toJson(runConfig))

            ConfigGenerationResult(configFile.absolutePath, resolvedTag, allTags)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate config file", e)
            null
        }
    }
    private fun buildOutboundForRuntime(outbound: Outbound): Outbound = OutboundFixer.buildForRuntime(context, outbound)

    private suspend fun preResolveDomainsForProfile(
        profileId: String,
        config: SingBoxConfig,
        dnsServer: String?
    ) {
        val outbounds = config.outbounds ?: return
        val domains = outbounds.mapNotNull { outbound ->
            val server = outbound.server ?: return@mapNotNull null
            if (DnsResolver.isIpAddress(server)) return@mapNotNull null
            server
        }.distinct()

        if (domains.isEmpty()) {
            Log.d(TAG, "No domains to pre-resolve for profile $profileId")
            return
        }

        Log.d(TAG, "Pre-resolving ${domains.size} domains for profile $profileId")

        val results = dnsResolver.resolveBatch(
            domains = domains,
            dohServer = dnsServer ?: DnsResolver.DOH_CLOUDFLARE
        )

        val savedCount = dnsResolveStore.saveBatch(profileId, results)
        Log.d(TAG, "Pre-resolved and saved $savedCount domains for profile $profileId")
    }

    private fun applyDnsResolveToOutbound(profileId: String, outbound: Outbound): Outbound {
        val server = outbound.server ?: return outbound
        if (DnsResolver.isIpAddress(server)) return outbound

        val resolvedIp = dnsResolveStore.getIp(profileId, server)
        return if (resolvedIp != null) {
            Log.d(TAG, "Applying DNS resolve: $server -> $resolvedIp")
            outbound.copy(server = resolvedIp)
        } else {
            outbound
        }
    }

    private fun buildCustomRuleSets(settings: AppSettings): List<RuleSetConfig> {
        val ruleSetRepo = RuleSetRepository.getInstance(context)

        val rules = settings.ruleSets.map { ruleSet ->
            if (ruleSet.type == RuleSetType.REMOTE) {
                val localPath = ruleSetRepo.getRuleSetPath(ruleSet.tag)
                val file = File(localPath)
                if (file.exists() && file.length() > 0) {
                    if (file.length() < 10) {
                        Log.w(TAG, "Rule set file too small, ignoring: ${ruleSet.tag} (${file.length()} bytes)")
                        return@map null
                    }
                    try {
                        val header = file.inputStream().use { input ->
                            val buffer = ByteArray(64)
                            val read = input.read(buffer)
                            if (read > 0) String(buffer, 0, read) else ""
                        }
                        val trimmedHeader = header.trim()
                        if (trimmedHeader.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                            trimmedHeader.startsWith("<html", ignoreCase = true) ||
                            trimmedHeader.startsWith("{")) {
                            Log.e(TAG, "Rule set file appears to be invalid (HTML/JSON), ignoring: ${ruleSet.tag}")
                            file.delete()
                            return@map null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to inspect rule set file header: ${ruleSet.tag}", e)
                    }

                    RuleSetConfig(
                        tag = ruleSet.tag,
                        type = "local",
                        format = ruleSet.format,
                        path = localPath
                    )
                } else {
                    Log.w(TAG, "Rule set file not found or empty: ${ruleSet.tag} ($localPath)")
                    null
                }
            } else {
                val file = File(ruleSet.path)
                if (file.exists() && file.length() > 0) {
                    RuleSetConfig(
                        tag = ruleSet.tag,
                        type = "local",
                        format = ruleSet.format,
                        path = ruleSet.path
                    )
                } else {
                    Log.w(TAG, "Local rule set file not found: ${ruleSet.tag} (${ruleSet.path})")
                    null
                }
            }
        }.filterNotNull().toMutableList()

        return rules
    }

    private fun buildCustomDomainRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        fun splitValues(raw: String): List<String> {
            return raw
                .split("\n", "\r", ",", ";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        fun resolveOutboundTag(mode: RuleSetOutboundMode?, value: String?): String {
            return when (mode ?: RuleSetOutboundMode.PROXY) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(value)
                    if (resolvedTag != null) resolvedTag else defaultProxyTag
                }
                RuleSetOutboundMode.PROFILE -> {
                    val profileId = value
                    val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                    val tag = "P:$profileName"
                    if (outbounds.any { it.tag == tag }) tag else defaultProxyTag
                }
            }
        }

        val rules = settings.customRules
            .filter { it.enabled }
            .filter {
                it.type == RuleType.DOMAIN ||
                    it.type == RuleType.DOMAIN_SUFFIX ||
                    it.type == RuleType.DOMAIN_KEYWORD
            }
            .mapNotNull { rule ->
                val values = splitValues(rule.value)
                if (values.isEmpty()) return@mapNotNull null

                val mode = rule.outboundMode ?: when (rule.outbound) {
                    OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
                    OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
                    OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
                }

                val outbound = resolveOutboundTag(mode, rule.outboundValue)
                Log.d(TAG, "CustomDomainRule: type=${rule.type}, value=${rule.value}, mode=$mode, outboundValue=${rule.outboundValue}, resolved=$outbound")
                when (rule.type) {
                    RuleType.DOMAIN -> RouteRule(domain = values, outbound = outbound)
                    RuleType.DOMAIN_SUFFIX -> RouteRule(domainSuffix = values, outbound = outbound)
                    RuleType.DOMAIN_KEYWORD -> RouteRule(domainKeyword = values, outbound = outbound)
                    else -> null
                }
            }
        return rules
    }

    private fun buildCustomRuleSetRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
        val sortedRuleSets = settings.ruleSets.filter { it.enabled && it.tag in validTags }.sortedWith(
            compareBy(
                { ruleSet ->
                    when {
                        ruleSet.tag.contains("geolocation-!cn") -> 200
                        ruleSet.tag.contains("geolocation-cn") -> 199
                        ruleSet.tag.contains("!cn") -> 198
                        ruleSet.tag.matches(Regex("^geo(site|ip)-[a-z]{2}$")) -> 100
                        else -> 0
                    }
                },
                { ruleSet ->
                    when (ruleSet.outboundMode) {
                        RuleSetOutboundMode.NODE -> 0
                        RuleSetOutboundMode.PROXY -> 1
                        RuleSetOutboundMode.DIRECT -> 2
                        RuleSetOutboundMode.BLOCK -> 3
                        RuleSetOutboundMode.PROFILE -> 1
                        null -> 4
                    }
                }
            )
        )

        sortedRuleSets.forEach { ruleSet ->

            val outboundTag = when (ruleSet.outboundMode ?: RuleSetOutboundMode.DIRECT) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(ruleSet.outboundValue)
                    if (resolvedTag != null) {
                        resolvedTag
                    } else {
                        Log.w(TAG, "Node ID '${ruleSet.outboundValue}' not resolved to any tag, falling back to $defaultProxyTag")
                        defaultProxyTag
                    }
                }
                RuleSetOutboundMode.PROFILE -> {
                    val profileId = ruleSet.outboundValue
                    val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                    val tag = "P:$profileName"
                    if (outbounds.any { it.tag == tag }) {
                        tag
                    } else {
                        defaultProxyTag
                    }
                }
            }
            val inboundTags = if (ruleSet.inbounds.isNullOrEmpty()) {
                null
            } else {
                ruleSet.inbounds.map {
                    when (it) {
                        "tun" -> "tun-in"
                        "mixed" -> "mixed-in"
                        else -> it
                    }
                }
            }

            rules.add(RouteRule(
                ruleSet = listOf(ruleSet.tag),
                outbound = outboundTag,
                inbound = inboundTags
            ))
        }

        return rules
    }

    private fun buildAppRoutingRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        fun resolveUidByPackageName(pkg: String): Int {
            return try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                info.uid
            } catch (_: Exception) {
                0
            }
        }

        fun resolveOutboundTag(mode: RuleSetOutboundMode?, value: String?): String {
            return when (mode ?: RuleSetOutboundMode.DIRECT) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(value)
                    if (resolvedTag != null) resolvedTag else defaultProxyTag
                }
                RuleSetOutboundMode.PROFILE -> {
                    val profileId = value
                    val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                    val tag = "P:$profileName"
                    if (outbounds.any { it.tag == tag }) tag else defaultProxyTag
                }
            }
        }
        settings.appRules.filter { it.enabled }.forEach { rule ->
            val outboundTag = resolveOutboundTag(rule.outboundMode, rule.outboundValue)

            val uid = resolveUidByPackageName(rule.packageName)
            if (uid > 0) {
                rules.add(
                    RouteRule(
                        userId = listOf(uid),
                        outbound = outboundTag
                    )
                )
            }

            rules.add(
                RouteRule(
                    packageName = listOf(rule.packageName),
                    outbound = outboundTag
                )
            )
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            val outboundTag = resolveOutboundTag(group.outboundMode, group.outboundValue)
            val packageNames = group.apps.map { it.packageName }
            if (packageNames.isNotEmpty()) {
                val uids = packageNames.map { resolveUidByPackageName(it) }.filter { it > 0 }.distinct()
                if (uids.isNotEmpty()) {
                    rules.add(
                        RouteRule(
                            userId = uids,
                            outbound = outboundTag
                        )
                    )
                }

                rules.add(
                    RouteRule(
                        packageName = packageNames,
                        outbound = outboundTag
                    )
                )
            }
        }

        return rules
    }

    private fun buildRunLogConfig(): LogConfig {
        return LogConfig(
            level = "warn",
            timestamp = true
        )
    }

    private fun buildRunExperimentalConfig(settings: AppSettings): ExperimentalConfig {
        val singboxDataDir = File(context.filesDir, "singbox_data").also { it.mkdirs() }

        val clashApiPort = findAvailablePort(9090)
        val clashApi = ClashApiConfig(
            externalController = "127.0.0.1:$clashApiPort",
            defaultMode = "rule"
        )

        return ExperimentalConfig(
            cacheFile = CacheFileConfig(
                enabled = true,
                path = File(singboxDataDir, "cache.db").absolutePath,
                storeFakeip = settings.fakeDnsEnabled
            ),
            clashApi = clashApi
        )
    }

    private fun buildRunInbounds(settings: AppSettings): List<Inbound> =
        InboundBuilder.build(
            settings.copy(tunMtu = getEffectiveTunMtu(settings)),
            getEffectiveTunStack(settings.tunStack)
        )

    private fun buildRunDns(settings: AppSettings, validRuleSets: List<RuleSetConfig>): DnsConfig {
        val dnsServers = mutableListOf<DnsServer>()
        val dnsRules = mutableListOf<DnsRule>()

        val proxyServerTag = if (settings.fakeDnsEnabled) "fakeip-dns" else "remote"
        val proxyFinalServerTag = "remote"
        val directServerTag = "local"

        fun dnsRouteTo(server: String, rule: DnsRule): DnsRule =
            rule.copy(action = "route", server = server)

        fun dnsReject(rule: DnsRule): DnsRule = rule.copy(action = "reject", method = "default")

        val fakeipQueryTypes = listOf("A", "AAAA")

        fun dnsRouteToProxy(rule: DnsRule): List<DnsRule> {
            if (!settings.fakeDnsEnabled) {
                return listOf(dnsRouteTo(proxyServerTag, rule))
            }
            return listOf(
                dnsRouteTo("fakeip-dns", rule.copy(queryType = fakeipQueryTypes))
            )
        }

        fun outboundModeOf(
            ruleOutboundMode: RuleSetOutboundMode?,
            fallbackOutbound: OutboundTag?
        ): RuleSetOutboundMode {
            return ruleOutboundMode
                ?: when (fallbackOutbound) {
                    OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
                    OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
                    OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
                    null -> RuleSetOutboundMode.PROXY
                }
        }
        if (settings.blockQuic) {
            dnsRules.add(dnsReject(DnsRule(queryType = listOf("HTTPS", "SVCB"))))
        }
        val bootstrapStrategy = when (settings.serverAddressStrategy) {
            DnsStrategy.AUTO -> "prefer_ipv4"
            else -> mapDnsStrategy(settings.serverAddressStrategy) ?: "prefer_ipv4"
        }
        val bootstrapV4Tag = "dns-bootstrap-v4"
        val bootstrapV6Tag = "dns-bootstrap-v6"

        dnsServers.add(
            DnsServer(
                tag = bootstrapV4Tag,
                address = "223.5.5.5",
                detour = "direct",
                strategy = bootstrapStrategy
            )
        )
        dnsServers.add(
            DnsServer(
                tag = bootstrapV6Tag,
                address = "https://[2606:4700:4700::1111]/dns-query",
                detour = "direct",
                strategy = "prefer_ipv6"
            )
        )
        dnsServers.add(
            DnsServer(
                tag = "dns-bootstrap",
                address = "119.29.29.29",
                detour = "direct",
                strategy = bootstrapStrategy
            )
        )

        val localDnsAddr = settings.localDns.takeIf { it.isNotBlank() } ?: "https://dns.alidns.com/dns-query"
        val localResolver = if (localDnsAddr == "local" || isIpAddress(localDnsAddr)) null else "dns-bootstrap"
        dnsServers.add(
            DnsServer(
                tag = "local",
                address = localDnsAddr,
                detour = "direct",
                strategy = mapDnsStrategy(settings.directDnsStrategy),
                addressResolver = localResolver
            )
        )
        val remoteDnsAddr = settings.remoteDns.takeIf { it.isNotBlank() } ?: "https://dns.google/dns-query"
        val remoteResolver = if (remoteDnsAddr == "local" || isIpAddress(extractHost(remoteDnsAddr))) {
            null
        } else {
            "dns-bootstrap"
        }
        dnsServers.add(
            DnsServer(
                tag = "remote",
                address = remoteDnsAddr,
                detour = "PROXY",
                strategy = mapDnsStrategy(settings.remoteDnsStrategy),
                addressResolver = remoteResolver
            )
        )

        dnsRules.addAll(
            buildBootstrapDnsRules(
                serverAddresses = listOfNotNull(
                    localDnsAddr.takeIf { localResolver != null },
                    remoteDnsAddr.takeIf { remoteResolver != null }
                ),
                bootstrapV4Tag = bootstrapV4Tag,
                bootstrapV6Tag = bootstrapV6Tag,
                bootstrapTag = "dns-bootstrap"
            )
        )

        if (settings.fakeDnsEnabled) {
            dnsServers.add(
                DnsServer(
                    tag = "fakeip-dns",
                    address = "fakeip"
                )
            )
        }
        val customDomainRulesForDns = settings.customRules
            .filter { it.enabled }
            .filter {
                it.type == RuleType.DOMAIN ||
                    it.type == RuleType.DOMAIN_SUFFIX ||
                    it.type == RuleType.DOMAIN_KEYWORD
            }

        if (customDomainRulesForDns.isNotEmpty()) {
            val proxyDomains = mutableListOf<String>()
            val proxySuffixes = mutableListOf<String>()
            val proxyKeywords = mutableListOf<String>()
            val directDomains = mutableListOf<String>()
            val directSuffixes = mutableListOf<String>()
            val directKeywords = mutableListOf<String>()
            val blockDomains = mutableListOf<String>()
            val blockSuffixes = mutableListOf<String>()
            val blockKeywords = mutableListOf<String>()

            customDomainRulesForDns.forEach { rule ->
                val values = rule.value
                    .split("\n", "\r", ",", ";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val mode = outboundModeOf(rule.outboundMode, rule.outbound)
                when (mode) {
                    RuleSetOutboundMode.DIRECT -> when (rule.type) {
                        RuleType.DOMAIN -> directDomains.addAll(values)
                        RuleType.DOMAIN_SUFFIX -> directSuffixes.addAll(values)
                        RuleType.DOMAIN_KEYWORD -> directKeywords.addAll(values)
                        else -> {}
                    }

                    RuleSetOutboundMode.BLOCK -> when (rule.type) {
                        RuleType.DOMAIN -> blockDomains.addAll(values)
                        RuleType.DOMAIN_SUFFIX -> blockSuffixes.addAll(values)
                        RuleType.DOMAIN_KEYWORD -> blockKeywords.addAll(values)
                        else -> {}
                    }

                    RuleSetOutboundMode.PROXY,
                    RuleSetOutboundMode.NODE,
                    RuleSetOutboundMode.PROFILE -> when (rule.type) {
                        RuleType.DOMAIN -> proxyDomains.addAll(values)
                        RuleType.DOMAIN_SUFFIX -> proxySuffixes.addAll(values)
                        RuleType.DOMAIN_KEYWORD -> proxyKeywords.addAll(values)
                        else -> {}
                    }
                }
            }

            // BLOCK: reject DNS queries (method=default)
            if (blockDomains.isNotEmpty()) {
                dnsRules.add(dnsReject(DnsRule(domain = blockDomains.distinct())))
            }
            if (blockSuffixes.isNotEmpty()) {
                dnsRules.add(dnsReject(DnsRule(domainSuffix = blockSuffixes.distinct())))
            }
            if (blockKeywords.isNotEmpty()) {
                dnsRules.add(dnsReject(DnsRule(domainKeyword = blockKeywords.distinct())))
            }

            if (proxyDomains.isNotEmpty()) {
                dnsRules.addAll(dnsRouteToProxy(DnsRule(domain = proxyDomains.distinct())))
            }
            if (proxySuffixes.isNotEmpty()) {
                dnsRules.addAll(dnsRouteToProxy(DnsRule(domainSuffix = proxySuffixes.distinct())))
            }
            if (proxyKeywords.isNotEmpty()) {
                dnsRules.addAll(dnsRouteToProxy(DnsRule(domainKeyword = proxyKeywords.distinct())))
            }

            if (directDomains.isNotEmpty()) {
                dnsRules.add(
                    dnsRouteTo(directServerTag, DnsRule(domain = directDomains.distinct()))
                )
            }
            if (directSuffixes.isNotEmpty()) {
                dnsRules.add(
                    dnsRouteTo(directServerTag, DnsRule(domainSuffix = directSuffixes.distinct()))
                )
            }
            if (directKeywords.isNotEmpty()) {
                dnsRules.add(
                    dnsRouteTo(directServerTag, DnsRule(domainKeyword = directKeywords.distinct()))
                )
            }
        }
        val validRuleSetTags = validRuleSets.mapNotNull { it.tag }.toSet()
        val proxyRuleSetTags = mutableListOf<String>()
        val directRuleSetTags = mutableListOf<String>()
        val blockRuleSetTags = mutableListOf<String>()

        settings.ruleSets
            .filter { it.enabled }
            .forEach { ruleSet ->
                // Only apply DNS mapping for rule sets that are actually available in runtime config.
                val tag = ruleSet.tag
                if (tag.isBlank() || tag !in validRuleSetTags) return@forEach

                val mode = ruleSet.outboundMode ?: RuleSetOutboundMode.DIRECT
                when (mode) {
                    RuleSetOutboundMode.DIRECT -> directRuleSetTags.add(tag)
                    RuleSetOutboundMode.BLOCK -> blockRuleSetTags.add(tag)
                    RuleSetOutboundMode.PROXY,
                    RuleSetOutboundMode.NODE,
                    RuleSetOutboundMode.PROFILE -> proxyRuleSetTags.add(tag)
                }
            }

        if (blockRuleSetTags.isNotEmpty()) {
            dnsRules.add(dnsReject(DnsRule(ruleSet = blockRuleSetTags.distinct())))
        }
        if (proxyRuleSetTags.isNotEmpty()) {
            dnsRules.addAll(dnsRouteToProxy(DnsRule(ruleSet = proxyRuleSetTags.distinct())))
        }
        if (directRuleSetTags.isNotEmpty()) {
            dnsRules.add(
                dnsRouteTo(directServerTag, DnsRule(ruleSet = directRuleSetTags.distinct()))
            )
        }
        val proxyPackages = mutableListOf<String>()
        val directPackages = mutableListOf<String>()
        val blockPackages = mutableListOf<String>()

        fun addPackageByMode(pkg: String, mode: RuleSetOutboundMode) {
            when (mode) {
                RuleSetOutboundMode.DIRECT -> directPackages.add(pkg)
                RuleSetOutboundMode.BLOCK -> blockPackages.add(pkg)
                RuleSetOutboundMode.PROXY,
                RuleSetOutboundMode.NODE,
                RuleSetOutboundMode.PROFILE -> proxyPackages.add(pkg)
            }
        }

        settings.appRules.filter { it.enabled }.forEach { rule ->
            addPackageByMode(rule.packageName, rule.outboundMode ?: RuleSetOutboundMode.PROXY)
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            val mode = group.outboundMode ?: RuleSetOutboundMode.PROXY
            group.apps.forEach { addPackageByMode(it.packageName, mode) }
        }

        // We keep both package_name and user_id matching for robustness.
        // (sing-box docs mark user_id as Linux-only, but some Android clients still accept it via platform integration)
        fun resolveUids(pkgs: List<String>): List<Int> {
            return pkgs.mapNotNull {
                try {
                    context.packageManager.getApplicationInfo(it, 0).uid
                } catch (_: Exception) {
                    null
                }
            }.distinct()
        }

        val directPkgs = directPackages.distinct().filter { it.isNotBlank() }
        val proxyPkgs = proxyPackages.distinct().filter { it.isNotBlank() }
        val blockPkgs = blockPackages.distinct().filter { it.isNotBlank() }

        if (blockPkgs.isNotEmpty()) {
            dnsRules.add(
                dnsReject(DnsRule(packageName = blockPkgs, userId = resolveUids(blockPkgs)))
            )
        }
        if (proxyPkgs.isNotEmpty()) {
            dnsRules.addAll(
                dnsRouteToProxy(DnsRule(packageName = proxyPkgs, userId = resolveUids(proxyPkgs)))
            )
        }
        if (directPkgs.isNotEmpty()) {
            dnsRules.add(
                dnsRouteTo(
                    directServerTag,
                    DnsRule(packageName = directPkgs, userId = resolveUids(directPkgs))
                )
            )
        }
        if (settings.fakeDnsEnabled) {
            val fakeIpExcludeDomains = buildList {
                settings.fakeIpExcludeDomains
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { add(it) }
                val defaultExcludes = listOf(
                    "accounts.google.com",
                    "oauth.googleusercontent.com",
                    "appleid.apple.com",
                    "idmsa.apple.com",
                    "login.microsoftonline.com",
                    "login.live.com",
                    "lan",
                    "local",
                    "localhost",
                    "localdomain",
                    "arpa"
                )
                defaultExcludes.filter { it !in this }.forEach { add(it) }
            }.distinct()

            if (fakeIpExcludeDomains.isNotEmpty()) {
                dnsRules.add(dnsRouteTo("remote", DnsRule(domain = fakeIpExcludeDomains)))
            }
        }
        if (settings.fakeDnsEnabled) {
            dnsRules.add(dnsRouteTo("fakeip-dns", DnsRule(
                queryType = fakeipQueryTypes,
                inbound = listOf("tun-in")
            )))
        }

        val fakeIpConfig = if (settings.fakeDnsEnabled) {
            val fakeIpRanges = settings.fakeIpRange.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val inet4Range = fakeIpRanges.firstOrNull { it.contains(".") } ?: "198.18.0.0/15"
            val inet6Range = fakeIpRanges.firstOrNull { it.contains(":") } ?: "fc00::/18"

            DnsFakeIpConfig(
                enabled = true,
                inet4Range = inet4Range,
                inet6Range = inet6Range
            )
        } else {
            null
        }

        val finalServer = when (settings.routingMode) {
            RoutingMode.GLOBAL_PROXY -> if (settings.fakeDnsEnabled) proxyFinalServerTag else proxyServerTag
            RoutingMode.GLOBAL_DIRECT -> directServerTag
            RoutingMode.RULE -> when (settings.defaultRule) {
                DefaultRule.PROXY -> if (settings.fakeDnsEnabled) proxyFinalServerTag else proxyServerTag
                DefaultRule.DIRECT -> directServerTag
                // If default is block, we still default DNS to proxy side to reduce local DNS leakage.
                DefaultRule.BLOCK -> if (settings.fakeDnsEnabled) proxyFinalServerTag else proxyServerTag
            }
        }

        return DnsConfig(
            servers = dnsServers,
            rules = dnsRules,
            finalServer = finalServer,
            strategy = mapDnsStrategy(settings.dnsStrategy),
            disableCache = !settings.dnsCacheEnabled,
            // Keep cache shared by default to improve hit rate and reduce repeated resolutions.
            // sing-box doc notes independent_cache slightly degrades performance.
            independentCache = false,
            fakeip = fakeIpConfig
        )
    }

    private data class RunOutboundsContext(
        val outbounds: List<Outbound>,
        val selectorTag: String,
        val nodeTagResolver: (String?) -> String?,
        val nodeTagMap: Map<String, String>
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth")
    private fun buildRunOutbounds(
        baseConfig: SingBoxConfig,
        activeNode: NodeUi?,
        settings: AppSettings,
        allNodes: List<NodeUi>,
        dnsPreResolve: Boolean = false,
        profileId: String? = null
    ): RunOutboundsContext {
        val rawOutbounds = baseConfig.outbounds
        if (rawOutbounds.isNullOrEmpty()) {
            Log.w(TAG, "No outbounds found in base config, adding defaults")
        }

        val fixedOutbounds = rawOutbounds?.mapNotNull { outbound ->
            var processed = buildOutboundForRuntime(outbound)
            if (processed.type == "dns") {
                Log.w(TAG, "Skipping deprecated dns outbound: ${processed.tag}")
                return@mapNotNull null
            }
            if (dnsPreResolve && profileId != null) {
                processed = applyDnsResolveToOutbound(profileId, processed)
            }
            if (singBoxCore.validateOutbound(processed)) {
                processed
            } else {
                Log.w(TAG, "Skipping invalid outbound: ${outbound.tag} (type=${outbound.type})")
                null
            }
        }?.toMutableList() ?: mutableListOf()

        if (fixedOutbounds.none { it.tag == "direct" }) {
            fixedOutbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        if (fixedOutbounds.none { it.tag == "block" }) {
            fixedOutbounds.add(Outbound(type = "block", tag = "block"))
        }

        val activeProfileId = _activeProfileId.value
        val requiredNodeIds = mutableSetOf<String>()
        val requiredProfileIds = mutableSetOf<String>()

        fun resolveNodeRefToId(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val parts = value.split("::", limit = 2)
            if (parts.size == 2) {
                val refProfileId = parts[0]
                val nodeName = parts[1]
                return allNodes.firstOrNull { it.sourceProfileId == refProfileId && it.name == nodeName }?.id
            }
            if (allNodes.any { it.id == value }) return value
            val node = if (activeProfileId != null) {
                allNodes.firstOrNull { it.sourceProfileId == activeProfileId && it.name == value }
                    ?: allNodes.firstOrNull { it.name == value }
            } else {
                allNodes.firstOrNull { it.name == value }
            }
            return node?.id
        }
        settings.appRules.filter { it.enabled }.forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            when (group.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(group.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> group.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.ruleSets.filter { it.enabled }.forEach { ruleSet ->
            when (ruleSet.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(ruleSet.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> ruleSet.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.customRules.filter { it.enabled }.forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        fixedOutbounds.mapNotNull { it.detour }.forEach { detourValue ->
            resolveNodeRefToId(detourValue)?.let { requiredNodeIds.add(it) }
        }
        activeNode?.let { requiredNodeIds.add(it.id) }
        requiredProfileIds.forEach { requiredProfileId ->
            allNodes.filter { it.sourceProfileId == requiredProfileId }.forEach { node ->
                requiredNodeIds.add(node.id)
            }
        }
        val nodeTagMap = mutableMapOf<String, String>()
        val existingTags = fixedOutbounds.map { it.tag }.toMutableSet()
        Log.d(TAG, "buildRunOutbounds: activeProfileId=$activeProfileId, existingTags count=${existingTags.size}")
        Log.d(TAG, "  existingTags (first 10): ${existingTags.take(10)}")
        if (activeProfileId != null) {
            val profileNodes = allNodes.filter { it.sourceProfileId == activeProfileId }
            Log.d(TAG, "  profileNodes count=${profileNodes.size}")
            profileNodes.forEach { node ->
                if (existingTags.contains(node.name)) {
                    nodeTagMap[node.id] = node.name
                } else {
                    val fuzzyMatch = existingTags.find { it.equals(node.name, ignoreCase = true) }
                    if (fuzzyMatch != null) {
                        nodeTagMap[node.id] = fuzzyMatch
                        Log.w(TAG, "  Fuzzy matched node '${node.name}' to tag '$fuzzyMatch'")
                    } else {
                        Log.w(TAG, "  WARNING: Node '${node.name}' (id=${node.id.take(8)}) not found in existingTags!")
                    }
                }
            }
        }
        requiredNodeIds.forEach { nodeId ->
            if (nodeTagMap.containsKey(nodeId)) return@forEach

            val node = allNodes.find { it.id == nodeId }
            if (node == null) {
                Log.w(TAG, "Cross-profile node not found in allNodes: nodeId=$nodeId")
                return@forEach
            }
            val sourceProfileId = node.sourceProfileId
            if (sourceProfileId == activeProfileId) {
                Log.w(TAG, "Cross-profile node belongs to activeProfile but not in outbounds: ${node.name}")
                return@forEach
            }
            val sourceConfig = loadConfig(sourceProfileId)
            if (sourceConfig == null) {
                Log.e(TAG, "Failed to load source config for cross-profile node: profileId=$sourceProfileId, nodeName=${node.name}")
                return@forEach
            }
            val sourceOutbound = sourceConfig.outbounds?.find { it.tag == node.name }
                ?: sourceConfig.outbounds?.find { it.tag.equals(node.name, ignoreCase = true) }
                ?: sourceConfig.outbounds?.find {
                    it.tag.replace(REGEX_WHITESPACE_DASH, "").equals(
                        node.name.replace(REGEX_WHITESPACE_DASH, ""),
                        ignoreCase = true
                    )
                }

            if (sourceOutbound == null) {
                Log.e(TAG, "Cross-profile outbound not found: nodeName=${node.name}, profileId=$sourceProfileId, available tags: ${sourceConfig.outbounds?.map { it.tag }?.take(10)}")
                return@forEach
            }
            var fixedSourceOutbound = buildOutboundForRuntime(sourceOutbound)
            var finalTag = fixedSourceOutbound.tag
            if (existingTags.contains(finalTag)) {
                val suffix = sourceProfileId.take(4)
                finalTag = "${finalTag}_$suffix"
                if (existingTags.contains(finalTag)) {
                    finalTag = "${finalTag}_${java.util.UUID.randomUUID().toString().take(4)}"
                }
                fixedSourceOutbound = fixedSourceOutbound.copy(tag = finalTag)
            }
            if (!singBoxCore.validateOutbound(fixedSourceOutbound)) {
                Log.w(TAG, "Skipping invalid cross-profile outbound: ${node.name} (type=${sourceOutbound.type})")
                return@forEach
            }
            fixedOutbounds.add(fixedSourceOutbound)
            existingTags.add(finalTag)
            nodeTagMap[nodeId] = finalTag
        }
        requiredProfileIds.forEach { requiredProfileId ->
            val profileNodes = allNodes.filter { it.sourceProfileId == requiredProfileId }
            val nodeTags = profileNodes.mapNotNull { nodeTagMap[it.id] }
            val profileName = _profiles.value.find { it.id == requiredProfileId }?.name ?: "Profile_$requiredProfileId"
            val tag = "P:$profileName"

            if (nodeTags.isNotEmpty()) {
                val existingIndex = fixedOutbounds.indexOfFirst { it.tag == tag }
                if (existingIndex < 0) {
                    val newSelector = Outbound(
                        type = "selector",
                        tag = tag,
                        outbounds = nodeTags.distinct(),
                        default = nodeTags.firstOrNull(),
                        interruptExistConnections = false
                    )
                    fixedOutbounds.add(0, newSelector)
                }
            }
        }
        val proxyTags = fixedOutbounds.filter {
            it.type in listOf(
                "vless", "vmess", "trojan", "shadowsocks",
                "hysteria2", "hysteria", "anytls", "tuic",
                "wireguard", "ssh", "shadowtls", "http", "socks", "naive"
            )
        }.map { it.tag }.toMutableList()
        val selectorTag = "PROXY"
        if (proxyTags.isEmpty()) {
            proxyTags.add("direct")
        }

        val selectorDefault = activeNode
            ?.let { nodeTagMap[it.id] ?: it.name }
            ?.takeIf { it in proxyTags }
            ?: proxyTags.firstOrNull()
        if (activeNode != null) {
            val mappedTag = nodeTagMap[activeNode.id]
            Log.d(TAG, "Selector default: activeNode=${activeNode.name}, id=${activeNode.id}, mappedTag=$mappedTag, selectorDefault=$selectorDefault, inProxyTags=${selectorDefault in proxyTags}")
            if (mappedTag == null && activeNode.name !in proxyTags) {
                Log.w(TAG, "WARNING: Active node not in nodeTagMap and name not in proxyTags! Node may not be selected correctly.")
                Log.w(TAG, "  Available proxyTags (first 10): ${proxyTags.take(10)}")
                Log.w(TAG, "  nodeTagMap keys (first 10): ${nodeTagMap.keys.take(10)}")
            }
        }

        val selectorOutbound = Outbound(
            type = "selector",
            tag = selectorTag,
            outbounds = proxyTags,
            default = selectorDefault,
            interruptExistConnections = true
        )
        val existingProxyIndexes = fixedOutbounds.withIndex()
            .filter { it.value.tag == selectorTag }
            .map { it.index }
        if (existingProxyIndexes.isNotEmpty()) {
            existingProxyIndexes.asReversed().forEach { idx ->
                fixedOutbounds.removeAt(idx)
            }
        }
        fixedOutbounds.add(0, selectorOutbound)
        val nodeTagResolver: (String?) -> String? = { value ->
            if (value.isNullOrBlank()) {
                null
            } else {
                nodeTagMap[value]
                    ?: resolveNodeRefToId(value)?.let { nodeTagMap[it] }
                    ?: if (fixedOutbounds.any { it.tag == value }) value else null
            }
        }

        // Final safety check:
        // 1) Normalize detour node refs to runtime tag
        // 2) Filter out non-existent references in Selector/URLTest
        // 3) Validate detour target exists (or clear detour)
        val detourNormalizedOutbounds = fixedOutbounds.map { outbound ->
            val detourValue = outbound.detour
            if (detourValue.isNullOrBlank()) return@map outbound
            val mappedDetourTag = nodeTagResolver(detourValue)
            if (mappedDetourTag != null && mappedDetourTag != detourValue) {
                outbound.copy(detour = mappedDetourTag)
            } else {
                outbound
            }
        }

        val allOutboundTags = detourNormalizedOutbounds.map { it.tag }.toSet()
        val selectorSafeOutbounds = detourNormalizedOutbounds.map { outbound ->
            if (outbound.type == "selector" || outbound.type == "urltest" || outbound.type == "url-test") {
                val validRefs = outbound.outbounds?.filter { allOutboundTags.contains(it) } ?: emptyList()
                val safeRefs = if (validRefs.isEmpty()) listOf("direct") else validRefs

                if (safeRefs.size != (outbound.outbounds?.size ?: 0)) {
                    Log.w(TAG, "Filtered invalid refs in ${outbound.tag}: ${outbound.outbounds} -> $safeRefs")
                }

                val currentDefault = outbound.default
                val safeDefault = if (currentDefault != null && safeRefs.contains(currentDefault)) {
                    currentDefault
                } else {
                    safeRefs.firstOrNull()
                }

                outbound.copy(outbounds = safeRefs, default = safeDefault)
            } else {
                outbound
            }
        }

        val finalTags = selectorSafeOutbounds.map { it.tag }.toSet()
        val safeOutbounds = selectorSafeOutbounds.map { outbound ->
            val detourTag = outbound.detour
            if (detourTag.isNullOrBlank()) return@map outbound

            val isInvalidDetour = detourTag == outbound.tag || detourTag !in finalTags
            if (isInvalidDetour) {
                Log.w(TAG, "Cleared invalid detour for ${outbound.tag}: detour=$detourTag")
                outbound.copy(detour = null)
            } else {
                outbound
            }
        }

        return RunOutboundsContext(
            outbounds = safeOutbounds,
            selectorTag = selectorTag,
            nodeTagResolver = nodeTagResolver,
            nodeTagMap = nodeTagMap
        )
    }

    private fun buildQuicBlockRule(settings: AppSettings): List<RouteRule> {
        return if (settings.blockQuic) {
            listOf(
                RouteRule(protocolRaw = listOf("quic"), outbound = "block"),
                RouteRule(networkRaw = listOf("udp"), port = listOf(443), action = "reject")
            )
        } else {
            emptyList()
        }
    }

    private fun buildBypassLanRules(settings: AppSettings): List<RouteRule> {
        return if (settings.bypassLan) {
            listOf(
                RouteRule(
                    ipCidr = listOf(
                        "10.0.0.0/8",
                        "172.16.0.0/12",
                        "192.168.0.0/16",
                        "fd00::/8",
                        "127.0.0.0/8",
                        "::1/128"
                    ),
                    outbound = "direct"
                )
            )
        } else {
            emptyList()
        }
    }

    private fun buildIcmpEchoRules(settings: AppSettings): List<RouteRule> {
        if (!settings.icmpEchoRoutingEnabled) return emptyList()

        return when (settings.routingMode) {
            RoutingMode.GLOBAL_DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
            RoutingMode.GLOBAL_PROXY -> {
                Log.w(TAG, "ICMP echo proxy outbound is limited; fallback to direct routing")
                listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
            }
            RoutingMode.RULE -> when (settings.defaultRule) {
                DefaultRule.DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                DefaultRule.BLOCK -> listOf(RouteRule(networkRaw = listOf("icmp"), action = "reject"))
                DefaultRule.PROXY -> {
                    Log.w(TAG, "ICMP echo with PROXY default rule falls back to direct routing")
                    listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                }
            }
        }
    }

    private fun buildDefaultRules(settings: AppSettings, selectorTag: String): List<RouteRule> {
        return when (settings.defaultRule) {
            DefaultRule.DIRECT -> listOf(RouteRule(outbound = "direct"))
            DefaultRule.BLOCK -> listOf(RouteRule(outbound = "block"))
            DefaultRule.PROXY -> listOf(RouteRule(outbound = selectorTag))
        }
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
    private fun buildRunRoute(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>,
        bootstrapStrategy: String = "prefer_ipv4"
    ): RouteConfig {
        val hasAppRouting = settings.appRules.any { it.enabled } || settings.appGroups.any { it.enabled }

        val appRoutingRules = buildAppRoutingRules(settings, selectorTag, outbounds, nodeTagResolver)
        val customRuleSetRules =
            buildCustomRuleSetRules(settings, selectorTag, outbounds, nodeTagResolver, validRuleSets)

        val quicRule = buildQuicBlockRule(settings)
        val bypassLanRules = buildBypassLanRules(settings)
        val icmpEchoRules = buildIcmpEchoRules(settings)
        val customDomainRules = buildCustomDomainRules(settings, selectorTag, outbounds, nodeTagResolver)
        val defaultRuleCatchAll = buildDefaultRules(settings, selectorTag)
        val hijackDnsRule = listOf(RouteRule(protocolRaw = listOf("dns"), action = "hijack-dns"))
        val sniffRule = listOf(RouteRule(inbound = listOf("tun-in", "mixed-in"), action = "sniff"))

        val allRules = when (settings.routingMode) {
            RoutingMode.GLOBAL_PROXY -> hijackDnsRule + sniffRule + quicRule + icmpEchoRules
            RoutingMode.GLOBAL_DIRECT ->
                hijackDnsRule + sniffRule + quicRule + icmpEchoRules + listOf(RouteRule(outbound = "direct"))
            RoutingMode.RULE -> {
                hijackDnsRule + sniffRule + quicRule + bypassLanRules + icmpEchoRules +
                    customDomainRules + appRoutingRules + customRuleSetRules + defaultRuleCatchAll
            }
        }

        val normalizedRules = allRules.map { rule ->
            if (!rule.outbound.isNullOrBlank() && rule.action.isNullOrBlank()) {
                rule.copy(action = "route")
            } else {
                rule
            }
        }

        return RouteConfig(
            ruleSet = validRuleSets,
            rules = normalizedRules,
            finalOutbound = selectorTag,
            findProcess = hasAppRouting,
            autoDetectInterface = true,
            defaultDomainResolver = DomainResolveConfig(
                server = "dns-bootstrap",
                strategy = bootstrapStrategy
            )
        )
    }

    fun getActiveConfig(): SingBoxConfig? {
        val id = _activeProfileId.value ?: return null
        return loadConfig(id)
    }

    fun getConfig(profileId: String): SingBoxConfig? {
        return loadConfig(profileId)
    }

    private fun mapDnsStrategy(strategy: DnsStrategy): String? {
        return when (strategy) {
            DnsStrategy.AUTO -> null
            DnsStrategy.PREFER_IPV4 -> "prefer_ipv4"
            DnsStrategy.PREFER_IPV6 -> "prefer_ipv6"
            DnsStrategy.ONLY_IPV4 -> "ipv4_only"
            DnsStrategy.ONLY_IPV6 -> "ipv6_only"
        }
    }

    fun getOutboundByNodeId(nodeId: String): Outbound? {
        val node = _nodes.value.find { it.id == nodeId } ?: return null
        val config = loadConfig(node.sourceProfileId) ?: return null
        return config.outbounds?.find { it.tag == node.name }
    }

    fun getNodeById(nodeId: String): NodeUi? {
        _nodes.value.find { it.id == nodeId }?.let { return it }
        for ((_, nodes) in profileNodes) {
            nodes.find { it.id == nodeId }?.let { return it }
        }
        _allNodes.value.find { it.id == nodeId }?.let { return it }

        return null
    }

    @Suppress("ReturnCount")
    fun getNodeByName(nodeName: String): NodeUi? {
        _nodes.value.find { it.name == nodeName }?.let { return it }
        for ((_, nodes) in profileNodes) {
            nodes.find { it.name == nodeName }?.let { return it }
        }
        _allNodes.value.find { it.name == nodeName }?.let { return it }

        return null
    }

    fun createNode(
        outbound: Outbound,
        targetProfileId: String? = null,
        newProfileName: String? = null
    ) {
        var createdProfileId: String? = null
        try {
            val profileId: String
            val existingConfig: SingBoxConfig?
            var targetProfile: ProfileUi? = null
            val finalProfileName: String

            when {
                targetProfileId != null -> {
                    targetProfile = _profiles.value.find { it.id == targetProfileId }
                    if (targetProfile != null) {
                        profileId = targetProfileId
                        existingConfig = loadConfig(profileId)
                        finalProfileName = targetProfile.name
                    } else {
                        profileId = UUID.randomUUID().toString()
                        existingConfig = null
                        finalProfileName = "Manual"
                        createdProfileId = profileId
                    }
                }
                newProfileName != null -> {
                    profileId = UUID.randomUUID().toString()
                    existingConfig = null
                    finalProfileName = newProfileName
                    createdProfileId = profileId
                }
                else -> {
                    val manualProfileName = "Manual"
                    targetProfile = _profiles.value.find { it.name == manualProfileName && it.type == ProfileType.Imported }
                    if (targetProfile != null) {
                        profileId = targetProfile.id
                        existingConfig = loadConfig(profileId)
                    } else {
                        profileId = UUID.randomUUID().toString()
                        existingConfig = null
                        createdProfileId = profileId
                    }
                    finalProfileName = manualProfileName
                }
            }
            val newOutbounds = mutableListOf<Outbound>()
            existingConfig?.outbounds?.let { existing ->
                newOutbounds.addAll(existing.filter { it.type !in listOf("direct", "block", "dns") })
            }
            var finalTag = outbound.tag
            var counter = 1
            while (newOutbounds.any { it.tag == finalTag }) {
                finalTag = "${outbound.tag}_$counter"
                counter++
            }
            val finalOutbound = if (finalTag != outbound.tag) outbound.copy(tag = finalTag) else outbound
            newOutbounds.add(finalOutbound)
            if (newOutbounds.none { it.tag == "direct" }) {
                newOutbounds.add(Outbound(type = "direct", tag = "direct"))
            }
            if (newOutbounds.none { it.tag == "block" }) {
                newOutbounds.add(Outbound(type = "block", tag = "block"))
            }
            val newConfig = deduplicateTags(SingBoxConfig(outbounds = newOutbounds))

            writeConfigFileOrThrow(profileId, newConfig)

            cacheConfig(profileId, newConfig)

            if (targetProfile == null) {
                targetProfile = ProfileUi(
                    id = profileId,
                    name = finalProfileName,
                    type = ProfileType.Imported,
                    url = null,
                    lastUpdated = System.currentTimeMillis(),
                    enabled = true,
                    updateStatus = UpdateStatus.Idle
                )
                _profiles.update { it + targetProfile }
            } else {
                _profiles.update { list ->
                    list.map { if (it.id == profileId) it.copy(lastUpdated = System.currentTimeMillis()) else it }
                }
            }

            setActiveProfile(profileId)
            scope.launch {
                val nodes = extractNodesFromConfig(newConfig, profileId)
                profileNodes[profileId] = nodes
                if (_activeProfileId.value == profileId) {
                    _nodes.value = nodes
                }
                updateAllNodesAndGroups()
                val addedNode = nodes.find { it.name == finalTag }
                if (addedNode != null) {
                    _activeNodeId.value = addedNode.id
                }

                saveProfiles()
                Log.i(TAG, "Created node: $finalTag in profile $profileId")
            }
        } catch (e: Exception) {
            createdProfileId?.let { rollbackTransientProfileFile(it) }
            Log.e(TAG, "Failed to create node", e)
        }
    }

    fun deleteNode(nodeId: String) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return
        val newOutbounds = config.outbounds?.filter { it.tag != node.name }
        val newConfig = config.copy(outbounds = newOutbounds)
        cacheConfig(profileId, newConfig)
        writeConfigFileOrThrow(profileId, newConfig)
        scope.launch {
            val newNodes = extractNodesFromConfig(newConfig, profileId)
            profileNodes[profileId] = newNodes
            updateAllNodesAndGroups()
            if (_activeProfileId.value == profileId) {
                _nodes.value = newNodes
                if (_activeNodeId.value == nodeId) {
                    _activeNodeId.value = newNodes.firstOrNull()?.id
                }
            }

            saveProfiles()
        }
    }

    suspend fun addSingleNode(
        link: String,
        targetProfileId: String? = null,
        newProfileName: String? = null
    ): Result<NodeUi> = withContext(Dispatchers.IO) {
        var createdProfileId: String? = null
        try {
            val outbound = parseNodeLink(link.trim())
                ?: return@withContext Result.failure(Exception("Failed to parse node link"))

            val profileId: String
            val existingConfig: SingBoxConfig?
            var isNewProfile = false

            when {
                targetProfileId != null -> {
                    val profile = _profiles.value.find { it.id == targetProfileId }
                    if (profile == null) {
                        return@withContext Result.failure(Exception("Profile not found"))
                    }
                    profileId = targetProfileId
                    existingConfig = loadConfig(profileId)
                }
                newProfileName != null -> {
                    profileId = UUID.randomUUID().toString()
                    existingConfig = null
                    isNewProfile = true
                    createdProfileId = profileId
                }
                else -> {
                    val manualProfileName = "Manual"
                    val manualProfile = _profiles.value.find { it.name == manualProfileName && it.type == ProfileType.Imported }
                    if (manualProfile != null) {
                        profileId = manualProfile.id
                        existingConfig = loadConfig(profileId)
                    } else {
                        profileId = UUID.randomUUID().toString()
                        existingConfig = null
                        isNewProfile = true
                        createdProfileId = profileId
                    }
                }
            }

            val newOutbounds = mutableListOf<Outbound>()
            existingConfig?.outbounds?.let { existing ->
                newOutbounds.addAll(existing.filter { it.type !in listOf("direct", "block", "dns") })
            }

            var finalTag = outbound.tag
            var counter = 1
            while (newOutbounds.any { it.tag == finalTag }) {
                finalTag = "${outbound.tag}_$counter"
                counter++
            }
            val finalOutbound = if (finalTag != outbound.tag) outbound.copy(tag = finalTag) else outbound
            newOutbounds.add(finalOutbound)

            if (newOutbounds.none { it.tag == "direct" }) {
                newOutbounds.add(Outbound(type = "direct", tag = "direct"))
            }
            if (newOutbounds.none { it.tag == "block" }) {
                newOutbounds.add(Outbound(type = "block", tag = "block"))
            }
            val newConfig = deduplicateTags(SingBoxConfig(outbounds = newOutbounds))

            writeConfigFileOrThrow(profileId, newConfig)

            cacheConfig(profileId, newConfig)
            val nodes = extractNodesFromConfig(newConfig, profileId)
            profileNodes[profileId] = nodes

            if (isNewProfile || existingConfig == null) {
                val profileName = newProfileName ?: "Manual"
                val newProfile = ProfileUi(
                    id = profileId,
                    name = profileName,
                    type = ProfileType.Imported,
                    url = null,
                    lastUpdated = System.currentTimeMillis(),
                    enabled = true,
                    updateStatus = UpdateStatus.Idle
                )
                _profiles.update { it + newProfile }
            } else {
                _profiles.update { list ->
                    list.map { if (it.id == profileId) it.copy(lastUpdated = System.currentTimeMillis()) else it }
                }
            }

            updateAllNodesAndGroups()

            setActiveProfile(profileId)
            val addedNode = nodes.find { it.name == finalTag }
            if (addedNode != null) {
                _activeNodeId.value = addedNode.id
            }

            saveProfiles()

            Log.i(TAG, "Added single node: $finalTag to profile $profileId")

            Result.success(addedNode ?: nodes.last())
        } catch (e: Exception) {
            createdProfileId?.let { rollbackTransientProfileFile(it) }
            Log.e(TAG, "Failed to add single node", e)
            Result.failure(Exception(context.getString(R.string.nodes_add_failed) + ": ${e.message}"))
        }
    }

    fun renameNode(nodeId: String, newName: String) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) it.copy(tag = newName) else it
        }
        var newConfig = config.copy(outbounds = newOutbounds)
        newConfig = deduplicateTags(newConfig)
        cacheConfig(profileId, newConfig)
        writeConfigFileOrThrow(profileId, newConfig)

        val oldNodes = profileNodes[profileId] ?: _nodes.value
        val latencyById = oldNodes.associate { it.id to it.latencyMs }
        val updatedNodeId = stableNodeId(profileId, newName)
        val originalLatency = oldNodes.find { it.id == nodeId }?.latencyMs
        scope.launch {
            val newNodes = extractNodesFromConfig(newConfig, profileId)
            val mergedNodes = newNodes.map { nodeItem ->
                val storedLatency = latencyById[nodeItem.id]
                    ?: if (nodeItem.id == updatedNodeId) originalLatency else null
                if (storedLatency != null) nodeItem.copy(latencyMs = storedLatency) else nodeItem
            }
            profileNodes[profileId] = mergedNodes
            updateAllNodesAndGroups()
            if (_activeProfileId.value == profileId) {
                _nodes.value = mergedNodes
                if (_activeNodeId.value == nodeId) {
                    val newNode = mergedNodes.find { it.name == newName }
                    if (newNode != null) {
                        _activeNodeId.value = newNode.id
                    }
                }
            }

            saveProfiles()
        }
    }

    fun updateNode(nodeId: String, newOutbound: Outbound) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) newOutbound else it
        }
        var newConfig = config.copy(outbounds = newOutbounds)
        newConfig = deduplicateTags(newConfig)
        cacheConfig(profileId, newConfig)
        writeConfigFileOrThrow(profileId, newConfig)
        val oldNodes = profileNodes[profileId] ?: _nodes.value
        val latencyById = oldNodes.associate { it.id to it.latencyMs }
        val updatedNodeId = stableNodeId(profileId, newOutbound.tag)
        val originalLatency = oldNodes.find { it.id == nodeId }?.latencyMs
        val isActiveProfile = _activeProfileId.value == profileId
        val isActiveNode = _activeNodeId.value == nodeId
        val newTag = newOutbound.tag
        scope.launch {
            val newNodes = extractNodesFromConfig(newConfig, profileId)
            val mergedNodes = newNodes.map { nodeItem ->
                val storedLatency = latencyById[nodeItem.id]
                    ?: if (nodeItem.id == updatedNodeId) originalLatency else null
                if (storedLatency != null) nodeItem.copy(latencyMs = storedLatency) else nodeItem
            }
            profileNodes[profileId] = mergedNodes
            updateAllNodesAndGroups()
            if (isActiveProfile) {
                _nodes.value = mergedNodes
                if (isActiveNode) {
                    val newNode = mergedNodes.find { it.name == newTag }
                    if (newNode != null) {
                        _activeNodeId.value = newNode.id
                    }
                }
            }

            saveProfiles()
        }
    }

    fun exportNode(nodeId: String): String? {
        val node = _nodes.value.find { it.id == nodeId } ?: run {
            Log.e(TAG, "exportNode: Node not found in UI list: $nodeId")
            return null
        }

        val config = loadConfig(node.sourceProfileId) ?: run {
            Log.e(TAG, "exportNode: Config not found for profile: ${node.sourceProfileId}")
            return null
        }

        val outbound = config.outbounds?.find { it.tag == node.name } ?: run {
            Log.e(TAG, "exportNode: Outbound not found in config with tag: ${node.name}")
            return null
        }

        return NodeLinkExporter.export(outbound, gson)
    }

    private fun deduplicateTags(config: SingBoxConfig): SingBoxConfig {
        val outbounds = config.outbounds ?: return config
        val seenTags = mutableSetOf<String>()

        val newOutbounds = outbounds.map { outbound ->
            var tag = outbound.tag
            if (tag.isBlank()) {
                tag = "unnamed"
            }

            var newTag = tag
            var counter = 1
            while (seenTags.contains(newTag)) {
                newTag = "${tag}_$counter"
                counter++
            }

            seenTags.add(newTag)

            if (newTag != outbound.tag) {
                outbound.copy(tag = newTag)
            } else {
                outbound
            }
        }

        return config.copy(outbounds = newOutbounds)
    }

    private fun findAvailablePort(startPort: Int): Int {
        for (port in startPort until startPort + 100) {
            try {
                java.net.ServerSocket(port).use {
                    return port
                }
            } catch (_: Exception) {
            }
        }
        return startPort
    }

    fun cleanup() {
        scope.cancel()
        nodeIdCache.clear()
        configCache.clear()
        profileNodes.clear()
        savedNodeLatencies.clear()
        inFlightLatencyTests.clear()
        Log.i(TAG, "ConfigRepository cleanup completed")
    }

    private fun isIpAddress(address: String?): Boolean {
        return isIpAddressValue(address)
    }

    private fun extractHost(url: String): String {
        return extractHostFromAddress(url) ?: url
    }
}
