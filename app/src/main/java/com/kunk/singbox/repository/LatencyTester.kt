package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.Outbound
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 注释已清理。
 *
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 */
class LatencyTester(
    private val context: Context,
    private val singBoxCore: SingBoxCore
) {
    companion object {
        private const val TAG = "LatencyTester"
    }

    private val inFlightTests = ConcurrentHashMap<String, CompletableDeferred<Long>>()

    /**
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     */
    suspend fun testNode(
        nodeId: String,
        outbound: Outbound,
        allOutbounds: List<Outbound> = emptyList(),
        onResult: ((Long) -> Unit)? = null
    ): Long {

        val existing = inFlightTests[nodeId]
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<Long>()
        val prev = inFlightTests.putIfAbsent(nodeId, deferred)
        if (prev != null) {
            return prev.await()
        }

        try {
            val result = withContext(Dispatchers.IO) {
                try {
                    val latency = singBoxCore.testOutboundLatency(outbound, allOutbounds)
                    onResult?.invoke(latency)
                    latency
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        -1L
                    } else {
                        Log.e(TAG, "Latency test error for $nodeId", e)
                        LogRepository.getInstance().addLog(
                            context.getString(R.string.nodes_test_failed, outbound.tag) + ": ${e.message}"
                        )
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
            inFlightTests.remove(nodeId, deferred)
        }
    }

    /**
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     */
    suspend fun testBatch(
        outbounds: List<Outbound>,
        onNodeComplete: ((String, Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (outbounds.isEmpty()) {
            Log.w(TAG, "No outbounds to test")
            return@withContext
        }

        singBoxCore.testOutboundsLatency(outbounds) { tag, latency ->
            val latencyValue = if (latency > 0) latency else -1L
            onNodeComplete?.invoke(tag, latencyValue)
        }
    }

    /**
     * 注释已清理。
     */
    fun cancelAll() {
        inFlightTests.values.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(-1L)
            }
        }
        inFlightTests.clear()
    }

    fun isTestingNode(nodeId: String): Boolean {
        return inFlightTests.containsKey(nodeId)
    }

    /**
     * 注释已清理。
     */
    fun getActiveTestCount(): Int {
        return inFlightTests.size
    }
}
