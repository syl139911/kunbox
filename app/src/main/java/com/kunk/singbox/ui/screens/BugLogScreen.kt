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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.utils.LogExporter
import com.kunk.singbox.viewmodel.BugLogViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
                title = { Text(stringResource(R.string.bug_log_title), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    // Share/Export button
                    val shareLabel = stringResource(R.string.bug_log_share)
                    IconButton(onClick = {
                        val logsText = viewModel.getLogsForExport()
                        LogExporter.shareLogFile(context, logsText, "KunBox_Bug_Log.txt")
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = shareLabel, tint = MaterialTheme.colorScheme.onBackground)
                    }
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
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copiedText = stringResource(R.string.common_copied)

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
                    val countSuffix = if (entry.count > 1) " ×${entry.count}" else ""

                    val titleText = "[$timeStr]$countSuffix ${entry.title}"
                    val fullDetail = buildString {
                        append(titleText)
                        append("\n")
                        append(entry.detail)
                        if (!entry.stackTrace.isNullOrBlank()) {
                            append("\n")
                            append(entry.stackTrace)
                        }
                    }

                    Text(
                        text = titleText,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .combinedClickable(
                                onLongClick = {
                                    clipboardManager.setPrimaryClip(ClipData.newPlainText("bug_log", fullDetail))
                                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                                },
                                onClick = {}
                            )
                            .padding(top = 6.dp, bottom = 2.dp)
                    )

                    Text(
                        text = entry.detail,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )

                    if (!entry.stackTrace.isNullOrBlank()) {
                        Text(
                            text = entry.stackTrace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
