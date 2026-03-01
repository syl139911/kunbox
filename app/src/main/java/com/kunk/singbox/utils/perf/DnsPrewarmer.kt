package com.kunk.singbox.utils.perf

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

object DnsPrewarmer {
    private const val TAG = "DnsPrewarmer"
    private val dnsCache = ConcurrentHashMap<String, List<String>>()

    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private val cacheTimestamps = ConcurrentHashMap<String, Long>()

    // 妤犵偠娉涜ぐ鍌炴⒔閹邦剙鐓?
    private const val MAX_CONCURRENCY = 8

    private const val RESOLVE_TIMEOUT_MS = 2000L

    private const val TOTAL_TIMEOUT_MS = 3000L

    /**
     */
    data class PrewarmResult(
        val totalDomains: Int,
        val resolvedDomains: Int,
        val cachedDomains: Int,
        val failedDomains: Int,
        val durationMs: Long
    )

    /**
     */
    suspend fun prewarm(configContent: String): PrewarmResult = withContext(Dispatchers.IO) {
        PerfTracer.begin(PerfTracer.Phases.DNS_PREWARM)

        val domains = extractNodeDomains(configContent)
        if (domains.isEmpty()) {
            val duration = PerfTracer.end(PerfTracer.Phases.DNS_PREWARM)
            return@withContext PrewarmResult(0, 0, 0, 0, duration)
        }

        Log.d(TAG, "Prewarming ${domains.size} domains...")

        var resolvedCount = 0
        var cachedCount = 0
        var failedCount = 0

        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            val semaphore = Semaphore(MAX_CONCURRENCY)

            coroutineScope {
                domains.map { domain ->
                    async {
                        semaphore.withPermit {
                            val result = resolveWithCache(domain)
                            synchronized(this@DnsPrewarmer) {
                                when (result) {
                                    ResolveResult.RESOLVED -> resolvedCount++
                                    ResolveResult.CACHED -> cachedCount++
                                    ResolveResult.FAILED -> failedCount++
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val duration = PerfTracer.end(PerfTracer.Phases.DNS_PREWARM)
        val result = PrewarmResult(
            totalDomains = domains.size,
            resolvedDomains = resolvedCount,
            cachedDomains = cachedCount,
            failedDomains = failedCount,
            durationMs = duration
        )

        Log.i(
            TAG,
            "DNS prewarm completed: ${result.resolvedDomains} resolved, " +
                "${result.cachedDomains} cached, ${result.failedDomains} failed " +
                "in ${result.durationMs}ms"
        )

        result
    }

    /**
     */
    suspend fun prewarmSingle(domain: String): Boolean = withContext(Dispatchers.IO) {
        if (domain.isBlank() || isIpAddress(domain)) {
            return@withContext true
        }

        val result = resolveWithCache(domain)
        result != ResolveResult.FAILED
    }

    fun clearCache() {
        dnsCache.clear()
        cacheTimestamps.clear()
        Log.d(TAG, "DNS cache cleared")
    }

    /**
     */
    fun getCachedAddresses(domain: String): List<String>? {
        val timestamp = cacheTimestamps[domain] ?: return null
        if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) {
            dnsCache.remove(domain)
            cacheTimestamps.remove(domain)
            return null
        }
        return dnsCache[domain]
    }

    private enum class ResolveResult {
        RESOLVED,
        CACHED,
        FAILED
    }

    private suspend fun resolveWithCache(domain: String): ResolveResult {
        val cached = getCachedAddresses(domain)
        if (cached != null) {
            Log.v(TAG, "DNS cache hit: $domain -> ${cached.firstOrNull()}")
            return ResolveResult.CACHED
        }

        return withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            try {
                val addresses = InetAddress.getAllByName(domain)
                if (addresses.isNotEmpty()) {
                    val addressList = addresses.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
                    dnsCache[domain] = addressList
                    cacheTimestamps[domain] = System.currentTimeMillis()
                    Log.v(TAG, "DNS resolved: $domain -> ${addressList.firstOrNull()}")
                    ResolveResult.RESOLVED
                } else {
                    ResolveResult.FAILED
                }
            } catch (e: Exception) {
                Log.w(TAG, "DNS resolve failed: $domain - ${e.message}")
                ResolveResult.FAILED
            }
        } ?: ResolveResult.FAILED
    }

    /**
     */
    private fun extractNodeDomains(configJson: String): Set<String> {
        val domains = mutableSetOf<String>()

        val serverRegex = """"server"\s*:\s*"([^"]+)"""".toRegex()
        serverRegex.findAll(configJson).forEach { match ->
            val server = match.groupValues[1]
            if (server.isNotBlank() && !isIpAddress(server) && isValidDomain(server)) {
                domains.add(server)
            }
        }

        val addressRegex = """"address"\s*:\s*"([^"]+)"""".toRegex()
        addressRegex.findAll(configJson).forEach { match ->
            val address = match.groupValues[1]

            if (address.startsWith("https://") || address.startsWith("tls://")) {
                val host = extractHostFromUrl(address)
                if (host != null && !isIpAddress(host) && isValidDomain(host)) {
                    domains.add(host)
                }
            }
        }

        return domains
    }

    /**
     */
    private fun isValidDomain(host: String): Boolean {
        if (!host.contains('.')) return false
        if (host.startsWith('.') || host.endsWith('.')) return false
        return host.matches(Regex("""^[a-zA-Z0-9][a-zA-Z0-9\-.]*[a-zA-Z0-9]$"""))
    }

    private fun extractHostFromUrl(url: String): String? {
        return try {
            val withoutScheme = url.substringAfter("://")
            val hostPort = withoutScheme.substringBefore("/")
            hostPort.substringBefore(":")
        } catch (_: Exception) {
            null
        }
    }

    private fun isIpAddress(host: String): Boolean {
        // IPv4
        if (host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
            return true
        }
        // IPv6
        if (host.contains(":") && host.matches(Regex("""^[0-9a-fA-F:]+$"""))) {
            return true
        }
        // IPv6 with brackets
        if (host.startsWith("[") && host.endsWith("]")) {
            return true
        }
        return false
    }
}
