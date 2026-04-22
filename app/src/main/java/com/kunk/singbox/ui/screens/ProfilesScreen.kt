package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.UpdateStatus
import com.kunk.singbox.ui.scanner.QrScannerActivity
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.ProfileCard
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.utils.DeepLinkHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private suspend fun readImportContentSafely(
    context: android.content.Context,
    uri: Uri,
    maxBytes: Int
): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream()
        var totalBytes = 0

        while (true) {
            val read = inputStream.read(buffer)
            if (read <= 0) break
            totalBytes += read
            require(totalBytes <= maxBytes) {
                context.getString(R.string.profiles_import_content_too_large)
            }
            output.write(buffer, 0, read)
        }

        output.toString(Charsets.UTF_8.name())
    } ?: ""
}

@Composable
fun ProfilesScreen(
    navController: NavController,
    viewModel: com.kunk.singbox.viewmodel.ProfilesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val allNodes by viewModel.allNodes.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()

    var showSearchDialog by remember { mutableStateOf(false) }
    var showImportSelection by remember { mutableStateOf(false) }
    var showSubscriptionInput by remember { mutableStateOf(false) }
    var showClipboardInput by remember { mutableStateOf(false) }
    var showCustomConfigInput by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<com.kunk.singbox.model.ProfileUi?>(null) }

    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // Reordering state
    val profileList = remember { mutableStateListOf<com.kunk.singbox.model.ProfileUi>() }
    val isDragging = remember { mutableStateOf(false) }
    var suppressPlacementAnimation by remember { mutableStateOf(false) }
    val enablePlacementAnimation = false

    androidx.compose.runtime.LaunchedEffect(profiles) {
        if (!isDragging.value) {
            val currentIds = profileList.map { it.id }.toSet()
            val newIds = profiles.map { it.id }.toSet()
            if (currentIds != newIds || profileList.size != profiles.size || profileList.isEmpty()) {
                profileList.clear()
                profileList.addAll(profiles)
            } else {
                profiles.forEach { newProfile ->
                    val index = profileList.indexOfFirst { it.id == newProfile.id }
                    if (index != -1 && profileList[index] != newProfile) {
                        profileList[index] = newProfile
                    }
                }
            }
        }
    }

    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggingItemOffset by remember { mutableStateOf(0f) }
    var itemHeightPx by remember { mutableStateOf(0f) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { message ->
            AppNotificationManager.showMessage(context, message)
        }
    }

    val pendingImport by DeepLinkHandler.pendingSubscriptionImport.collectAsState()

    pendingImport?.let { data ->
        ConfirmDialog(
            title = stringResource(R.string.profiles_deep_link_import_title),
            message = stringResource(
                R.string.profiles_deep_link_import_message,
                data.name,
                data.url
            ),
            confirmText = stringResource(R.string.common_import),
            onConfirm = {
                val started = viewModel.importSubscription(
                    name = data.name,
                    url = data.url,
                    autoUpdateInterval = data.autoUpdateInterval
                )
                if (started) {
                    DeepLinkHandler.clearPendingSubscriptionImport()
                } else {
                    AppNotificationManager.showMessage(context, context.getString(R.string.common_loading))
                }
            },
            onDismiss = { DeepLinkHandler.clearPendingSubscriptionImport() }
        )
    }

    // Handle update state feedback
    androidx.compose.runtime.LaunchedEffect(updateStatus) {
        updateStatus?.let {
            AppNotificationManager.showMessage(context, it)
        }
    }

    val importSuccessMsg = stringResource(R.string.profiles_import_success)
    val importFailedMsg = stringResource(R.string.profiles_import_failed)

    // Handle import state feedback
    androidx.compose.runtime.LaunchedEffect(importState) {
        when (val state = importState) {
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Success -> {
                AppNotificationManager.showMessage(
                    context,
                    context.getString(R.string.profiles_import_success, state.profile.name)
                )
                viewModel.resetImportState()
            }
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Error -> {
                AppNotificationManager.showMessage(
                    context = context,
                    message = context.getString(R.string.profiles_import_failed, state.message),
                    duration = androidx.compose.material3.SnackbarDuration.Long
                )
                viewModel.resetImportState()
            }
            // Loading state is now handled by ImportLoadingDialog
            else -> {}
        }
    }

    if (importState is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Loading) {
        ImportLoadingDialog(
            message = (importState as com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Loading).message,
            onCancel = { viewModel.cancelImport() }
        )
    }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var isFabVisible by remember { mutableStateOf(true) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -10f) {
                    isFabVisible = false
                } else if (available.y > 10f) {
                    isFabVisible = true
                }
                return Offset.Zero
            }
        }
    }

    var lastY by remember { mutableStateOf(0f) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val declaredLength = withContext(Dispatchers.IO) {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                            descriptor.length
                        } ?: -1L
                    }
                    if (declaredLength > com.kunk.singbox.viewmodel.ProfilesViewModel.MAX_IMPORT_CONTENT_BYTES) {
                        AppNotificationManager.showMessage(
                            context,
                            context.getString(R.string.profiles_import_content_too_large)
                        )
                        return@launch
                    }

                    val content = readImportContentSafely(
                        context = context,
                        uri = uri,
                        maxBytes = com.kunk.singbox.viewmodel.ProfilesViewModel.MAX_IMPORT_CONTENT_BYTES
                    )

                    if (content.isNotBlank()) {

                        val fileName = uri.lastPathSegment?.let { segment ->

                            segment.substringAfterLast("/")
                                .substringAfterLast(":")
                                .substringBeforeLast(".")
                                .takeIf { it.isNotBlank() }
                        } ?: context.getString(R.string.profiles_file_import)

                        viewModel.importFromContent(fileName, content)
                    } else {
                        AppNotificationManager.showMessage(context, context.getString(R.string.profiles_file_empty))
                    }
                } catch (e: Exception) {
                    AppNotificationManager.showMessage(
                        context = context,
                        message = context.getString(R.string.profiles_read_file_failed, e.message),
                        duration = androidx.compose.material3.SnackbarDuration.Long
                    )
                }
            }
        }
    }

    val qrCodeLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        if (result.contents != null) {
            val scannedContent = result.contents

            val isNodeLink = scannedContent.let {
                it.startsWith("vmess://") || it.startsWith("vless://") ||
                    it.startsWith("ss://") || it.startsWith("ssr://") ||
                    it.startsWith("trojan://") || it.startsWith("hysteria://") ||
                    it.startsWith("hysteria2://") || it.startsWith("hy2://") ||
                    it.startsWith("tuic://") || it.startsWith("wireguard://") ||
                    it.startsWith("ssh://") || it.startsWith("anytls://") ||
                    it.startsWith("naive://") || it.startsWith("naive+https://")
            }

            val isSubscriptionUrl = scannedContent.startsWith("http://") ||
                scannedContent.startsWith("https://")

            when {
                isNodeLink -> {
                    viewModel.importFromContent(context.getString(R.string.profiles_qrcode_import), scannedContent)
                }
                isSubscriptionUrl -> {

                    viewModel.importSubscription(context.getString(R.string.profiles_qrcode_subscription), scannedContent, 0)
                }
                scannedContent.trim().startsWith("{") || scannedContent.trim().startsWith("proxies:") -> {

                    viewModel.importFromContent(context.getString(R.string.profiles_qrcode_import), scannedContent)
                }
                else -> {

                    viewModel.importFromContent(context.getString(R.string.profiles_qrcode_import), scannedContent)
                }
            }
        }
    }

    fun createScanOptions(): ScanOptions {
        return ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
            setCaptureActivity(QrScannerActivity::class.java) // custom scanner activity with transparent status bar
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            qrCodeLauncher.launch(createScanOptions())
        } else {
            AppNotificationManager.showMessage(context, context.getString(R.string.profiles_camera_permission_required))
        }
    }

    if (showImportSelection) {
        ImportSelectionDialog(
            onDismiss = { showImportSelection = false },
            onTypeSelected = { type ->
                showImportSelection = false
                when (type) {
                    ProfileImportType.Subscription -> showSubscriptionInput = true
                    ProfileImportType.Clipboard -> showClipboardInput = true
                    ProfileImportType.Custom -> showCustomConfigInput = true
                    ProfileImportType.File -> {

                        filePickerLauncher.launch(arrayOf(
                            "application/json",
                            "text/plain",
                            "application/x-yaml",
                            "text/yaml",
                            "*/*"
                        ))
                    }
                    ProfileImportType.QRCode -> {

                        when {
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED -> {

                                qrCodeLauncher.launch(createScanOptions())
                            }
                            else -> {

                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    }
                }
            }
        )
    }

    if (showSubscriptionInput) {
        SubscriptionInputDialog(
            onDismiss = { showSubscriptionInput = false },
            onConfirm = { name, url, autoUpdateInterval, dnsPreResolve, dnsServer ->
                viewModel.importSubscription(name, url, autoUpdateInterval, dnsPreResolve, dnsServer)
                showSubscriptionInput = false
            }
        )
    }

    if (showClipboardInput) {
        val clipboardEmptyMsg = stringResource(R.string.profiles_clipboard_empty)
        val nameInvalidMsg = stringResource(R.string.profiles_name_invalid)
        val defaultClipboardName = stringResource(R.string.profiles_clipboard_import)

        InputDialog(
            title = stringResource(R.string.profiles_import_clipboard),
            placeholder = stringResource(R.string.profiles_import_clipboard_hint),
            initialValue = "",
            confirmText = stringResource(R.string.common_import),
            onConfirm = { name ->
                if (name.contains("://")) {
                    AppNotificationManager.showMessage(context, nameInvalidMsg)
                } else {
                    val content = clipboardManager.getText()?.text ?: ""
                    if (content.isNotBlank()) {
                        viewModel.importFromContent(if (name.isBlank()) defaultClipboardName else name, content)
                        showClipboardInput = false
                    } else {
                        AppNotificationManager.showMessage(context, clipboardEmptyMsg)
                    }
                }
            },
            onDismiss = { showClipboardInput = false }
        )
    }

    if (showCustomConfigInput) {
        DisposableEffect(Unit) {
            viewModel.setAllNodesUiActive(true)
            onDispose {
                viewModel.setAllNodesUiActive(false)
            }
        }

        val subscriptionProfileNames = remember(profiles) {
            profiles
                .filter { it.type == ProfileType.Subscription }
                .associate { it.id to it.name }
        }
        val selectableNodes = remember(allNodes, subscriptionProfileNames) {
            allNodes
                .filter { subscriptionProfileNames.containsKey(it.sourceProfileId) }
                .sortedWith(compareBy<NodeUi>({ subscriptionProfileNames[it.sourceProfileId] ?: "" }, { it.name }))
        }

        CustomConfigDialog(
            nodes = selectableNodes,
            profileNames = subscriptionProfileNames,
            onDismiss = { showCustomConfigInput = false },
            onConfirm = { name, selectedNodeIds ->
                viewModel.createCustomConfig(name, selectedNodeIds)
                showCustomConfigInput = false
            }
        )
    }

    if (showSearchDialog) {
        InputDialog(
            title = stringResource(R.string.profiles_search),
            placeholder = stringResource(R.string.profiles_search_hint),
            confirmText = stringResource(R.string.common_search),
            onConfirm = { showSearchDialog = false },
            onDismiss = { showSearchDialog = false }
        )
    }

    if (editingProfile != null) {
        val profile = checkNotNull(editingProfile)
        SubscriptionInputDialog(
            initialName = profile.name,
            initialUrl = profile.url ?: "",
            initialAutoUpdateInterval = profile.autoUpdateInterval,
            initialDnsPreResolve = profile.dnsPreResolve,
            initialDnsServer = profile.dnsServer,
            title = stringResource(R.string.profiles_edit_profile),
            onDismiss = { editingProfile = null },
            onConfirm = { name, url, autoUpdateInterval, dnsPreResolve, dnsServer ->
                viewModel.updateProfileMetadata(profile.id, name, url, autoUpdateInterval, dnsPreResolve, dnsServer)
                editingProfile = null
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            AnimatedVisibility(
                visible = isFabVisible,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                FloatingActionButton(
                    onClick = { showImportSelection = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add Profile")
                }
            }
        }
    ) { padding ->
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        lastY = down.position.y
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val currentY = event.changes.firstOrNull()?.position?.y ?: lastY
                            val deltaY = currentY - lastY
                            if (deltaY < -30f) {
                                isFabVisible = false
                            } else if (deltaY > 30f) {
                                isFabVisible = true
                            }
                            lastY = currentY
                        } while (event.changes.any { it.pressed })
                    }
                }
                .nestedScroll(nestedScrollConnection)
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.profiles_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(onClick = { showSearchDialog = true }) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onBackground)
                }
            }

            // List
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(profileList.size, key = { profileList[it].id }) { index ->
                    val profile = profileList[index]
                    var visible by remember { mutableStateOf(false) }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (index < 15) {
                            delay(index * 30L)
                        }
                        visible = true
                    }

                    val alpha by animateFloatAsState(
                        targetValue = if (visible) 1f else 0f,
                        animationSpec = tween(durationMillis = 300),
                        label = "alpha"
                    )

                    val currentDraggingIndex = draggingItemIndex
                    val currentDragOffset = draggingItemOffset

                    var translationY = if (visible) 0f else 40f
                    var zIndex = 0f

                    if (currentDraggingIndex != null && itemHeightPx > 0) {
                        if (index == currentDraggingIndex) {
                            translationY = currentDragOffset
                            zIndex = 1f
                        } else {
                            val dist = (currentDragOffset / itemHeightPx).toInt()
                            val targetIndex = (currentDraggingIndex + dist).coerceIn(0, profileList.lastIndex)

                            if (currentDraggingIndex < targetIndex) {
                                if (index in (currentDraggingIndex + 1)..targetIndex) {
                                    translationY = -itemHeightPx
                                }
                            } else if (currentDraggingIndex > targetIndex) {
                                if (index in targetIndex until currentDraggingIndex) {
                                    translationY = itemHeightPx
                                }
                            }
                        }
                    }

                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .zIndex(zIndex)
                            .graphicsLayer {
                                this.translationY = translationY
                                this.alpha = alpha
                                if (index == currentDraggingIndex) {
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                    shadowElevation = 8.dp.toPx()
                                }
                            }
                            .onGloballyPositioned { coordinates ->
                                if (itemHeightPx == 0f) {
                                    val spacingPx = with(density) { 12.dp.toPx() }
                                    itemHeightPx = coordinates.size.height.toFloat() + spacingPx
                                }
                            }
                            .then(
                                if (!enablePlacementAnimation || suppressPlacementAnimation) {
                                    Modifier
                                } else {
                                    Modifier.animateItem()
                                }
                            )
                    ) {
                        ProfileCard(
                            name = profile.name,
                            type = profile.type.name,
                            isSelected = profile.id == activeProfileId,
                            isEnabled = profile.enabled,
                            isUpdating = profile.updateStatus == UpdateStatus.Updating &&
                                profile.updateStage?.isBackground != true,
                            updateStatus = profile.updateStatus,
                            updateStage = profile.updateStage,
                            expireDate = profile.expireDate,
                            totalTraffic = profile.totalTraffic,
                            usedTraffic = profile.usedTraffic,
                            lastUpdated = profile.lastUpdated,
                            dnsPreResolve = profile.dnsPreResolve,
                            onClick = { viewModel.setActiveProfile(profile.id) },
                            onUpdate = {
                                viewModel.updateProfile(profile.id)
                            },
                            onToggle = {
                                viewModel.toggleProfileEnabled(profile.id)
                            },
                            onEdit = {
                                if (profile.type == com.kunk.singbox.model.ProfileType.Subscription ||
                                    profile.type == com.kunk.singbox.model.ProfileType.Imported) {
                                    editingProfile = profile
                                } else {
                                    navController.navigate(Screen.ProfileEditor.route)
                                }
                            },
                            onDelete = {
                                viewModel.deleteProfile(profile.id)
                            },
                            modifier = Modifier
                                .pointerInput(index) {
                                    androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingItemIndex = index
                                            draggingItemOffset = 0f
                                            isDragging.value = true
                                            haptic.performHapticFeedback(
                                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                            )
                                        },
                                        onDragEnd = {
                                            draggingItemIndex?.let { startIdx ->
                                                val dist = kotlin.math.round(draggingItemOffset / itemHeightPx).toInt()
                                                val endIdx = (startIdx + dist).coerceIn(0, profileList.lastIndex)

                                                suppressPlacementAnimation = true

                                                val absScrollBefore = if (itemHeightPx > 0f) {
                                                    val index = listState.firstVisibleItemIndex
                                                    val offset = listState.firstVisibleItemScrollOffset
                                                    index * itemHeightPx + offset
                                                } else {
                                                    null
                                                }

                                                if (startIdx != endIdx) {
                                                    val item = profileList.removeAt(startIdx)
                                                    profileList.add(endIdx, item)
                                                    viewModel.reorderProfiles(profileList.toList())
                                                }

                                                val abs = absScrollBefore
                                                if (abs != null && itemHeightPx > 0f) {
                                                    val targetIndex = (abs / itemHeightPx).toInt().coerceIn(
                                                        0,
                                                        profileList.lastIndex
                                                    )
                                                    val targetOffset = (abs - targetIndex * itemHeightPx).toInt()
                                                        .coerceAtLeast(0)
                                                    scope.launch {
                                                        listState.scrollToItem(targetIndex, targetOffset)
                                                    }
                                                }

                                                draggingItemIndex = null
                                                draggingItemOffset = 0f
                                                isDragging.value = false

                                                scope.launch {
                                                    withFrameNanos { }
                                                    suppressPlacementAnimation = false
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            draggingItemIndex = null
                                            draggingItemOffset = 0f
                                            isDragging.value = false
                                            suppressPlacementAnimation = false
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            draggingItemOffset += dragAmount.y
                                        }
                                    )
                                }
                        )
                    }
                }
            }
        }
    }
}

private fun MutableList<String>.updateCustomSelection(nodeId: String, checked: Boolean) {
    if (checked) {
        if (!contains(nodeId)) {
            add(nodeId)
        }
    } else {
        remove(nodeId)
    }
}

private fun buildCustomNodeSubtitle(profileNames: Map<String, String>, node: NodeUi): String {
    val profileName = profileNames[node.sourceProfileId] ?: "未知订阅"
    return "$profileName · ${node.protocolDisplay}"
}

@Composable
private fun CustomConfigNodeItem(
    node: NodeUi,
    subtitle: String,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!isSelected) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = onToggle
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CustomConfigNodeList(
    nodes: List<NodeUi>,
    profileNames: Map<String, String>,
    selectedNodeIds: MutableList<String>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
    ) {
        items(nodes, key = { it.id }) { node ->
            val isSelected = selectedNodeIds.contains(node.id)
            CustomConfigNodeItem(
                node = node,
                subtitle = buildCustomNodeSubtitle(profileNames, node),
                isSelected = isSelected,
                onToggle = { checked -> selectedNodeIds.updateCustomSelection(node.id, checked) }
            )
        }
    }
}

@Composable
private fun CustomConfigDialog(
    nodes: List<NodeUi>,
    profileNames: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val selectedNodeIds = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "自定义配置", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "请输入配置名称",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (nodes.isEmpty()) {
                    Text(
                        text = "暂无可用订阅节点",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    CustomConfigNodeList(
                        nodes = nodes,
                        profileNames = profileNames,
                        selectedNodeIds = selectedNodeIds
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), selectedNodeIds.toList()) },
                enabled = name.isNotBlank() && selectedNodeIds.isNotEmpty()
            ) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

private enum class ProfileImportType { Subscription, File, Clipboard, QRCode, Custom }

@Composable
private fun ImportSelectionDialog(
    onDismiss: () -> Unit,
    onTypeSelected: (ProfileImportType) -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ImportOptionCard(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.profiles_subscription_link),
                subtitle = stringResource(R.string.common_import),
                onClick = { onTypeSelected(ProfileImportType.Subscription) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.Description,
                title = stringResource(R.string.profiles_local_file),
                subtitle = stringResource(R.string.profiles_local_file_subtitle),
                onClick = { onTypeSelected(ProfileImportType.File) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.ContentPaste,
                title = stringResource(R.string.profiles_clipboard),
                subtitle = stringResource(R.string.profiles_clipboard_subtitle),
                onClick = { onTypeSelected(ProfileImportType.Clipboard) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.QrCodeScanner,
                title = stringResource(R.string.profiles_scan_qrcode),
                subtitle = stringResource(R.string.profiles_scan_qrcode_subtitle),
                onClick = { onTypeSelected(ProfileImportType.QRCode) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.DashboardCustomize,
                title = "自定义配置",
                subtitle = "从现有订阅选择节点组合",
                onClick = { onTypeSelected(ProfileImportType.Custom) }
            )
        }
    }
}

@Composable
private fun ImportLoadingDialog(message: String, onCancel: () -> Unit = {}) {
    var displayMessage = message
    val progress = remember(message) {
        val regex = Regex(".*?\\((\\d+)/(\\d+)\\).*")
        val match = regex.find(message)
        if (match != null) {
            val (current, total) = match.destructured
            val totalFloat = total.toFloat()
            if (totalFloat > 0) {
                current.toFloat() / totalFloat
            } else {
                null
            }
        } else {
            null
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (progress != null) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            } else {
                androidx.compose.material3.CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ImportOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    StandardCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@Composable
private fun SubscriptionInputDialog(
    initialName: String = "",
    initialUrl: String = "",
    initialAutoUpdateInterval: Int = 0,
    initialDnsPreResolve: Boolean = false,
    initialDnsServer: String? = null,
    title: String = stringResource(R.string.profiles_add_subscription),
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, autoUpdateInterval: Int, dnsPreResolve: Boolean, dnsServer: String?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var autoUpdateEnabled by remember { mutableStateOf(initialAutoUpdateInterval > 0) }
    var autoUpdateMinutes by remember { mutableStateOf(if (initialAutoUpdateInterval > 0) initialAutoUpdateInterval.toString() else "60") }
    var dnsPreResolveEnabled by remember { mutableStateOf(initialDnsPreResolve) }
    var selectedDnsServer by remember { mutableStateOf(initialDnsServer ?: "https://cloudflare-dns.com/dns-query") }
    var dnsDropdownExpanded by remember { mutableStateOf(false) }

    val dnsServerOptions = listOf(
        "https://cloudflare-dns.com/dns-query" to stringResource(R.string.profiles_dns_server_cloudflare),
        "https://dns.google/dns-query" to stringResource(R.string.profiles_dns_server_google),
        "https://dns.alidns.com/dns-query" to stringResource(R.string.profiles_dns_server_alidns)
    )

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.profiles_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.profiles_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.profiles_auto_update),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                androidx.compose.material3.Switch(
                    checked = autoUpdateEnabled,
                    onCheckedChange = { autoUpdateEnabled = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            AnimatedVisibility(
                visible = autoUpdateEnabled,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 300)
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 300)
                )
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    androidx.compose.material3.OutlinedTextField(
                        value = autoUpdateMinutes,
                        onValueChange = { newValue ->

                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                autoUpdateMinutes = newValue
                            }
                        },
                        label = { Text(stringResource(R.string.profiles_auto_update_interval)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        supportingText = {
                            Text(
                                text = stringResource(R.string.profiles_auto_update_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.profiles_dns_preresolve),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.profiles_dns_preresolve_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = dnsPreResolveEnabled,
                    onCheckedChange = { dnsPreResolveEnabled = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            AnimatedVisibility(
                visible = dnsPreResolveEnabled,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 300)
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 300)
                )
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    androidx.compose.material3.OutlinedTextField(
                        value = dnsServerOptions.find { it.first == selectedDnsServer }?.second ?: selectedDnsServer,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text(stringResource(R.string.profiles_dns_server)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dnsDropdownExpanded = true },
                        shape = RoundedCornerShape(16.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            if (dnsDropdownExpanded) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { dnsDropdownExpanded = false }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(24.dp)
                            )
                            .padding(vertical = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.profiles_dns_server),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        dnsServerOptions.forEach { (url, label) ->
                            val isSelected = selectedDnsServer == url
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDnsServer = url
                                        dnsDropdownExpanded = false
                                    }
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val context = LocalContext.current

            Button(
                onClick = {

                    val isNodeLink = url.trim().let {
                        it.startsWith("vmess://") || it.startsWith("vless://") ||
                            it.startsWith("ss://") || it.startsWith("ssr://") ||
                            it.startsWith("trojan://") || it.startsWith("hysteria://") ||
                            it.startsWith("hysteria2://") || it.startsWith("hy2://") ||
                            it.startsWith("tuic://") || it.startsWith("bean://") ||
                            it.startsWith("wireguard://") || it.startsWith("ssh://") ||
                            it.startsWith("naive://") || it.startsWith("naive+https://")
                    }

                    if (isNodeLink) {
                        AppNotificationManager.showMessage(
                            context = context,
                            message = context.getString(R.string.profiles_subscription_node_warning),
                            duration = androidx.compose.material3.SnackbarDuration.Long
                        )
                        return@Button
                    }

                    if (name.contains("://")) {
                        AppNotificationManager.showMessage(context, context.getString(R.string.profiles_name_invalid))
                        return@Button
                    }

                    val finalInterval = if (autoUpdateEnabled) {
                        val minutes = autoUpdateMinutes.toIntOrNull() ?: 0
                        if (minutes < 15) {
                            AppNotificationManager.showMessage(
                                context,
                                context.getString(R.string.settings_update_interval_min)
                            )
                            return@Button
                        }
                        minutes
                    } else {
                        0
                    }

                    onConfirm(
                        name,
                        url,
                        finalInterval,
                        dnsPreResolveEnabled,
                        if (dnsPreResolveEnabled) selectedDnsServer else null
                    )
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text(stringResource(R.string.common_ok), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }

            Spacer(modifier = Modifier.height(8.dp))

            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}
