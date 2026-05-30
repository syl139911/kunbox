package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.kunk.singbox.utils.NetworkClient
import okhttp3.OkHttpClient
import java.io.File

/**
 */
class RuleSetRepository(private val context: Context) {

    companion object {
        private const val TAG = "RuleSetRepository"

        @Volatile
        private var instance: RuleSetRepository? = null

        fun getInstance(context: Context): RuleSetRepository {
            return instance ?: synchronized(this) {
                instance ?: RuleSetRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val ruleSetDir: File
        get() = File(context.filesDir, "rulesets").also { it.mkdirs() }

    private val settingsRepository = SettingsRepository.getInstance(context)

    private fun getDirectClient(): OkHttpClient {
        return NetworkClient.createClientWithTimeout(
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 60,
            writeTimeoutSeconds = 30
        )
    }

    private fun getProxyClient(settings: AppSettings): OkHttpClient? {
        if (!VpnStateStore.getActive() || settings.proxyPort <= 0) {
            return null
        }
        return NetworkClient.createClientWithProxy(
            proxyPort = settings.proxyPort,
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 60,
            writeTimeoutSeconds = 30
        )
    }

    /**
     */
    fun isRuleSetLocal(tag: String): Boolean {
        return getRuleSetFile(tag).exists()
    }

    /**
     */
    suspend fun hasLocalCache(): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        settings.ruleSets.filter { it.enabled && it.type == RuleSetType.REMOTE }.forEach { ruleSet ->
            if (!getRuleSetFile(ruleSet.tag).exists()) {
                return@withContext false
            }
        }

        true
    }

    /**
     */
    suspend fun ensureRuleSetsReady(
        forceUpdate: Boolean = false,
        allowNetwork: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        var allReady = true

        settings.ruleSets.filter { it.enabled && it.type == RuleSetType.REMOTE }.forEach { ruleSet ->
            val file = getRuleSetFile(ruleSet.tag)

            if (!file.exists()) {
                installBaselineRuleSet(ruleSet.tag, file)
            }

            if (allowNetwork && (!file.exists() || (forceUpdate && isExpired(file)))) {
                onProgress("Updating rule set ${ruleSet.tag}...")
                val success = downloadCustomRuleSet(ruleSet, settings)
                if (!success && !file.exists()) {
                    allReady = false
                    Log.e(TAG, "Failed to download rule set ${ruleSet.tag} and no cache available")
                }
            } else if (!file.exists()) {
                allReady = false
                Log.w(TAG, "Rule set ${ruleSet.tag} missing, and network download is disabled")
            }
        }

        allReady
    }

    /**
     */
    suspend fun prefetchRuleSet(
        ruleSet: RuleSet,
        forceUpdate: Boolean = false,
        allowNetwork: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ruleSet.enabled) return@withContext true

        val settings = settingsRepository.settings.first()

        return@withContext when (ruleSet.type) {
            RuleSetType.LOCAL -> File(ruleSet.path).exists()
            RuleSetType.REMOTE -> {
                val file = getRuleSetFile(ruleSet.tag)
                if (!file.exists()) {
                    installBaselineRuleSet(ruleSet.tag, file)
                }
                if (!allowNetwork) {
                    file.exists()
                } else if (!file.exists() || (forceUpdate && isExpired(file))) {
                    val success = downloadCustomRuleSet(ruleSet, settings)
                    success || file.exists()
                } else {
                    true
                }
            }
        }
    }

    /**
     */
    private fun installBaselineRuleSet(tag: String, targetFile: File): Boolean {
        return try {
            val assetPath = "rulesets/$tag.srs"

            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Baseline rule set installed: ${targetFile.name}")
            true
        } catch (e: Exception) {

            Log.w(TAG, "Baseline rule set not found in assets: $tag")
            false
        }
    }

    /**
     */
    fun getRuleSetPath(tag: String): String {
        return getRuleSetFile(tag).absolutePath
    }

    private fun getRuleSetFile(tag: String): File {
        return File(ruleSetDir, "$tag.srs")
    }

    private fun isExpired(file: File): Boolean {

        val lastModified = file.lastModified()
        val now = System.currentTimeMillis()
        return (now - lastModified) > 24 * 60 * 60 * 1000
    }

    private suspend fun downloadCustomRuleSet(
        ruleSet: RuleSet,
        settings: AppSettings
    ): Boolean {
        if (ruleSet.url.isBlank()) return false
        val mirrorUrl = settings.ghProxyMirror.url

        val mirrorUrlString = normalizeRuleSetUrl(ruleSet.url, mirrorUrl)
        val success = downloadFileWithFallback(mirrorUrlString, getRuleSetFile(ruleSet.tag), settings)

        if (success) return true

        if (mirrorUrlString != ruleSet.url) {
            Log.w(TAG, "Mirror download failed, trying original URL: ${ruleSet.url}")
            return downloadFileWithFallback(ruleSet.url, getRuleSetFile(ruleSet.tag), settings)
        }

        return false
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod", "CognitiveComplexMethod")
    private fun normalizeRuleSetUrl(url: String, mirrorUrl: String): String {
        val rawPrefix = "https://raw.githubusercontent.com/"
        val cdnPrefix = "https://cdn.jsdelivr.net/gh/"

        var rawUrl = url

        // Handle CDN format first
        if (rawUrl.startsWith(cdnPrefix)) {
            val path = rawUrl.removePrefix(cdnPrefix)
            val parts = path.split("@", limit = 2)
            if (parts.size == 2) {
                val userRepo = parts[0]
                val branchPath = parts[1]
                rawUrl = "$rawPrefix$userRepo/$branchPath"
            }
        }

        // Handle proxy/mirror URLs that wrap the original URL (e.g., https://ghp.ci/https://raw...)
        val proxyPrefixes = listOf(
            "https://ghp.ci/",
            "https://mirror.ghproxy.com/",
            "https://ghproxy.com/",
            "https://ghproxy.net/",
            "https://ghfast.top/",
            "https://gh-proxy.com/"
        )

        for (proxy in proxyPrefixes) {
            if (rawUrl.startsWith(proxy)) {
                val afterProxy = rawUrl.removePrefix(proxy)
                if (afterProxy.startsWith("http://") || afterProxy.startsWith("https://")) {
                    // Extract path after the protocol
                    val withoutProtocol = afterProxy
                        .removePrefix("https://")
                        .removePrefix("http://")
                    val firstSlash = withoutProtocol.indexOf('/')
                    if (firstSlash > 0) {
                        rawUrl = "/" + withoutProtocol.substring(firstSlash)
                    } else {
                        rawUrl = "/" + withoutProtocol
                    }
                } else if (afterProxy.startsWith("/")) {
                    rawUrl = afterProxy
                } else {
                    rawUrl = afterProxy
                }
                break
            }
        }

        // If still contains raw.githubusercontent.com but not as proper prefix, extract clean path
        if (rawUrl.contains("raw.githubusercontent.com")) {
            val path = rawUrl.substringAfter("raw.githubusercontent.com/")
            if (path.startsWith("http://") || path.startsWith("https://")) {
                val cleanPath = path
                    .removePrefix("https://")
                    .removePrefix("http://")
                rawUrl = rawPrefix + cleanPath
            } else if (path.contains("raw.githubusercontent.com/")) {
                rawUrl = rawPrefix + path.substringAfter("raw.githubusercontent.com/")
            } else {
                rawUrl = rawPrefix + path
            }
        }

        var updatedUrl = rawUrl

        if (mirrorUrl.contains("cdn.jsdelivr.net")) {
            if (rawUrl.startsWith(rawPrefix)) {
                val path = rawUrl.removePrefix(rawPrefix)
                val parts = path.split("/", limit = 4)
                if (parts.size >= 4) {
                    val user = parts[0]
                    val repo = parts[1]
                    val branch = parts[2]
                    val filePath = parts[3]
                    updatedUrl = "$cdnPrefix$user/$repo@$branch/$filePath"
                }
            }
        } else if (mirrorUrl != rawPrefix) {
            if (rawUrl.startsWith(rawPrefix)) {
                updatedUrl = rawUrl.replace(rawPrefix, mirrorUrl)
            }
        }

        return updatedUrl
    }

    private suspend fun downloadFileWithFallback(
        url: String,
        targetFile: File,
        settings: AppSettings
    ): Boolean {
        val proxyClient = getProxyClient(settings)
        if (proxyClient != null) {
            try {
                val success = downloadFile(proxyClient, url, targetFile)
                if (success) {
                    Log.d(TAG, "Proxy download succeeded: ${targetFile.name}")
                    return true
                }
                Log.w(TAG, "Proxy download failed, falling back to direct")
            } catch (e: Exception) {
                Log.w(TAG, "Proxy download error: ${e.message}, falling back to direct")
            }
        }

        return downloadFile(getDirectClient(), url, targetFile)
    }

    @Suppress("ReturnCount", "NestedBlockDepth", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    private suspend fun downloadFile(client: OkHttpClient, url: String, targetFile: File): Boolean {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${response.code}")
                    return false
                }

                val body = response.body ?: return false
                val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val isValid = try {
                    val header = tempFile.inputStream().use { input ->
                        val buffer = ByteArray(64)
                        val read = input.read(buffer)
                        if (read > 0) String(buffer, 0, read) else ""
                    }
                    val trimmedHeader = header.trim()
                    val isInvalid = trimmedHeader.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                        trimmedHeader.startsWith("<html", ignoreCase = true) ||
                        trimmedHeader.startsWith("{") // JSON error

                    if (isInvalid) {
                        Log.e(TAG, "Downloaded file is invalid (HTML/JSON), discarding: ${targetFile.name}")
                        false
                    } else if (tempFile.length() < 10) {
                        Log.e(TAG, "Downloaded file is too small, discarding: ${targetFile.name}")
                        false
                    } else {
                        true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to verify downloaded file", e)

                    false
                }

                if (isValid) {
                    if (!replaceRuleSetFile(tempFile, targetFile)) {
                        Log.e(TAG, "Failed to replace rule set file, keeping temp file for retry: ${tempFile.name}")
                        return false
                    }
                    tempFile.delete()
                    Log.i(TAG, "Rule set downloaded and verified successfully: ${targetFile.name}")
                    return true
                } else {
                    tempFile.delete()
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            false
        }
    }

    @Suppress("ReturnCount")
    private fun replaceRuleSetFile(tempFile: File, targetFile: File): Boolean {
        val backupFile = File(targetFile.parentFile, "${targetFile.name}.bak")

        if (backupFile.exists() && !backupFile.delete()) {
            Log.e(TAG, "Failed to delete stale backup file: ${backupFile.name}")
            return false
        }

        if (targetFile.exists()) {
            try {
                targetFile.copyTo(backupFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create rule set backup before replace: ${targetFile.name}", e)
                return false
            }

            if (!targetFile.delete()) {
                Log.e(TAG, "Failed to remove existing rule set before replace: ${targetFile.name}")
                return false
            }
        }

        if (tempFile.renameTo(targetFile)) {
            if (backupFile.exists() && !backupFile.delete()) {
                Log.w(TAG, "Failed to delete rule set backup after successful replace: ${backupFile.name}")
            }
            return true
        }

        Log.e(TAG, "Failed to replace rule set file: ${targetFile.name}")
        if (backupFile.exists()) {
            try {
                backupFile.copyTo(targetFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore original rule set after replace failure: ${targetFile.name}", e)
            }
        }
        return false
    }
}
