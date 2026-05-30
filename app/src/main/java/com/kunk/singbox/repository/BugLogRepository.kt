package com.kunk.singbox.repository

import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _bugLogs = MutableSharedFlow<List<BugLogEntry>>(replay = 1)
    val bugLogs: SharedFlow<List<BugLogEntry>> = _bugLogs.asSharedFlow()

    private val logs = CopyOnWriteArrayList<BugLogEntry>()
    private val maxLogSize = 200

    init {
        // Load persisted logs on init
        loadFromDisk()
    }

    fun addBugLog(title: String, detail: String, throwable: Throwable? = null) {
        val entry = BugLogEntry(
            timestamp = System.currentTimeMillis(),
            title = title,
            detail = detail,
            stackTrace = throwable?.let { getStackTrace(it) }
        )

        // Skip consecutive duplicate entries
        val lastEntry = logs.lastOrNull()
        if (lastEntry != null && lastEntry.title == title && lastEntry.detail == detail) {
            return
        }
        logs.add(entry)
        while (logs.size > maxLogSize) {
            logs.removeAt(0)
        }

        _bugLogs.tryEmit(logs.toList())
        // Persist to disk
        saveToDisk()
    }

    fun getBugLogs(): List<BugLogEntry> = logs.toList()

    /**
     * Add an info-level lifecycle log (VPN start/stop/switch).
     * Skips if the last entry has the same title AND detail (dedup).
     */
    fun addInfoLog(title: String, detail: String) {
        val lastEntry = logs.lastOrNull()
        if (lastEntry != null && lastEntry.title == title && lastEntry.detail == detail) {
            return
        }
        val entry = BugLogEntry(
            timestamp = System.currentTimeMillis(),
            title = title,
            detail = detail,
            stackTrace = null
        )
        logs.add(entry)
        while (logs.size > maxLogSize) {
            logs.removeAt(0)
        }
        _bugLogs.tryEmit(logs.toList())
        saveToDisk()
    }

    fun clearBugLogs() {
        logs.clear()
        _bugLogs.tryEmit(emptyList())
        mmkv.remove("bug_logs_json")
    }

    fun getBugLogsAsText(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val header = buildString {
            appendLine("=== KunBox Bug Log ===")
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
            // Never let persistence crash the app
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
                _bugLogs.tryEmit(logs.toList())
            }
        } catch (_: Exception) {
            // Corrupted data, start fresh
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
