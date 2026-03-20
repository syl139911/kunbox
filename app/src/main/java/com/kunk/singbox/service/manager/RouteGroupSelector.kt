package com.kunk.singbox.service.manager

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import io.nekohasekai.libbox.CommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

class RouteGroupSelector(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "RouteGroupSelector"
        private const val AUTO_SELECT_INTERVAL_MS = 30L * 60L * 1000L
        private const val INITIAL_DELAY_MS = 1200L
        private const val LATENCY_TEST_TIMEOUT_MS = 10000L
        private const val MAX_CONCURRENT_TESTS = 4

        internal data class RouteGroupTarget(
            val groupTag: String,
            val candidates: List<String>
        )

        internal fun collectRouteGroupTargets(config: SingBoxConfig): List<RouteGroupTarget> {
            val referencedOutbounds = config.route?.rules
                .orEmpty()
                .mapNotNull { it.outbound?.trim() }
                .filter { it.isNotBlank() }
                .toSet()

            if (referencedOutbounds.isEmpty()) {
                return emptyList()
            }

            return config.outbounds
                .orEmpty()
                .asSequence()
                .filter { it.type == "selector" }
                .filter { referencedOutbounds.contains(it.tag) }
                .filterNot { it.tag.equals("PROXY", ignoreCase = true) }
                .mapNotNull { selector ->
                    val candidates = selector.outbounds
                        .orEmpty()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .filterNot { it.equals("direct", ignoreCase = true) || it.equals("block", ignoreCase = true) }
                        .distinct()
                    if (candidates.isEmpty()) {
                        null
                    } else {
                        RouteGroupTarget(
                            groupTag = selector.tag,
                            candidates = candidates
                        )
                    }
                }
                .toList()
        }

        internal fun selectBestCandidate(
            candidates: Collection<String>,
            urlTestResults: Map<String, Int>
        ): Pair<String, Long>? {
            val candidateSet = candidates
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()

            if (candidateSet.isEmpty()) {
                return null
            }

            return urlTestResults.asSequence()
                .filter { (tag, delay) -> tag in candidateSet && delay > 0 }
                .minByOrNull { (_, delay) -> delay }
                ?.let { it.key to it.value.toLong() }
        }
    }

    private val gson = Gson()
    private var autoSelectJob: Job? = null

    private data class SelectionContext(
        val byTag: Map<String, Outbound>,
        val outbounds: List<Outbound>,
        val core: SingBoxCore,
        val semaphore: Semaphore
    )

    interface Callbacks {
        val isRunning: Boolean
        val isStopping: Boolean
        fun getCommandClient(): CommandClient?
        fun getSelectedOutbound(groupTag: String): String?
        suspend fun urlTestGroup(groupTag: String, expectedTags: Set<String>): Map<String, Int>
    }

    private var callbacks: Callbacks? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun start(configContent: String) {
        stop()

        autoSelectJob = serviceScope.launch {
            Log.i(TAG, "Route group auto-select scheduled, initialDelay=${INITIAL_DELAY_MS}ms")
            delay(INITIAL_DELAY_MS)
            while (callbacks?.isRunning == true && callbacks?.isStopping != true) {
                val targets = runCatching {
                    gson.fromJson(configContent, SingBoxConfig::class.java)
                        ?.let { collectRouteGroupTargets(it) }
                        .orEmpty()
                }.getOrDefault(emptyList())

                if (targets.isEmpty()) {
                    Log.d(TAG, "No route-linked selector groups found for auto-select, stop loop")
                    break
                }

                runCatching {
                    selectBestForRouteGroups(configContent)
                }.onFailure { e ->
                    Log.w(TAG, "Route group auto-select failed: ${e.message}", e)
                }
                Log.d(TAG, "Next route group auto-select in ${AUTO_SELECT_INTERVAL_MS / 60000} minutes")
                delay(AUTO_SELECT_INTERVAL_MS)
            }
            Log.i(TAG, "Route group auto-select loop stopped")
        }
    }

    fun stop() {
        autoSelectJob?.cancel()
        autoSelectJob = null
    }

    private suspend fun selectBestForRouteGroups(configContent: String) {
        val config = runCatching {
            gson.fromJson(configContent, SingBoxConfig::class.java)
        }.getOrNull() ?: return

        val outbounds = config.outbounds.orEmpty()
        val byTag = outbounds.associateBy { it.tag }
        val targetGroups = collectRouteGroupTargets(config)

        if (targetGroups.isEmpty()) {
            Log.d(TAG, "No route-linked selector groups found for auto-select")
            return
        }

        val client = waitForCommandClient(LATENCY_TEST_TIMEOUT_MS) ?: return
        val selectionContext = SelectionContext(
            byTag = byTag,
            outbounds = outbounds,
            core = SingBoxCore.getInstance(context),
            semaphore = Semaphore(permits = MAX_CONCURRENT_TESTS)
        )

        Log.i(TAG, "Route group auto-select running for ${targetGroups.size} groups")

        for (target in targetGroups) {
            if (callbacks?.isRunning != true || callbacks?.isStopping == true) {
                return
            }
            processTargetGroup(
                client = client,
                target = target,
                selectionContext = selectionContext
            )
        }
    }

    private suspend fun processTargetGroup(
        client: CommandClient,
        target: RouteGroupTarget,
        selectionContext: SelectionContext
    ) {
        val currentSelected = callbacks?.getSelectedOutbound(target.groupTag)
        Log.i(
            TAG,
            "Testing route group '${target.groupTag}', current='${currentSelected ?: "(none)"}', " +
                "candidates=${target.candidates.size}"
        )

        val best = findBestCandidate(
            target = target,
            selectionContext = selectionContext
        )

        if (best == null) {
            Log.w(TAG, "No latency result available for group '${target.groupTag}'")
            return
        }

        val (bestTag, bestDelayMs) = best
        Log.i(TAG, "Best node for '${target.groupTag}' is '$bestTag' (${bestDelayMs}ms)")

        if (currentSelected != null && currentSelected == bestTag) {
            Log.d(TAG, "Group '${target.groupTag}' already uses lowest-latency node '$bestTag'")
            return
        }

        switchToBestCandidate(
            client = client,
            groupTag = target.groupTag,
            currentSelected = currentSelected,
            bestTag = bestTag
        )
    }

    private suspend fun findBestCandidate(
        target: RouteGroupTarget,
        selectionContext: SelectionContext
    ): Pair<String, Long>? {
        val urlTestResults = runCatching {
            callbacks?.urlTestGroup(target.groupTag, target.candidates.toSet()).orEmpty()
        }.onFailure { e ->
            Log.w(TAG, "URL test failed for group '${target.groupTag}': ${e.message}", e)
        }.getOrDefault(emptyMap())

        return selectBestCandidate(target.candidates, urlTestResults)
            ?: fallbackBestCandidate(
                candidates = target.candidates,
                byTag = selectionContext.byTag,
                outbounds = selectionContext.outbounds,
                core = selectionContext.core,
                semaphore = selectionContext.semaphore
            )
    }

    private fun switchToBestCandidate(
        client: CommandClient,
        groupTag: String,
        currentSelected: String?,
        bestTag: String
    ) {
        runCatching {
            try {
                client.selectOutbound(groupTag, bestTag)
            } catch (_: Exception) {
                client.selectOutbound(groupTag.lowercase(), bestTag)
            }
            Log.i(TAG, "Route group '$groupTag' switched from '${currentSelected ?: "(none)"}' to '$bestTag'")
        }.onFailure { e ->
            Log.w(TAG, "Failed to switch group '$groupTag' to '$bestTag': ${e.message}", e)
        }
    }

    private suspend fun fallbackBestCandidate(
        candidates: List<String>,
        byTag: Map<String, Outbound>,
        outbounds: List<Outbound>,
        core: SingBoxCore,
        semaphore: Semaphore
    ): Pair<String, Long>? {
        val results = ConcurrentHashMap<String, Long>()

        coroutineScope {
            candidates.map { tag ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val outbound = byTag[tag] ?: return@async
                        val rtt = try {
                            core.testOutboundLatency(outbound, outbounds)
                        } catch (_: Exception) {
                            -1L
                        }
                        if (rtt >= 0) {
                            results[tag] = rtt
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        return results.entries.minByOrNull { it.value }?.let { it.key to it.value }
    }

    private suspend fun waitForCommandClient(timeoutMs: Long): CommandClient? {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val client = callbacks?.getCommandClient()
            if (client != null) {
                return client
            }
            delay(120)
        }
        return callbacks?.getCommandClient()
    }

    fun cleanup() {
        stop()
        callbacks = null
    }
}
