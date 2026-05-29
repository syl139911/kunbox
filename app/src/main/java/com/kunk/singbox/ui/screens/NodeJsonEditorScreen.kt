package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.gson.GsonBuilder
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.config.OutboundFixer
import com.kunk.singbox.ui.components.AppNotificationManager

/**
 * NodeJsonEditorScreen — 自定义配置 JSON 编辑器
 *
 * 允许直接编辑节点的 outbound JSON，保存时解析并写回配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeJsonEditorScreen(
    navController: NavController,
    nodeId: String
) {
    val context = LocalContext.current
    val configRepository = remember { ConfigRepository.getInstance(context) }
    val gson = remember { GsonBuilder().setPrettyPrinting().create() }

    // Activate UI so nodes stay visible
    DisposableEffect(configRepository) {
        configRepository.setAllNodesUiActive(true)
        onDispose {
            configRepository.setAllNodesUiActive(false)
        }
    }

    val nodes by configRepository.nodes.collectAsState(initial = emptyList())
    val node = nodes.find { it.id == nodeId }

    // JSON text state
    var jsonText by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }
    var isLoaded by remember { mutableStateOf(false) }

    // Load the outbound JSON on first composition or when nodeId changes
    LaunchedEffect(nodeId) {
        if (!isLoaded) {
            val outbound = configRepository.getOutboundByNodeId(nodeId)
            if (outbound != null) {
                jsonText = gson.toJson(outbound)
                isLoaded = true
            } else {
                parseError = context.getString(R.string.node_json_editor_load_failed)
            }
        }
    }

    fun validateAndSave(): Boolean {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) {
            parseError = context.getString(R.string.node_json_editor_empty)
            return false
        }

        val parsed = try {
            gson.fromJson(trimmed, Outbound::class.java)
        } catch (e: Exception) {
            parseError = context.getString(R.string.node_json_editor_parse_error, e.message ?: "Unknown")
            return false
        }

        if (parsed == null) {
            parseError = context.getString(R.string.node_json_editor_parse_error, "null result")
            return false
        }

        if (parsed.type.isBlank()) {
            parseError = context.getString(R.string.node_json_editor_missing_type)
            return false
        }

        if (parsed.tag.isBlank()) {
            parseError = context.getString(R.string.node_json_editor_missing_tag)
            return false
        }

        // Apply automatic fixes (interval normalization, flow cleanup, TLS compat, etc.)
        val fixed = OutboundFixer.fix(parsed)

        configRepository.updateNode(nodeId, fixed)
        parseError = null
        return true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.node_json_editor_title),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (validateAndSave()) {
                            val savedMsg = context.getString(R.string.node_detail_saved)
                            AppNotificationManager.showMessage(context, savedMsg)
                            navController.popBackStack()
                        }
                    }) {
                        Icon(
                            Icons.Rounded.Save,
                            contentDescription = stringResource(R.string.common_save),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            // Node name hint
            node?.let {
                Text(
                    text = it.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Error banner
            parseError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // JSON editor
            BasicTextField(
                value = jsonText,
                onValueChange = {
                    jsonText = it
                    parseError = null // Clear error on edit
                },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}
