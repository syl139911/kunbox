package com.kunk.singbox.utils

import android.content.Context
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.SettingsRepository
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 注释已清理。
 *
 * 注释已清理。
 *
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 */
object KernelHttpClient {
    private const val TAG = "KernelHttpClient"

    // 注释已清理。
    private const val DEFAULT_TIMEOUT_MS = 30000

    // 濮掓稒顭堥缁樼閿濆洦鍊炵紒鏃戝灠瑜?
    private const val DEFAULT_PROXY_PORT = 2080

    @Volatile
    private var cachedProxyPort: Int = DEFAULT_PROXY_PORT

    /**
     * 注释已清理。
     */
    data class HttpResult(
        val success: Boolean,
        val statusCode: Int,
        val body: String,
        val error: String?
    ) {
        val isOk: Boolean get() = success && statusCode in 200..299

        companion object {
            fun error(message: String): HttpResult {
                return HttpResult(false, 0, "", message)
            }
        }
    }

    /**
     * 注释已清理。
     * 注释已清理。
     */
    fun updateProxyPort(port: Int) {
        cachedProxyPort = port
        Log.d(TAG, "Proxy port updated to $port")
    }

    /**
     * 注释已清理。
     */
    suspend fun updateProxyPortFromSettings(context: Context) {
        try {
            val settings = SettingsRepository.getInstance(context).settings.first()
            cachedProxyPort = settings.proxyPort
            Log.d(TAG, "Proxy port loaded from settings: $cachedProxyPort")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load proxy port from settings: ${e.message}")
        }
    }

    /**
     * 注释已清理。
     */
    fun getProxyPort(): Int = cachedProxyPort

    /**
     * 注释已清理。
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     * @return HttpResult
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetch(
        url: String,
        outboundTag: String = "proxy",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResult = withContext(Dispatchers.IO) {

        if (isKernelFetchAvailable()) {
            val kernelResult = fetchViaKernel(url)
            if (kernelResult.success) {
                return@withContext kernelResult
            }
            Log.w(TAG, "Kernel fetch failed, falling back to OkHttp: ${kernelResult.error}")
        }

        // 注释已清理。
        Log.d(TAG, "fetch: $url (using OkHttp)")
        fetchWithOkHttp(url, timeoutMs)
    }

    /**
     * 注释已清理。
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     * @return HttpResult
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchWithHeaders(
        url: String,
        headers: Map<String, String>,
        outboundTag: String = "proxy",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResult = withContext(Dispatchers.IO) {

        if (isKernelFetchAvailable()) {
            val kernelResult = fetchViaKernel(url, headers)
            if (kernelResult.success) {
                return@withContext kernelResult
            }
            Log.w(TAG, "Kernel fetch with headers failed, falling back to OkHttp: ${kernelResult.error}")
        }

        // 注释已清理。
        Log.d(TAG, "fetchWithHeaders: $url (using OkHttp)")
        fetchWithOkHttpAndHeaders(url, headers, timeoutMs)
    }

    /**
     * 注释已清理。
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     * @return HttpResult
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun smartFetch(
        url: String,
        preferKernel: Boolean = true,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResult = withContext(Dispatchers.IO) {

        if (preferKernel && isKernelFetchAvailable()) {
            val kernelResult = fetchViaKernel(url)
            if (kernelResult.success) {
                return@withContext kernelResult
            }
            Log.w(TAG, "smartFetch kernel failed, falling back to OkHttp: ${kernelResult.error}")
        }

        // 注释已清理。
        fetchWithOkHttp(url, timeoutMs)
    }

    /**
     * 注释已清理。
     */
    private fun fetchWithOkHttp(url: String, timeoutMs: Int): HttpResult {
        return try {
            val client = NetworkClient.createClientWithTimeout(
                connectTimeoutSeconds = (timeoutMs / 1000).toLong(),
                readTimeoutSeconds = (timeoutMs / 1000).toLong()
            )

            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "KunBox/1.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            HttpResult(
                success = true,
                statusCode = response.code,
                body = body,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "OkHttp fetch error: ${e.message}")
            HttpResult.error("OkHttp error: ${e.message}")
        }
    }

    /**
     * 注释已清理。
     */
    private fun fetchWithOkHttpAndHeaders(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int
    ): HttpResult {
        return try {
            val client = NetworkClient.createClientWithTimeout(
                connectTimeoutSeconds = (timeoutMs / 1000).toLong(),
                readTimeoutSeconds = (timeoutMs / 1000).toLong()
            )

            val requestBuilder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "KunBox/1.0")

            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: ""

            HttpResult(
                success = true,
                statusCode = response.code,
                body = body,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "OkHttp fetch with headers error: ${e.message}")
            HttpResult.error("OkHttp error: ${e.message}")
        }
    }

    /**
     * 注释已清理。
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     * @return HttpResult
     */
    private fun fetchViaKernel(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        var client: io.nekohasekai.libbox.HTTPClient? = null
        try {

            client = Libbox.newHTTPClient()

            val proxyPort = cachedProxyPort
            client.trySocks5(proxyPort)

            // 注释已清理。
            client.modernTLS()
            client.keepAlive()

            val request = client.newRequest()
            request.setURL(url)
            request.setMethod("GET")
            request.randomUserAgent()

            // 注释已清理。
            headers.forEach { (key, value) ->
                request.setHeader(key, value)
            }

            val response = request.execute()
            val content = response.content?.value ?: ""

            Log.d(TAG, "Kernel fetch success: $url (${content.length} bytes)")

            return HttpResult(
                success = true,
                statusCode = 200,
                body = content,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Kernel fetch error: ${e.message}")
            return HttpResult.error("Kernel error: ${e.message}")
        } finally {
            try {
                client?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close HTTP client: ${e.message}")
            }
        }
    }

    /**
     * 注释已清理。
     * 注释已清理。
     */
    fun isKernelFetchAvailable(): Boolean {

        val vpnActive = VpnStateStore.getActive()
        val boxAvailable = BoxWrapperManager.isAvailable()
        return vpnActive && boxAvailable
    }

    /**
     * 注释已清理。
     */
    fun isVpnRunning(): Boolean {
        return BoxWrapperManager.isAvailable()
    }
}
