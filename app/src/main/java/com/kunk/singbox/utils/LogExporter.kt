package com.kunk.singbox.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object LogExporter {

    /**
     * Export log text as a file and share via system chooser.
     * @param context Android context
     * @param logContent The log text content
     * @param fileName The export file name (e.g. "KunBox_Runtime_Log.txt")
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
}
