package com.kunk.singbox.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kunk.singbox.repository.BugLogRepository
import com.kunk.singbox.repository.LogRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExporter {

    /**
     * Export log text as a file and share via system chooser.
     */
    fun shareLogFile(context: Context, logContent: String, fileName: String) {
        val exportDir = File(context.cacheDir, "log_export")
        if (!exportDir.exists()) exportDir.mkdirs()

        val file = File(exportDir, fileName)
        file.writeText(logContent)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, fileName))
    }

    /**
     * 合并 Bug 日志 + 运行日志为一个文件，按时间排序
     */
    fun getMergedLogsAsText(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val header = buildString {
            appendLine("=== KunBox Merged Log ===")
            appendLine("Export Time: ${dateFormat.format(Date())}")
            appendLine("========================")
            appendLine()
        }

        data class MergedEntry(val timestamp: Long, val content: String)

        val entries = mutableListOf<MergedEntry>()

        // Bug 日志
        try {
            for (log in BugLogRepository.getInstance().getBugLogs()) {
                val time = timeFormat.format(Date(log.timestamp))
                val text = buildString {
                    appendLine("[$time] [BugLog] ${log.title}")
                    appendLine(log.detail)
                    if (!log.stackTrace.isNullOrBlank()) {
                        appendLine(log.stackTrace)
                    }
                }
                entries.add(MergedEntry(log.timestamp, text))
            }
        } catch (_: Exception) {}

        // 运行日志
        try {
            for (log in LogRepository.getInstance().getLogsAsList()) {
                entries.add(MergedEntry(log.timestamp, log.message + "\n"))
            }
        } catch (_: Exception) {}

        // 按时间排序
        entries.sortBy { it.timestamp }

        val content = entries.joinToString("") { it.content }
        return header + content
    }
}
