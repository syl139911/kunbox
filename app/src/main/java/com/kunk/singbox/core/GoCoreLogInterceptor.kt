package com.kunk.singbox.core

import android.util.Log
import com.kunk.singbox.utils.BugLogHelper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Go 核心 (libbox) 运行时错误日志拦截器
 *
 * 实时读取 logcat，捕获 Go 层的连接/出站/传输/DNS/TLS 错误，
 * 自动分类后接入 BugLogHelper。
 *
 * 集成方式：
 *   - VPN 启动成功后：GoCoreLogInterceptor.start()
 *   - VPN 停止时：GoCoreLogInterceptor.stop()
 */
object GoCoreLogInterceptor {

    private const val TAG = "GoCoreLogInterceptor"

    // ========== 错误分类 ==========
    enum class ErrorCategory(val title: String) {
        OUTBOUND("Outbound Error"),
        TRANSPORT("Transport Error"),
        DNS("DNS Error"),
        TLS("TLS Error"),
        CONNECTION("Connection Error"),
        GENERIC("Go Core Error")
    }

    // ========== 匹配规则 ==========

    // Go 核心日志的 tag 关键词
    private val GO_CORE_TAGS = setOf(
        "sing-box", "singbox", "box", "libbox",
        "tun", "inbound", "outbound", "router",
        "dns", "transport", "connection", "proxy"
    )

    // 错误分类模式
    private data class ErrorPattern(
        val keywords: List<String>,
        val category: ErrorCategory,
        val requireAll: Boolean = false
    )

    private val ERROR_PATTERNS = listOf(
        // TLS/证书错误（最高优先级）
        ErrorPattern(listOf("tls", "certificate"), ErrorCategory.TLS),
        ErrorPattern(listOf("x509", "cert", "ssl", "handshake"), ErrorCategory.TLS),

        // DNS 错误
        ErrorPattern(listOf("dns", "resolve", "lookup"), ErrorCategory.DNS),
        ErrorPattern(listOf("no such host", "dns query"), ErrorCategory.DNS),

        // 出站错误
        ErrorPattern(listOf("outbound", "failed"), ErrorCategory.OUTBOUND),
        ErrorPattern(listOf("proxy", "error"), ErrorCategory.OUTBOUND),

        // 传输层错误
        ErrorPattern(listOf("transport", "websocket", "grpc", "quic"), ErrorCategory.TRANSPORT),
        ErrorPattern(listOf("dial", "failed"), ErrorCategory.TRANSPORT),

        // 连接错误
        ErrorPattern(listOf("connect", "refused"), ErrorCategory.CONNECTION),
        ErrorPattern(listOf("connection", "reset"), ErrorCategory.CONNECTION),
        ErrorPattern(listOf("timeout", "deadline"), ErrorCategory.CONNECTION),
        ErrorPattern(listOf("unreachable", "no route"), ErrorCategory.CONNECTION),
        ErrorPattern(listOf("eof", "broken pipe", "i/o"), ErrorCategory.CONNECTION)
    )

    // KunBox HTTP 出站连接日志关键词（直接从 logcat 行匹配，不依赖 tag）
    private val KUNBOX_HTTP_KEYWORDS = listOf(
        "[KunBox-HTTP]", "KunBox-HTTP"
    )

    // 排除的噪声日志
    private val NOISE_PATTERNS = listOf(
        "GoCoreLogInterceptor", "BugLogHelper",
        "ActivityThread", "Choreographer", "ViewRootImpl", "InputMethodManager"
    )

    @Volatile private var process: Process? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var running = false

    // 去重：相同错误短时间内只报一次
    private val recentErrors = LinkedHashMap<String, Long>(64, 0.75f, true)
    private const val DEDUP_WINDOW_MS = 30_000L
    private const val MAX_RECENT_ERRORS = 100

    fun start() {
        if (running) return
        running = true

        readerThread = Thread({
            Log.i(TAG, "Go core log interceptor started")
            try {
                // 不清空缓冲区（会丢失 VPN 启动期间的 Go 日志）
                // 用 -T 0 跳过已有行，只读新增日志
                // *:W = 默认 Warning 以上；GoLog:I + stderr:I = 额外抓取 Go 层 Info 日志
                //       （[KunBox-HTTP] 的 CONNECT 请求/响应日志走 stderr，级别为 Info）
                val pid = android.os.Process.myPid()
                val proc = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "--pid=$pid", "-T", "0", "*:W", "GoLog:I", "system.err:I"
                ))
                process = proc

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?

                while (running) {
                    line = reader.readLine() ?: break
                    processLogLine(line)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Log interceptor error", e)
            } finally {
                Log.i(TAG, "Go core log interceptor exited")
            }
        }, "GoCoreLogInterceptor").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        readerThread = null
    }

    fun isRunning(): Boolean = running

    private fun processLogLine(line: String) {
        val lowerLine = line.lowercase()

        // 排除噪声
        if (NOISE_PATTERNS.any { lowerLine.contains(it.lowercase()) }) return

        // ── KunBox HTTP 出站连接日志（优先匹配，不走 tag/分类过滤）──
        if (KUNBOX_HTTP_KEYWORDS.any { line.contains(it) }) {
            val detail = extractKunBoxHttpDetail(line)
            if (detail.isNotBlank()) {
                // CONNECT 请求和响应不走去重（每条都有价值）
                BugLogHelper.log("GoCore-CONN", detail)
            }
            return
        }

        // 检查是否匹配 Go 核心 tag
        val tag = extractTag(line)
        val isGoCore = tag != null && GO_CORE_TAGS.any { goTag ->
            tag.lowercase().contains(goTag)
        }
        if (!isGoCore) return

        // 分类错误
        val category = classifyError(lowerLine) ?: return

        // 提取错误详情
        val detail = extractDetail(line)
        if (detail.isBlank()) return

        // 去重检查
        val dedupKey = "${category.name}:${detail.take(100)}"
        if (isDuplicate(dedupKey)) return

        // 上报
        Log.w(TAG, "[${category.title}] $detail")
        reportError(category, detail)
    }

    /**
     * 提取 [KunBox-HTTP] 日志的详情，保留完整 CONNECT 请求内容（不截断）
     */
    private fun extractKunBoxHttpDetail(line: String): String {
        // 找到 [KunBox-HTTP] 或 KunBox-HTTP 关键词后的内容
        val markers = listOf("[KunBox-HTTP]", "KunBox-HTTP")
        for (marker in markers) {
            val idx = line.indexOf(marker)
            if (idx >= 0) {
                val start = idx + marker.length
                val msg = line.substring(start).trimStart(':', ' ', '-')
                return msg.trim()
            }
        }
        return extractDetail(line)
    }

    private fun classifyError(line: String): ErrorCategory? {
        for (pattern in ERROR_PATTERNS) {
            val matched = if (pattern.requireAll) {
                pattern.keywords.all { line.contains(it) }
            } else {
                pattern.keywords.any { line.contains(it) }
            }
            if (matched) return pattern.category
        }
        return null
    }

    private fun reportError(category: ErrorCategory, detail: String) {
        when (category) {
            ErrorCategory.OUTBOUND -> BugLogHelper.logOutboundError(
                BugLogHelper.getCurrentNodeOutboundTag() ?: "unknown", detail
            )
            ErrorCategory.TRANSPORT -> BugLogHelper.logTransportError("unknown", detail)
            ErrorCategory.DNS -> BugLogHelper.logDnsError(extractDomain(detail) ?: "unknown", detail)
            ErrorCategory.TLS -> BugLogHelper.logTlsError(
                BugLogHelper.getCurrentNodeName() ?: "unknown", detail
            )
            ErrorCategory.CONNECTION, ErrorCategory.GENERIC ->
                BugLogHelper.logConnectionError("[${category.title}] $detail")
        }
    }

    private fun extractTag(line: String): String? {
        val match = Regex("""\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+(\S+?)\s*:""")
            .find(line)
        return match?.groupValues?.get(1)
    }

    private fun extractDetail(line: String): String {
        val colonIndex = line.indexOfFirst { it == ':' }
        if (colonIndex < 0) return ""
        var start = colonIndex + 1
        while (start < line.length && line[start] == ' ') start++
        val msg = line.substring(start).trim()
        return if (msg.length > 500) msg.substring(0, 500) + "..." else msg
    }

    private fun extractDomain(detail: String): String? {
        val match = Regex("""(?:domain|host|server)[=:\s]+([a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""")
            .find(detail)
        return match?.groupValues?.get(1)
    }

    private fun isDuplicate(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(recentErrors) {
            val iterator = recentErrors.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value > DEDUP_WINDOW_MS) iterator.remove()
            }
            val lastTime = recentErrors[key]
            if (lastTime != null && now - lastTime < DEDUP_WINDOW_MS) return true
            recentErrors[key] = now
            if (recentErrors.size > MAX_RECENT_ERRORS) {
                recentErrors.remove(recentErrors.keys.first())
            }
        }
        return false
    }
}
