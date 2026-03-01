package com.kunk.singbox.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress

object ProbeManager {
    private const val TAG = "ProbeManager"

    /**
     * 注释已清理。
     * 注释已清理。
     */
    private val DEFAULT_PROBE_TARGETS = listOf(
        ProbeTarget("1.1.1.1", 53, "Cloudflare DNS"),
        ProbeTarget("8.8.8.8", 53, "Google DNS"),
        ProbeTarget("223.5.5.5", 53, "Alibaba DNS")
    )

    /**
     * 注释已清理。
     */
    private const val DEFAULT_TIMEOUT_MS = 2000L

    /**
     * 注释已清理。
     */
    data class ProbeTarget(
        val host: String,
        val port: Int,
        val name: String = "$host:$port"
    )

    /**
     * 注释已清理。
     */
    sealed class ProbeResult {
        /**
         * 注释已清理。
         * 注释已清理。
         * 注释已清理。
         */
        data class Success(
            val target: ProbeTarget,
            val latencyMs: Long
        ) : ProbeResult()

        /**
         * 注释已清理。
         * 注释已清理。
         * 注释已清理。
         */
        data class Timeout(
            val target: ProbeTarget,
            val timeoutMs: Long
        ) : ProbeResult()

        /**
         * 注释已清理。
         * 注释已清理。
         * 注释已清理。
         * 注释已清理。
         */
        data class Error(
            val target: ProbeTarget,
            val error: String,
            val exception: Throwable? = null
        ) : ProbeResult()
    }

    /**
     * 注释已清理。
     */
    data class BatchProbeResult(
        val results: List<ProbeResult>,
        val successCount: Int,
        val totalCount: Int,
        val firstSuccessLatencyMs: Long?
    ) {
        val isAnySuccess: Boolean get() = successCount > 0
        val allSuccess: Boolean get() = successCount == totalCount
    }

    data class QuickProbeResult(
        val firstSuccess: ProbeResult.Success?,
        val allFailedByBindPermission: Boolean
    )

    /**
     * 注释已清理。
     *
     * @param context Android Context
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     */
    suspend fun probeViaVpn(
        context: Context,
        target: ProbeTarget = DEFAULT_PROBE_TARGETS.first(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ProbeResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "probeViaVpn: starting probe to ${target.name}")

        val probeNetwork = findProbeNetwork(context)
        if (probeNetwork == null) {
            Log.w(TAG, "probeViaVpn: probe network not found")
            return@withContext ProbeResult.Error(
                target = target,
                error = "Probe network not found"
            )
        }

        Log.d(TAG, "probeViaVpn: found probe network $probeNetwork")
        probeTarget(probeNetwork, target, timeoutMs)
    }

    /**
     * 注释已清理。
     *
     * @param context Android Context
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     */
    suspend fun probeAllViaVpn(
        context: Context,
        targets: List<ProbeTarget> = DEFAULT_PROBE_TARGETS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): BatchProbeResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "probeAllViaVpn: starting batch probe for ${targets.size} targets")

        val probeNetwork = findProbeNetwork(context)
        if (probeNetwork == null) {
            Log.w(TAG, "probeAllViaVpn: probe network not found")
            val errorResults = targets.map { target ->
                ProbeResult.Error(
                    target = target,
                    error = "Probe network not found"
                )
            }
            return@withContext BatchProbeResult(
                results = errorResults,
                successCount = 0,
                totalCount = targets.size,
                firstSuccessLatencyMs = null
            )
        }

        Log.d(TAG, "probeAllViaVpn: found probe network $probeNetwork")

        val results = coroutineScope {
            targets.map { target ->
                async {
                    probeTarget(probeNetwork, target, timeoutMs)
                }
            }.map { it.await() }
        }

        val successResults = results.filterIsInstance<ProbeResult.Success>()
        val firstSuccessLatency = successResults.minByOrNull { it.latencyMs }?.latencyMs

        Log.i(
            TAG,
            "probeAllViaVpn: completed, success=${successResults.size}/${targets.size}, " +
                "firstLatency=${firstSuccessLatency}ms"
        )

        BatchProbeResult(
            results = results,
            successCount = successResults.size,
            totalCount = targets.size,
            firstSuccessLatencyMs = firstSuccessLatency
        )
    }

    /**
     * 注释已清理。
     *
     * @param context Android Context
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     */
    suspend fun probeFirstSuccessViaVpn(
        context: Context,
        targets: List<ProbeTarget> = DEFAULT_PROBE_TARGETS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ProbeResult.Success? {
        return probeFirstSuccessViaVpnDetailed(context, targets, timeoutMs).firstSuccess
    }

    suspend fun probeFirstSuccessViaVpnDetailed(
        context: Context,
        targets: List<ProbeTarget> = DEFAULT_PROBE_TARGETS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): QuickProbeResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "probeFirstSuccessViaVpn: starting quick probe (parallel)")

        val probeNetwork = findProbeNetwork(context)
        if (probeNetwork == null) {
            Log.w(TAG, "probeFirstSuccessViaVpn: probe network not found")
            return@withContext QuickProbeResult(null, false)
        }

        val results = coroutineScope {
            targets.map { target ->
                async { probeTarget(probeNetwork, target, timeoutMs) }
            }.map { it.await() }
        }

        val success = results.filterIsInstance<ProbeResult.Success>().firstOrNull()
        if (success != null) {
            Log.i(TAG, "probeFirstSuccessViaVpn: success on ${success.target.name}, latency=${success.latencyMs}ms")
            return@withContext QuickProbeResult(success, false)
        }

        val errors = results.filterIsInstance<ProbeResult.Error>()
        val allFailedByBindPermission = errors.isNotEmpty() && errors.all { isPermissionError(it) }

        if (allFailedByBindPermission) {
            Log.w(TAG, "probeFirstSuccessViaVpn: all targets failed by permission error, probe unavailable")
        } else {
            Log.w(TAG, "probeFirstSuccessViaVpn: all targets failed")
        }

        QuickProbeResult(null, allFailedByBindPermission)
    }

    /**
     * 注释已清理。
     *
     * @param context Android Context
     * 注释已清理。
     * 注释已清理。
     */
    suspend fun isVpnLinkAvailable(
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        return probeFirstSuccessViaVpn(context, DEFAULT_PROBE_TARGETS, timeoutMs) != null
    }

    /**
     * 注释已清理。
     */
    private fun findProbeNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || cm == null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                Log.w(TAG, "findProbeNetwork: API level < 23, not supported")
            }
            if (cm == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Log.e(TAG, "findProbeNetwork: ConnectivityManager not available")
            }
            return null
        }

        val activeCandidate = cm.activeNetwork?.takeIf { active ->
            isValidPhysicalNetwork(cm.getNetworkCapabilities(active))
        }

        val fallbackCandidate = runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { network ->
                val caps = cm.getNetworkCapabilities(network)
                isValidPhysicalNetwork(caps)
            }
        }.onFailure { e ->
            Log.e(TAG, "findProbeNetwork: failed to enumerate networks", e)
        }.getOrNull()

        return activeCandidate ?: fallbackCandidate
    }

    private fun isValidPhysicalNetwork(caps: NetworkCapabilities?): Boolean {
        if (caps == null) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    /**
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     */
    private suspend fun probeTarget(
        network: Network,
        target: ProbeTarget,
        timeoutMs: Long
    ): ProbeResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "probeTarget: probing ${target.name} via network $network")

        val startTime = System.currentTimeMillis()

        val result = withTimeoutOrNull(timeoutMs) {
            try {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(
                        InetSocketAddress(target.host, target.port),
                        timeoutMs.toInt()
                    )
                }

                val latencyMs = System.currentTimeMillis() - startTime
                Log.i(TAG, "probeTarget: ${target.name} connected, latency=${latencyMs}ms")

                ProbeResult.Success(target, latencyMs)
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.w(TAG, "probeTarget: ${target.name} failed after ${elapsed}ms: ${e.message}")

                ProbeResult.Error(
                    target = target,
                    error = e.message ?: "Unknown error",
                    exception = e
                )
            }
        }

        if (result == null) {
            Log.w(TAG, "probeTarget: ${target.name} timed out after ${timeoutMs}ms")
            ProbeResult.Timeout(target, timeoutMs)
        } else {
            result
        }
    }

    private fun isPermissionError(error: ProbeResult.Error): Boolean {
        val message = error.error.lowercase()
        return message.contains("eperm") ||
            message.contains("operation not permitted") ||
            message.contains("permission denied") ||
            (error.exception is SecurityException)
    }

    /**
     * 注释已清理。
     */
    fun getDefaultTargets(): List<ProbeTarget> = DEFAULT_PROBE_TARGETS.toList()

    /**
     * 注释已清理。
     */
    fun createTarget(host: String, port: Int, name: String? = null): ProbeTarget {
        return ProbeTarget(host, port, name ?: "$host:$port")
    }
}
