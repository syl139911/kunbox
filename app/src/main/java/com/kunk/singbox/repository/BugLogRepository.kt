package com.kunk.singbox.repository

import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
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

    private val mmkv: MMKV by lazy {
        MMKV.mmkvWithID("bug_log", MMKV.SINGLE_PROCESS_MODE)
    }
    private val gson = Gson()

    private val _bugLogs = MutableStateFlow<List<BugLogEntry>>(emptyList())
    val bugLogs: StateFlow<List<BugLogEntry>> = _bugLogs.asStateFlow()

    private val logs = CopyOnWriteArrayList<BugLogEntry>()
    private val maxLogSize = 200

    init {
        loadFromDisk()
    }

    fun addBugLog(title: String, detail: String, throwable: Throwable? = null) {
        val entry = BugLogEntry(
            timestamp = System.currentTimeMillis(),
            title = title,
            detail = detail,
            stackTrace = throwable?.let { getStackTrace(it) }
        )
        logs.add(entry)
        while (logs.size > maxLogSize) {
            logs.removeAt(0)
        }
        _bugLogs.value = logs.toList()
        saveToDisk()
    }

    /**
     * Add a bug log with full context: stack trace + runtime config snapshot.
     */
    fun addBugLogWithContext(
        title: String,
        detail: String,
        throwable: Throwable? = null,
        runtimeConfig: String? = null
    ) {
        val fullDetail = if (runtimeConfig != null) {
            "$detail\n\n--- Runtime Config ---\n$runtimeConfig"
        } else {
            detail
        }
        addBugLog(title, fullDetail, throwable)
    }

    fun getBugLogs(): List<BugLogEntry> = logs.toList()

    fun clearBugLogs() {
        logs.clear()
        _bugLogs.value = emptyList()
        mmkv.remove("bug_logs_json")
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

    private fun saveToDisk() {
        try {
            val json = gson.toJson(logs.toList())
            mmkv.encode("bug_logs_json", json)
        } catch (_: Exception) {
        }
    }

    private fun loadFromDisk() {
        try {
            val json = mmkv.decodeString("bug_logs_json")
            if (!json.isNullOrBlank()) {
                val type = object : TypeToken<List<BugLogEntry>>() {}.type
                val loaded: List<BugLogEntry> = gson.fromJson(json, type) ?: emptyList()
                logs.clear()
                logs.addAll(loaded.takeLast(maxLogSize))
                _bugLogs.value = logs.toList()
            }
        } catch (_: Exception) {
            mmkv.remove("bug_logs_json")
        }
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
