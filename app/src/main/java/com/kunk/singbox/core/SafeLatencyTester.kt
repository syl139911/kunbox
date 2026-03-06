package com.kunk.singbox.core

import android.util.Log
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.service.SingBoxService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 *
 */
@Suppress("TooManyFunctions")
class SafeLatencyTester private constructor() {

    companion object {
        private const val TAG = "SafeLatencyTester"

        private const val DEFAULT_GROUP_TAG = "PROXY"

        private const val URL_TEST_TIMEOUT_MS = 15000L

        private const val CIRCUIT_BREAKER_THRESHOLD = 3
        private const val CIRCUIT_BREAKER_COOLDOWN_MS = 10000L

        const val DEFAULT_CONCURRENCY = 1

        @Volatile
        private var instance: SafeLatencyTester? = null

        fun getInstance(): SafeLatencyTester {
            return instance ?: synchronized(this) {
                instance ?: SafeLatencyTester().also { instance = it }
            }
        }
    }

    private val isTestingActive = AtomicBoolean(false)
    private val consecutiveFailures = AtomicInteger(0)
    private val lastCircuitBreakerTrip = AtomicLong(0)

    private var guardJob: Job? = null

    /**
     *
     */
    @Suppress("UNUSED_PARAMETER", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    suspend fun testOutboundsLatencySafe(
        outbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        if (outbounds.isEmpty()) return

        if (isCircuitBreakerOpen()) {
            Log.w(TAG, "Circuit breaker is open, skipping test")
            outbounds.forEach { onResult(it.tag, -1L) }
            return
        }

        if (!isTestingActive.compareAndSet(false, true)) {
            Log.w(TAG, "Another test is in progress, waiting for cached results")
            waitForCachedResults(outbounds, URL_TEST_TIMEOUT_MS, onResult)
            return
        }

        try {
            Log.i(TAG, "Starting URL test for ${outbounds.size} nodes via group API")
            val requestedTags = outbounds.map { it.tag }.toSet()
            val emittedTags = ConcurrentHashMap.newKeySet<String>()
            val successCount = AtomicInteger(0)

            val results = triggerGroupUrlTest(
                groupTag = DEFAULT_GROUP_TAG,
                expectedTags = requestedTags,
                onProgress = { progressResults ->
                    progressResults.forEach { (tag, delay) ->
                        if (tag in requestedTags && delay > 0 && emittedTags.add(tag)) {
                            onResult(tag, delay.toLong())
                            successCount.incrementAndGet()
                        }
                    }
                }
            )

            if (results.isEmpty()) {
                Log.w(TAG, "URL test returned no results, marking all as failed")
                handleTestFailure()
                outbounds.forEach { outbound ->
                    if (emittedTags.add(outbound.tag)) {
                        onResult(outbound.tag, -1L)
                    }
                }
                return
            }

            consecutiveFailures.set(0)

            outbounds.forEach { outbound ->
                if (!emittedTags.add(outbound.tag)) return@forEach
                val delay = results[outbound.tag]
                if (delay != null && delay > 0) {
                    onResult(outbound.tag, delay.toLong())
                    successCount.incrementAndGet()
                } else {
                    onResult(outbound.tag, -1L)
                }
            }

            Log.i(TAG, "URL test completed: ${successCount.get()}/${outbounds.size} succeeded")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "URL test failed: ${e.message}")
            handleTestFailure()
            outbounds.forEach { onResult(it.tag, -1L) }
        } finally {
            isTestingActive.set(false)
        }
    }

    /**
     */
    private suspend fun triggerGroupUrlTest(
        groupTag: String,
        expectedTags: Set<String>,
        onProgress: ((Map<String, Int>) -> Unit)? = null
    ): Map<String, Int> {
        val service = SingBoxService.instance
        if (service == null) {
            Log.w(TAG, "SingBoxService not available")
            return emptyMap()
        }

        return try {
            withTimeoutOrNull(URL_TEST_TIMEOUT_MS) {
                service.urlTestGroup(groupTag, URL_TEST_TIMEOUT_MS, expectedTags, onProgress)
            } ?: run {
                Log.w(TAG, "URL test timeout for group: $groupTag")
                emptyMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "URL test error: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun waitForCachedResults(
        outbounds: List<Outbound>,
        timeoutMs: Long,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        val service = SingBoxService.instance ?: return
        val deadline = System.currentTimeMillis() + timeoutMs
        val emittedTags = mutableSetOf<String>()
        while (System.currentTimeMillis() < deadline) {
            var hasAnyResult = false
            outbounds.forEach { outbound ->
                val delay = service.getCachedUrlTestDelay(outbound.tag)
                if (delay != null && delay > 0) {
                    hasAnyResult = true
                    if (emittedTags.add(outbound.tag)) {
                        onResult(outbound.tag, delay.toLong())
                    }
                }
            }
            if (emittedTags.size == outbounds.size) {
                return
            }
            if (hasAnyResult && emittedTags.isNotEmpty()) {
                delay(120)
            } else {
                delay(100)
            }
        }

        outbounds.forEach { outbound ->
            if (emittedTags.add(outbound.tag)) {
                onResult(outbound.tag, -1L)
            }
        }
    }

    /**
     */
    private fun handleTestFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
            tripCircuitBreaker()
        }
    }

    private fun isCircuitBreakerOpen(): Boolean {
        val lastTrip = lastCircuitBreakerTrip.get()
        if (lastTrip == 0L) return false

        val elapsed = System.currentTimeMillis() - lastTrip
        return elapsed < CIRCUIT_BREAKER_COOLDOWN_MS
    }

    /**
     */
    private fun tripCircuitBreaker() {
        lastCircuitBreakerTrip.set(System.currentTimeMillis())
        Log.e(TAG, "Circuit breaker tripped! Cooling down for ${CIRCUIT_BREAKER_COOLDOWN_MS}ms")
    }

    /**
     */
    fun cancelTest() {
        guardJob?.cancel()
        guardJob = null
    }

    /**
     */
    fun isTesting(): Boolean = isTestingActive.get()
}
