package com.kunk.singbox.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import android.os.Build
import com.kunk.singbox.utils.BugLogHelper

data class LogEntry(
    val message: String,
    val count: Int = 1,
    val rawMessage: String = ""
)

class LogRepository private constructor() {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _currentFilter = MutableStateFlow<String?>(null)
    val currentFilter: StateFlow<String?> = _currentFilter.asStateFlow()

    val availableCategories = listOf("CONN", "VPN", "CFG", "NET", "ERR", "DBG", "INFO")

    private val maxLogSize = 500
    private val maxLogLineLength = 2000
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val buffer = ArrayDeque<LogEntry>(maxLogSize)
    private val logVersion = AtomicLong(0)
    private val flushRunning = AtomicBoolean(false)
    private val logUiActiveCount = AtomicInteger(0)

    @Volatile private var fileSyncJob: Job? = null
    @Volatile private var lastSyncedFileSize: Long = -1L
    @Volatile private var lastSyncedFileMtime: Long = -1L

    fun setLogUiActive(active: Boolean) {
        if (active) {
            val count = logUiActiveCount.incrementAndGet()
            if (count == 1) {
                reloadFromFileBestEffort()
                startFileSyncLoopIfNeeded()
            }
            requestFlush()
        } else {
            while (true) {
                val cur = logUiActiveCount.get()
                if (cur <= 0) return
                if (logUiActiveCount.compareAndSet(cur, cur - 1)) {
                    if (cur - 1 <= 0) {
                        stopFileSyncLoop()
                    }
                    return
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    fun addLog(message: String) {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }

        // ---- BugLog Bridge: 在过滤器之前捕获，避免 DNS 等 DEBUG 日志被拦截 ----
        try {
            when {
                message.contains("ERROR") || message.contains("[ERR]") ->
                    BugLogHelper.log("GoCore", message.substringAfter("] ").trim().ifEmpty { message })
                (message.contains("WARN") || message.contains("[WRN]")) &&
                    listOf("dial","connect","timeout","refused","dns","tls","proxy","outbound")
                        .any { it in message.lowercase() } ->
                    BugLogHelper.log("GoCore", message.substringAfter("] ").trim().ifEmpty { message })
                // 没网时 DNS 解析失败（DEBUG 级别也要抓）
                message.contains("dns", ignoreCase = true) &&
                    listOf("no such host","network is unreachable","no route to host","timeout","refused")
                        .any { it in message.lowercase() } ->
                    BugLogHelper.log("DNS", message.substringAfter("] ").trim().ifEmpty { message })
            }
        } catch (_: Exception) {}

        if (message.contains("TRACE")) {
            return
        }

        if (message.contains("DEBUG")) {
            val isHighFreq = message.contains("selector: selected outbound") ||
                message.contains("dns: exchange") ||
                message.contains("dns: lookup") ||
                message.contains("dns: match") ||
                message.contains("dns: cached") ||
                message.contains("dns: server")

            if (isHighFreq) return
        }

        if (message.contains("INFO") &&
            (message.contains("inbound/tun") ||
                message.contains("inbound/mixed") ||
                message.contains("router: found package") ||
                message.contains("router: found user") ||
                message.contains("outbound/vless") ||
                message.contains("outbound/vmess") ||
                message.contains("outbound/trojan") ||
                message.contains("outbound/shadowsocks") ||
                message.contains("outbound/hysteria") ||
                message.contains("outbound/tuic") ||
                message.contains("dns: rejected") ||
                message.contains("dns: exchanged") ||
                message.contains("dns: cached"))) {
            return
        }

        val formattedLog = "[$timestamp] $message"
        val finalLog = if (formattedLog.length > maxLogLineLength) {
            formattedLog.substring(0, maxLogLineLength)
        } else {
            formattedLog
        }

        // Merge consecutive duplicate logs (compare raw content, ignore timestamp)
        // After file sync, entry.message = "[HH:mm:ss] LEVEL content" (with timestamp),
        // but incoming `message` = "LEVEL content" (without timestamp).
        // Always compare rawMessage to rawMessage to avoid format mismatch.
        synchronized(buffer) {
            val lastEntry = buffer.peekLast()
            val lastRaw = lastEntry?.rawMessage
                ?: lastEntry?.let { extractRawFromFormatted(it.message) }
            if (lastEntry != null && lastRaw == message) {
                // Same as last log, increment count
                buffer.removeLast()
                buffer.addLast(LogEntry(finalLog, lastEntry.count + 1, message))
            } else {
                // Different log, add new entry
                if (buffer.size >= maxLogSize) {
                    buffer.removeFirst()
                }
                buffer.addLast(LogEntry(finalLog, 1, message))
            }
            logVersion.incrementAndGet()
        }

        writeToFileBestEffort()

        requestFlush()
    }

    private fun requestFlush() {
        if (logUiActiveCount.get() <= 0) return
        if (!flushRunning.compareAndSet(false, true)) return

        scope.launch {
            var lastSeenVersion = logVersion.get()
            delay(200)

            while (true) {
                val snapshot = synchronized(buffer) {
                    buffer.toList()
                }
                _logs.value = snapshot

                val nowVersion = logVersion.get()
                if (nowVersion == lastSeenVersion) {
                    flushRunning.set(false)
                    if (logVersion.get() != nowVersion) {
                        if (flushRunning.compareAndSet(false, true)) {
                            lastSeenVersion = logVersion.get()
                            delay(200)
                            continue
                        }
                    }
                    break
                }

                lastSeenVersion = nowVersion
                delay(200)
            }
        }
    }

    fun clearLogs() {
        synchronized(buffer) {
            buffer.clear()
            logVersion.incrementAndGet()
        }
        _logs.value = emptyList()
        clearFileBestEffort()
    }

    fun setFilter(category: String?) {
        _currentFilter.value = category
        requestFlush()
    }

    fun getFilteredLogs(): List<LogEntry> {
        val filter = _currentFilter.value
        val allLogs = synchronized(buffer) { buffer.toList() }

        return if (filter == null) {
            allLogs
        } else {
            allLogs.filter { entry ->
                entry.message.contains("[$filter]")
            }
        }
    }

    fun searchLogs(keyword: String): List<LogEntry> {
        if (keyword.isBlank()) return getFilteredLogs()

        val keywordLower = keyword.lowercase()
        return getFilteredLogs().filter { entry ->
            entry.message.lowercase().contains(keywordLower)
        }
    }

    fun getErrorSummary(): List<LogEntry> {
        return synchronized(buffer) {
            buffer.filter { entry ->
                entry.message.contains("[ERR]") || entry.message.contains("[E]") || entry.message.contains("error", ignoreCase = true)
            }.toList()
        }
    }

    fun getLogsAsText(): String {
        val exportDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val header = buildString {
            appendLine("=== KunBox Runtime Log ===")
            appendLine("Export Time: ${exportDateFormat.format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("========================")
            appendLine()
        }

        val logContent = synchronized(buffer) {
            buffer.joinToString("\n") { entry ->
                if (entry.count > 1) {
                    formatLogLineForExport(entry)
                } else {
                    entry.message
                }
            }
        }

        return header + logContent
    }

    /**
     * Extract raw message (without timestamp) from a formatted log line.
     * "[HH:mm:ss] LEVEL content" -> "LEVEL content"
     */
    private fun extractRawFromFormatted(message: String): String {
        return if (message.length > 11 && message[0] == '[' && message[9] == ']') {
            message.substring(11)
        } else {
            message
        }
    }

    /**
     * Format a log entry for export: insert repeat count into the log line.
     * Single-line format so reloadFromFileBestEffort can parse it back correctly.
     * e.g. "[18:31:48] ERROR message" -> "[18:31:48] [ERROR ×131] message"
     */
    private fun formatLogLineForExport(entry: LogEntry): String {
        val msg = entry.message
        // message format: "[HH:mm:ss] LEVEL content"
        return if (msg.length > 11 && msg[0] == '[' && msg[9] == ']') {
            val time = msg.substring(1, 9)
            val rest = msg.substring(11)
            val levelEnd = rest.indexOf(' ')
            val level = if (levelEnd > 0) rest.substring(0, levelEnd) else rest
            val content = if (levelEnd > 0) rest.substring(levelEnd + 1) else ""
            if (content.isNotBlank()) {
                "[$time] [$level ×${entry.count}] $content"
            } else {
                "[$time] [$level ×${entry.count}]"
            }
        } else {
            "$msg [×${entry.count}]"
        }
    }

    companion object {
        @Volatile
        private var instance: LogRepository? = null

        @Volatile
        private var appContext: Context? = null

        fun init(context: Context) {
            appContext = context.applicationContext
        }

        fun getInstance(): LogRepository {
            return instance ?: synchronized(this) {
                instance ?: LogRepository().also { instance = it }
            }
        }
    }

    private fun getLogFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, "running.log")
    }

    private fun writeToFileBestEffort() {
        val file = getLogFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val snapshot = synchronized(buffer) { buffer.toList() }
            val text = snapshot.joinToString("\n") { entry ->
                if (entry.count > 1) {
                    formatLogLineForExport(entry)
                } else {
                    entry.message
                }
            } + if (snapshot.isNotEmpty()) "\n" else ""
            file.writeText(text)
        }
    }

    private fun clearFileBestEffort() {
        val file = getLogFile() ?: return
        runCatching {
            if (file.exists()) {
                file.writeText("")
            }
        }
    }

    private fun reloadFromFileBestEffort() {
        val file = getLogFile() ?: return
        runCatching {
            if (!file.exists()) return
            val lines = readLastLines(file, maxLogSize)
            synchronized(buffer) {
                buffer.clear()
                for (l in lines) {
                    if (l.isNotBlank()) {
                        // Parse exported format back to LogEntry
                        val entry = parseLogLine(l)
                        buffer.addLast(entry)
                    }
                }
                logVersion.incrementAndGet()
            }
        }
    }

    /**
     * Parse a log line back into a LogEntry.
     * Handles export format: "[HH:mm:ss] [LEVEL ×N] content"
     * Also handles raw format: "[HH:mm:ss] LEVEL content"
     */
    private fun parseLogLine(line: String): LogEntry {
        // Match export format: [HH:mm:ss] [LEVEL ×N] content
        // Only match known log levels to avoid false positives
        // when message content itself contains [×N] patterns
        val mergedMatch = Regex("""^(\[\d{2}:\d{2}:\d{2}]) \[(ERROR|WARN|WARNING|INFO|DEBUG|TRACE|ERR|DBG)\s+×(\d+)]\s*(.*)""").find(line)
        if (mergedMatch != null) {
            val time = mergedMatch.groupValues[1]  // [HH:mm:ss]
            val level = mergedMatch.groupValues[2]  // LEVEL
            val count = mergedMatch.groupValues[3].toIntOrNull() ?: 1
            val content = mergedMatch.groupValues[4]
            // Reconstruct original message format: "[HH:mm:ss] LEVEL content"
            val message = if (content.isNotBlank()) {
                "$time $level $content"
            } else {
                "$time $level"
            }
            // Reconstruct raw message (without timestamp) for comparison
            val rawMessage = if (content.isNotBlank()) {
                "$level $content"
            } else {
                level
            }
            return LogEntry(message, count, rawMessage)
        }
        return LogEntry(line, 1, line)
    }

    private fun startFileSyncLoopIfNeeded() {
        if (fileSyncJob?.isActive == true) return
        fileSyncJob = scope.launch {
            while (logUiActiveCount.get() > 0) {
                syncFromFileOnceBestEffort()
                delay(600)
            }
        }
    }

    private fun stopFileSyncLoop() {
        fileSyncJob?.cancel()
        fileSyncJob = null
    }

    private fun syncFromFileOnceBestEffort() {
        val file = getLogFile() ?: return
        runCatching {
            if (!file.exists()) return
            val size = file.length()
            val mtime = file.lastModified()
            if (size == lastSyncedFileSize && mtime == lastSyncedFileMtime) return
            lastSyncedFileSize = size
            lastSyncedFileMtime = mtime

            val lines = readLastLines(file, maxLogSize)
            val changed = synchronized(buffer) {
                val current = buffer.toList()
                val newEntries = lines.filter { it.isNotBlank() }.map { parseLogLine(it) }
                if (current.map { it.message } == newEntries.map { it.message }) {
                    false
                } else {
                    buffer.clear()
                    for (entry in newEntries) {
                        buffer.addLast(entry)
                    }
                    logVersion.incrementAndGet()
                    true
                }
            }
            if (changed) requestFlush()
        }
    }

    private fun readLastLines(file: File, maxLines: Int): List<String> {
        val deque = ArrayDeque<String>(maxLines)
        file.useLines { seq ->
            seq.forEach { line ->
                if (deque.size >= maxLines) deque.removeFirst()
                deque.addLast(line)
            }
        }
        return deque.toList()
    }
}
