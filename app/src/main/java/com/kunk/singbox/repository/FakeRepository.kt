package com.kunk.singbox.repository

import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.ConnectionStats
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.UpdateStatus
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object FakeRepository {

    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow(ConnectionStats(0, 0, 0, 0, 0))
    val stats: StateFlow<ConnectionStats> = _stats.asStateFlow()

    private val _profiles = MutableStateFlow<List<ProfileUi>>(emptyList())
    val profiles: StateFlow<List<ProfileUi>> = _profiles.asStateFlow()

    private val _nodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val nodes: StateFlow<List<NodeUi>> = _nodes.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _activeNodeId = MutableStateFlow<String?>(null)
    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()

    private var statsJob: Job? = null
    private val repositoryScope = CoroutineScope(Dispatchers.Default)

    init {
        val now = System.currentTimeMillis()
        val mockProfiles = listOf(
            ProfileUi("p1", "HK Subscription", ProfileType.Subscription, "https://sub.example.com/1", now, true),
            ProfileUi("p2", "US Subscription", ProfileType.Subscription, "https://sub.example.com/2", now - 86_400_000, true),
            ProfileUi("p3", "Local Backup", ProfileType.LocalFile, null, now - 10_000_000, false)
        )
        _profiles.value = mockProfiles
        _activeProfileId.value = "p1"

        _nodes.value = listOf(
            NodeUi("n1", "HK Node 01 [VLESS]", "vless", "HK", 45, true, "p1"),
            NodeUi("n2", "HK Node 02 [Trojan]", "trojan", "HK", 52, false, "p1"),
            NodeUi("n3", "US Node 01 [VMess]", "vmess", "US", 180, false, "p2"),
            NodeUi("n4", "JP Osaka [AnyTLS]", "anytls", "JP", 80, true, "p1"),
            NodeUi("n5", "SG Main [Hysteria2]", "hysteria2", "SG", 60, false, "p1")
        )
        _activeNodeId.value = "n1"
    }

    suspend fun toggleConnection() {
        when (_connectionState.value) {
            ConnectionState.Idle, ConnectionState.Error -> {
                _connectionState.value = ConnectionState.Connecting
                delay(1500)
                if (_connectionState.value == ConnectionState.Connecting) {
                    if (Random.nextFloat() > 0.1f) {
                        _connectionState.value = ConnectionState.Connected
                        statsJob?.cancel()
                        statsJob = repositoryScope.launch { startSimulatingStats() }
                    } else {
                        _connectionState.value = ConnectionState.Error
                    }
                }
            }

            ConnectionState.Connecting -> {
                statsJob?.cancel()
                _connectionState.value = ConnectionState.Idle
                _stats.value = ConnectionStats(0, 0, 0, 0, 0)
            }

            ConnectionState.Connected, ConnectionState.Disconnecting -> {
                statsJob?.cancel()
                _connectionState.value = ConnectionState.Disconnecting
                delay(500)
                _connectionState.value = ConnectionState.Idle
                _stats.value = ConnectionStats(0, 0, 0, 0, 0)
            }
        }
    }

    private suspend fun startSimulatingStats() {
        while (_connectionState.value == ConnectionState.Connected) {
            delay(1000)
            _stats.update { current ->
                current.copy(
                    uploadSpeed = Random.nextLong(1024, 1024 * 1024),
                    downloadSpeed = Random.nextLong(1024 * 10, 1024 * 1024 * 10),
                    uploadTotal = current.uploadTotal + Random.nextLong(1024, 1024 * 1024),
                    downloadTotal = current.downloadTotal + Random.nextLong(1024 * 10, 1024 * 1024 * 10),
                    duration = current.duration + 1000
                )
            }
        }
    }

    suspend fun testLatency(nodeId: String) {
        delay(Random.nextLong(200, 800))
        _nodes.update { list ->
            list.map { if (it.id == nodeId) it.copy(latencyMs = Random.nextInt(20, 300).toLong()) else it }
        }
    }

    fun setActiveNode(nodeId: String) {
        _activeNodeId.value = nodeId
    }

    fun setActiveProfile(profileId: String) {
        _activeProfileId.value = profileId
        if (profileId == "p2") {
            _nodes.value = listOf(
                NodeUi("n3", "US Node 01 [VMess]", "vmess", "US", 180, false, "p2"),
                NodeUi("n6", "US Node 02 [AnyTLS]", "anytls", "US", 200, false, "p2"),
                NodeUi("n7", "LA US Node", "vmess", "US", 190, false, "p2")
            )
            _activeNodeId.value = "n3"
            return
        }

        _nodes.value = listOf(
            NodeUi("n1", "HK Node 01 [VLESS]", "vless", "HK", 45, true, "p1"),
            NodeUi("n2", "HK Node 02 [Trojan]", "trojan", "HK", 52, false, "p1"),
            NodeUi("n4", "JP Osaka [AnyTLS]", "anytls", "JP", 80, true, "p1"),
            NodeUi("n5", "SG Main [Hysteria2]", "hysteria2", "SG", 60, false, "p1")
        )
        _activeNodeId.value = "n1"
    }

    fun deleteProfile(profileId: String) {
        _profiles.update { list -> list.filter { it.id != profileId } }
        if (_activeProfileId.value == profileId) {
            _activeProfileId.value = _profiles.value.firstOrNull()?.id
        }
    }

    fun toggleProfileEnabled(profileId: String) {
        _profiles.update { list -> list.map { if (it.id == profileId) it.copy(enabled = !it.enabled) else it } }
    }

    suspend fun updateProfile(profileId: String) {
        _profiles.update { list -> list.map { if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Updating) else it } }
        delay(2000)
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) {
                    it.copy(updateStatus = UpdateStatus.Success, lastUpdated = System.currentTimeMillis())
                } else {
                    it
                }
            }
        }
        delay(1000)
        _profiles.update { list -> list.map { if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Idle) else it } }
    }

    fun addProfile(profile: ProfileUi) {
        val normalized = if (profile.id.isBlank()) profile.copy(id = UUID.randomUUID().toString()) else profile
        _profiles.update { list -> list + normalized }
    }
}
