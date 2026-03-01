package com.kunk.singbox.utils.parser

import android.util.Log
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 注释已清理。
 */
interface SubscriptionParser {
    /**
     * 注释已清理。
     */
    fun canParse(content: String): Boolean

    /**
     * 注释已清理。
     */
    fun parse(content: String): SingBoxConfig?
}

/**
 * 注释已清理。
 * 注释已清理。
 */
object DnsResolveCache {
    private const val TAG = "DnsResolveCache"

    /**
     * 注释已清理。
     */
    private data class CacheEntry(val ip: String, val timestamp: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val failedDomains = ConcurrentHashMap<String, Long>()

    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    private const val RETRY_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * 注释已清理。
     * 注释已清理。
     */
    fun getResolvedIp(domain: String): String? {
        val entry = cache[domain] ?: return null
        val currentTime = System.currentTimeMillis()
        return if (currentTime - entry.timestamp < CACHE_TTL_MS) {
            entry.ip
        } else {

            cache.remove(domain)
            null
        }
    }

    /**
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     */
    suspend fun preResolve(domains: List<String>): Int = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()

        failedDomains.entries.removeIf { currentTime - it.value >= RETRY_INTERVAL_MS }

        val toResolve = domains.filter { domain ->

            val entry = cache[domain]
            if (entry != null && currentTime - entry.timestamp < CACHE_TTL_MS) {
                return@filter false
            }

            val failedTime = failedDomains[domain]
            if (failedTime != null && currentTime - failedTime < RETRY_INTERVAL_MS) {
                return@filter false
            }

            if (isIpAddress(domain)) return@filter false
            true
        }.distinct()

        if (toResolve.isEmpty()) return@withContext 0

        Log.d(TAG, "Pre-resolving ${toResolve.size} domains...")

        val results = toResolve.map { domain ->
            async {
                try {
                    val addresses = InetAddress.getAllByName(domain)
                    val ip = addresses.firstOrNull()?.hostAddress
                    if (ip != null) {
                        cache[domain] = CacheEntry(ip, currentTime)
                        Log.d(TAG, "Resolved $domain -> $ip")
                        1
                    } else {
                        failedDomains[domain] = currentTime
                        0
                    }
                } catch (e: Exception) {
                    failedDomains[domain] = currentTime
                    Log.w(TAG, "Failed to resolve $domain: ${e.message}")
                    0
                }
            }
        }.awaitAll()

        val successCount = results.sum()
        Log.d(TAG, "Pre-resolved $successCount/${toResolve.size} domains")
        successCount
    }

    /**
     * 注释已清理。
     */
    fun extractDomains(outbounds: List<Outbound>): List<String> {
        return outbounds.mapNotNull { outbound ->
            val server = outbound.server ?: return@mapNotNull null

            if (isIpAddress(server)) return@mapNotNull null
            server
        }.distinct()
    }

    /**
     * 注释已清理。
     */
    private fun isIpAddress(host: String): Boolean {
        // 注释已清理。
        if (host.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
            return true
        }
        // 注释已清理。
        if (host.contains(":") && !host.contains(".")) {
            return true
        }
        return false
    }

    fun clear() {
        cache.clear()
        failedDomains.clear()
    }

    /**
     * 注释已清理。
     */
    fun getStats(): Pair<Int, Int> = Pair(cache.size, failedDomains.size)
}

/**
 * 注释已清理。
 */
class SubscriptionManager(private val parsers: List<SubscriptionParser>) {

    companion object {
        private const val TAG = "SubscriptionManager"

        /**
         * 注释已清理。
         * 注释已清理。
         */
        private fun getDeduplicationKey(outbound: Outbound): String? {
            val server = outbound.server ?: return null
            val port = outbound.serverPort ?: return null
            val type = outbound.type

            if (type == "selector" || type == "urltest" || type == "direct" || type == "block" || type == "dns") {
                return null
            }

            val credential = outbound.password ?: outbound.uuid ?: ""
            return "$type://$credential@$server:$port"
        }

        /**
         * 注释已清理。
         * 注释已清理。
         */
        fun deduplicateOutbounds(outbounds: List<Outbound>): List<Outbound> {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<Outbound>()
            var duplicateCount = 0

            for (outbound in outbounds) {
                val key = getDeduplicationKey(outbound)
                if (key == null) {

                    result.add(outbound)
                } else if (seen.add(key)) {

                    result.add(outbound)
                } else {

                    duplicateCount++
                }
            }

            if (duplicateCount > 0) {
                Log.d(TAG, "Deduplicated $duplicateCount duplicate nodes, ${result.size} unique nodes remaining")
            }

            return result
        }
    }

    /**
     * 注释已清理。
     */
    fun parse(content: String): SingBoxConfig? {
        for (parser in parsers) {
            if (parser.canParse(content)) {
                try {
                    val config = parser.parse(content)
                    if (config != null && !config.outbounds.isNullOrEmpty()) {
                        // 注释已清理。
                        val deduplicatedOutbounds = deduplicateOutbounds(config.outbounds)
                        return config.copy(outbounds = deduplicatedOutbounds)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parser ${parser.javaClass.simpleName} failed", e)
                }
            }
        }
        return null
    }

    /**
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     */
    suspend fun parseWithDnsPreResolve(content: String, preResolveDns: Boolean = true): Pair<SingBoxConfig?, Int> {
        val config = parse(content)
        if (config == null || config.outbounds.isNullOrEmpty()) {
            return Pair(null, 0)
        }

        if (!preResolveDns) {
            return Pair(config, 0)
        }

        // 注释已清理。
        val domains = DnsResolveCache.extractDomains(config.outbounds)
        val resolvedCount = DnsResolveCache.preResolve(domains)

        return Pair(config, resolvedCount)
    }
}
