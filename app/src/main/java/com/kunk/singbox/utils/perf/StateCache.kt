package com.kunk.singbox.utils.perf

import android.net.Network
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 */
object StateCache {
    private const val TAG = "StateCache"

    private val cachedNetwork = AtomicReference<NetworkCache?>(null)

    private val cachedVpnState = AtomicReference<VpnStateCache?>(null)
    private val cachedSettings = AtomicReference<SettingsCache?>(null)

    private val ipcSavedCount = AtomicLong(0)
    private val ipcTotalCount = AtomicLong(0)

    data class NetworkCache(
        val network: Network?,
        val isValid: Boolean,
        val timestampMs: Long
    )

    data class VpnStateCache(
        val isRunning: Boolean,
        val isConnecting: Boolean,
        val activeNode: String?,
        val timestampMs: Long
    )

    data class SettingsCache(
        val data: Any?,
        val timestampMs: Long
    )

    /**
     */
    fun getNetwork(fetcher: () -> Network?): Network? {
        ipcTotalCount.incrementAndGet()

        val cached = cachedNetwork.get()
        val now = SystemClock.elapsedRealtime()

        if (cached != null && cached.isValid && (now - cached.timestampMs) < networkCacheTtlMs) {
            ipcSavedCount.incrementAndGet()
            return cached.network
        }

        val network = fetcher()
        cachedNetwork.set(NetworkCache(network, network != null, now))
        return network
    }

    fun updateNetworkCache(network: Network?) {
        cachedNetwork.set(NetworkCache(
            network = network,
            isValid = network != null,
            timestampMs = SystemClock.elapsedRealtime()
        ))
    }

    /**
     */
    fun invalidateNetworkCache() {
        cachedNetwork.set(null)
    }

    /**
     */
    fun getVpnState(fetcher: () -> VpnStateCache): VpnStateCache {
        ipcTotalCount.incrementAndGet()

        val cached = cachedVpnState.get()
        val now = SystemClock.elapsedRealtime()

        if (cached != null && (now - cached.timestampMs) < vpnStateCacheTtlMs) {
            ipcSavedCount.incrementAndGet()
            return cached
        }

        val state = fetcher()
        cachedVpnState.set(state.copy(timestampMs = now))
        return state
    }

    /**
     */
    fun updateVpnState(isRunning: Boolean, isConnecting: Boolean, activeNode: String?) {
        cachedVpnState.set(VpnStateCache(
            isRunning = isRunning,
            isConnecting = isConnecting,
            activeNode = activeNode,
            timestampMs = SystemClock.elapsedRealtime()
        ))
    }

    /**
     */
    fun invalidateVpnState() {
        cachedVpnState.set(null)
    }

    /**
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getSettings(fetcher: () -> T): T {
        ipcTotalCount.incrementAndGet()

        val cached = cachedSettings.get()
        val now = SystemClock.elapsedRealtime()

        if (cached != null && cached.data != null && (now - cached.timestampMs) < settingsCacheTtlMs) {
            ipcSavedCount.incrementAndGet()
            return cached.data as T
        }

        val settings = fetcher()
        cachedSettings.set(SettingsCache(settings, now))
        return settings
    }

    /**
     */
    fun invalidateSettings() {
        cachedSettings.set(null)
    }

    /**
     */
    fun clearAll() {
        cachedNetwork.set(null)
        cachedVpnState.set(null)
        cachedSettings.set(null)
    }

    /**
     * @return Pair<savedCount, totalCount>
     */
    fun getIpcStats(): Pair<Long, Long> {
        return Pair(ipcSavedCount.get(), ipcTotalCount.get())
    }

    /**
     */
    fun getIpcSavedPercent(): Int {
        val total = ipcTotalCount.get()
        if (total == 0L) return 0
        return ((ipcSavedCount.get() * 100) / total).toInt()
    }

    /**
     */
    fun logStats() {
        val (saved, total) = getIpcStats()
        val percent = getIpcSavedPercent()
        Log.i(TAG, "IPC Stats: saved=$saved, total=$total, saved_percent=$percent%")
    }

    /**
     */
    fun resetStats() {
        ipcSavedCount.set(0)
        ipcTotalCount.set(0)
    }
}
