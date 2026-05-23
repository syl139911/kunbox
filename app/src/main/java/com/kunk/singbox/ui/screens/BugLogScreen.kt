package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.viewmodel.BugLogViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugLogScreen(navController: NavController, viewModel: BugLogViewModel = viewModel()) {
    val bugLogs by viewModel.bugLogs.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(bugLogs.size) {
        if (bugLogs.isNotEmpty()) {
            listState.animateScrollToItem(bugLogs.size - 1)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.bug_log_title),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (bugLogs.isNotEmpty()) {
                            Text(
                                "${bugLogs.size} entries",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    // Copy all
                    val copiedMsg = stringResource(R.string.bug_log_copied)
                    IconButton(onClick = {
                        val logsText = viewModel.getLogsForExport()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("KunBox Bug Log", logsText))
                        AppNotificationManager.showMessage(context, copiedMsg)
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.bug_log_copy), tint = MaterialTheme.colorScheme.onBackground)
                    }
                    // Share
                    IconButton(onClick = {
                        val logsText = viewModel.getLogsForExport()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "KunBox Bug Report")
                            putExtra(Intent.EXTRA_TEXT, logsText)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Bug Log"))
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    // Export to file
                    IconButton(onClick = {
                        val logsText = viewModel.getLogsForExport()
                        val file = saveBugLogToFile(context, logsText)
                        if (file != null) {
                            AppNotificationManager.showMessage(context, "Saved to ${file.name}")
                        } else {
                            AppNotificationManager.showMessage(context, "Export failed")
                        }
                    }) {
                        Icon(Icons.Rounded.SaveAlt, contentDescription = "Export", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    // Clear
                    val clearedMsg = stringResource(R.string.bug_log_cleared)
                    IconButton(onClick = {
                        viewModel.clearLogs()
                        AppNotificationManager.showMessage(context, clearedMsg)
                    }) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.bug_log_clear), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (bugLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BugReport,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .width(64.dp)
                            .height(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = stringResource(R.string.bug_log_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
            ) {
                items(bugLogs) { entry ->
                    val timeStr = synchronized(dateFormat) { dateFormat.format(Date(entry.timestamp)) }

                    // Title line with timestamp
                    Text(
                        text = "[$timeStr] ${entry.title}",
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )

                    // Detail text
                    Text(
                        text = entry.detail,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )

                    // Stack trace (collapsible visually by being smaller + dimmer)
                    if (!entry.stackTrace.isNullOrBlank()) {
                        Text(
                            text = entry.stackTrace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun saveBugLogToFile(context: Context, content: String): File? {
    return try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(context.cacheDir, "bug_reports")
        dir.mkdirs()
        val file = File(dir, "bug_report_$timestamp.txt")
        file.writeText(content)
        file
    } catch (_: Exception) {
        null
    }
}
