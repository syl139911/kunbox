package com.kunk.singbox.utils.perf

import android.net.Network
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 注释已清理。
 * 注释已清理。
 */
object StateCache {
    private const val TAG = "StateCache"

    private val cachedNetwork = AtomicReference<NetworkCache?>(null)
    private const val networkCacheTtlMs = 5000L // 注释已清理。

    // 注释已清理。
    private val cachedVpnState = AtomicReference<VpnStateCache?>(null)
    private const val vpnStateCacheTtlMs = 1000L // 注释已清理。
    private val cachedSettings = AtomicReference<SettingsCache?>(null)
    private const val settingsCacheTtlMs = 10000L // 注释已清理。

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
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
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
     * 注释已清理。
     */
    fun invalidateNetworkCache() {
        cachedNetwork.set(null)
    }

    /**
     * 注释已清理。
     * 注释已清理。
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
     * 注释已清理。
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
     * 注释已清理。
     */
    fun invalidateVpnState() {
        cachedVpnState.set(null)
    }

    /**
     * 注释已清理。
     * 注释已清理。
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
     * 注释已清理。
     */
    fun invalidateSettings() {
        cachedSettings.set(null)
    }

    /**
     * 注释已清理。
     */
    fun clearAll() {
        cachedNetwork.set(null)
        cachedVpnState.set(null)
        cachedSettings.set(null)
    }

    /**
     * 注释已清理。
     * @return Pair<savedCount, totalCount>
     */
    fun getIpcStats(): Pair<Long, Long> {
        return Pair(ipcSavedCount.get(), ipcTotalCount.get())
    }

    /**
     * 注释已清理。
     */
    fun getIpcSavedPercent(): Int {
        val total = ipcTotalCount.get()
        if (total == 0L) return 0
        return ((ipcSavedCount.get() * 100) / total).toInt()
    }

    /**
     * 注释已清理。
     */
    fun logStats() {
        val (saved, total) = getIpcStats()
        val percent = getIpcSavedPercent()
        Log.i(TAG, "IPC Stats: saved=$saved, total=$total, saved_percent=$percent%")
    }

    /**
     * 注释已清理。
     */
    fun resetStats() {
        ipcSavedCount.set(0)
        ipcTotalCount.set(0)
    }
}
