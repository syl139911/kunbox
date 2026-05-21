package com.kunk.singbox.repository

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class BugLogRepository private constructor() {

    private val _bugLogs = MutableStateFlow<List<BugLogEntry>>(emptyList())
    val bugLogs: StateFlow<List<BugLogEntry>> = _bugLogs.asStateFlow()

    private val logs = CopyOnWriteArrayList<BugLogEntry>()
    private val maxLogSize = 200

    fun addBugLog(title: String, detail: String, throwable: Throwable? = null) {
        val entry = BugLogEntry(
            timestamp = System.currentTimeMillis(),
            title = title,
            detail = detail,
            stackTrace = throwable?.let { getStackTrace(it) }
        )
        logs.add(entry)
        // Trim if over max
        while (logs.size > maxLogSize) {
            logs.removeAt(0)
        }
        _bugLogs.value = logs.toList()
    }

    fun getBugLogs(): List<BugLogEntry> = logs.toList()

    fun clearBugLogs() {
        logs.clear()
        _bugLogs.value = emptyList()
    }

    fun getBugLogsAsText(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val header = buildString {
            appendLine("=== KunBox Bug Report ===")
            appendLine("Export Time: ${dateFormat.format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("========================")
            appendLine()
        }

        val logContent = logs.joinToString("\n---\n") { entry ->
            buildString {
                appendLine("Time: ${dateFormat.format(Date(entry.timestamp))}")
                appendLine("Title: ${entry.title}")
                appendLine()
                appendLine("Detail:")
                appendLine(entry.detail)
                if (!entry.stackTrace.isNullOrBlank()) {
                    appendLine()
                    appendLine("StackTrace:")
                    appendLine(entry.stackTrace)
                }
            }
        }

        return header + logContent
    }

    private fun getStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    companion object {
        @Volatile
        private var instance: BugLogRepository? = null

        fun getInstance(): BugLogRepository {
            return instance ?: synchronized(this) {
                instance ?: BugLogRepository().also { instance = it }
            }
        }
    }
}

data class BugLogEntry(
    val timestamp: Long,
    val title: String,
    val detail: String,
    val stackTrace: String? = null
)
