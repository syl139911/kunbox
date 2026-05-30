package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.repository.LogEntry
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.utils.LogExporter
import com.kunk.singbox.viewmodel.LogViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(navController: NavController, viewModel: LogViewModel = viewModel()) {
    val logs by viewModel.logs.collectAsState()
    val context = LocalContext.current

    val settings by viewModel.settings.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("bug_log") }) {
                        Icon(Icons.Rounded.BugReport, contentDescription = stringResource(R.string.bug_log_title), tint = MaterialTheme.colorScheme.onBackground)
                    }
                    if (settings.debugLoggingEnabled) {
                        val exportTitle = stringResource(R.string.logs_export)
                        IconButton(onClick = {
                            val logsText = viewModel.getLogsForExport()
                            LogExporter.shareLogFile(context, logsText, "KunBox_Runtime_Log.txt")
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = exportTitle, tint = MaterialTheme.colorScheme.onBackground)
                        }
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.logs_clear), tint = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (!settings.debugLoggingEnabled) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .width(64.dp)
                            .height(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = stringResource(R.string.logs_debug_disabled),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.logs_debug_disabled_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copiedText = stringResource(R.string.common_copied)

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
                reverseLayout = true
            ) {
                items(logs) { entry ->
                    LogEntryItem(entry) { textToCopy ->
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("log", textToCopy))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogEntryItem(entry: LogEntry, onLongPress: (String) -> Unit) {
    val msg = entry.message
    // Parse timestamp and level from "[HH:mm:ss] LEVEL content"
    val time: String
    val level: String
    val content: String

    if (msg.length > 11 && msg[0] == '[' && msg[9] == ']') {
        time = msg.substring(1, 9)
        val rest = msg.substring(11)
        val levelEnd = rest.indexOf(' ')
        if (levelEnd > 0) {
            level = rest.substring(0, levelEnd)
            content = rest.substring(levelEnd + 1)
        } else {
            level = rest
            content = ""
        }
    } else {
        time = ""
        level = ""
        content = msg
    }

    val countSuffix = if (entry.count > 1) " ×${entry.count}" else ""

    val color = when {
        level.contains("ERROR", ignoreCase = true) || msg.contains("ERROR", ignoreCase = true) ->
            MaterialTheme.colorScheme.error
        level.contains("WARN", ignoreCase = true) || msg.contains("WARN", ignoreCase = true) ->
            MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
        level.contains("DEBUG", ignoreCase = true) || msg.contains("DEBUG", ignoreCase = true) ->
            Neutral500
        else -> MaterialTheme.colorScheme.onSurface
    }

    val fullText = buildString {
        if (time.isNotBlank()) {
            append(time)
            append(" ")
        }
        if (level.isNotBlank()) {
            append("[$level")
            if (countSuffix.isNotBlank()) append(countSuffix)
            append("]")
        }
        if (content.isNotBlank()) {
            if (level.isNotBlank()) append("\n")
            append(content)
        } else if (level.isBlank()) {
            // No parseable format, show raw message
            append(msg)
            if (countSuffix.isNotBlank()) append(" [$countSuffix]")
        }
    }

    Text(
        text = fullText,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .combinedClickable(
                onLongClick = { onLongPress(fullText) },
                onClick = {}
            )
            .padding(vertical = 2.dp)
    )
}
