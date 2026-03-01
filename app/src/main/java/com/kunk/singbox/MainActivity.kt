package com.kunk.singbox

import android.content.Intent
import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.viewmodel.DashboardViewModel
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.AppLanguage
import com.kunk.singbox.utils.LocaleHelper
import com.kunk.singbox.utils.DeepLinkHandler
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.ui.components.AppNavBar
import com.kunk.singbox.ui.navigation.AppNavigation
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.SingBoxTheme
import android.content.ComponentName
import android.service.quicksettings.TileService
import androidx.work.WorkManager
import com.kunk.singbox.worker.RuleSetUpdateWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import android.app.Activity
import com.kunk.singbox.ui.scanner.QrScannerActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun attachBaseContext(newBase: Context) {

        val prefs = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val languageName = prefs.getString("app_language_cache", null)
        val language = if (languageName != null) {
            try {
                AppLanguage.valueOf(languageName)
            } catch (e: Exception) {
                AppLanguage.SYSTEM
            }
        } else {
            AppLanguage.SYSTEM
        }

        val context = LocaleHelper.wrap(newBase, language)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            SingBoxApp()
        }

        cancelRuleSetUpdateWork()
    }

    private fun cancelRuleSetUpdateWork() {
        WorkManager.getInstance(this).cancelUniqueWork(RuleSetUpdateWorker.WORK_NAME)
    }
}

@Composable
fun SingBoxApp() {
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
        }
    )

    LaunchedEffect(Unit) {
        SingBoxRemote.ensureBound(context)
        // Best-effort: ask system to refresh QS tile state after app process restarts/force-stops.
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, VpnTileService::class.java))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)

            if (permission != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsState(initial = null)
    val dashboardViewModel: DashboardViewModel = viewModel()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        dashboardViewModel.refreshState()
    }

    LaunchedEffect(settings?.appLanguage) {
        settings?.appLanguage?.let { language ->
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit().putString("app_language_cache", language.name).apply()
        }
    }

    val isVpnRunningForUpdate by SingBoxRemote.isRunning.collectAsState()
    var updateChecked by remember { mutableStateOf(false) }

    LaunchedEffect(settings?.autoCheckUpdate, isVpnRunningForUpdate) {
        if (settings?.autoCheckUpdate != true || updateChecked) return@LaunchedEffect

        if (isVpnRunningForUpdate) {

            kotlinx.coroutines.delay(1000L)
            updateChecked = true
            com.kunk.singbox.utils.AppUpdateChecker.checkAndNotify(context)
        }
    }

    LaunchedEffect(settings?.autoCheckUpdate) {
        if (settings?.autoCheckUpdate != true) return@LaunchedEffect
        kotlinx.coroutines.delay(10000L)
        if (!updateChecked) {
            updateChecked = true
            com.kunk.singbox.utils.AppUpdateChecker.checkAndNotify(context)
        }
    }

    // Handle App Shortcuts - need navController reference
    var pendingNavigation by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val activity = context as? Activity
        activity?.intent?.let { intent ->
            when (intent.action) {
                "com.kunk.singbox.action.SCAN" -> {
                    val scanIntent = android.content.Intent(context, QrScannerActivity::class.java)
                    context.startActivity(scanIntent)
                    intent.action = null
                }
                "com.kunk.singbox.action.SWITCH_NODE" -> {
                    pendingNavigation = "nodes"
                    intent.action = null
                }
                android.content.Intent.ACTION_VIEW -> {

                    intent.data?.let { uri ->
                        val scheme = uri.scheme
                        val host = uri.host

                        if ((scheme == "singbox" || scheme == "kunbox") && host == "install-config") {
                            val url = uri.getQueryParameter("url")
                            val name = uri.getQueryParameter("name") ?: "Imported Subscription"
                            val intervalStr = uri.getQueryParameter("interval")
                            val interval = intervalStr?.toIntOrNull() ?: 0

                            if (!url.isNullOrBlank()) {
                                DeepLinkHandler.setPendingSubscriptionImport(name, url, interval)

                                pendingNavigation = "profiles"
                            }
                        }
                    }
                    intent.data = null
                }
            }
        }
    }
    val connectionState by dashboardViewModel.connectionState.collectAsState()
    val isRunning by SingBoxRemote.isRunning.collectAsState()
    val isStarting by SingBoxRemote.isStarting.collectAsState()
    val manuallyStopped by SingBoxRemote.manuallyStopped.collectAsState()

    LaunchedEffect(isRunning, isStarting) {

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.kunk.singbox.utils.NetworkClient.clearConnectionPool()
        }
    }

    LaunchedEffect(settings?.autoConnect, connectionState) {
        if (settings?.autoConnect == true &&
            connectionState == ConnectionState.Idle &&
            !isRunning &&
            !isStarting &&
            !manuallyStopped
        ) {
            // Delay a bit to ensure everything is initialized
            delay(1000)
            if (connectionState == ConnectionState.Idle && !isRunning) {
                dashboardViewModel.toggleConnection()
            }
        }
    }

    LaunchedEffect(settings?.excludeFromRecent) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.appTasks?.forEach {
            it.setExcludeFromRecents(settings?.excludeFromRecent == true)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        SettingsRepository.restartRequiredEvents.collectLatest {

            if (!SingBoxRemote.isRunning.value && !SingBoxRemote.isStarting.value) return@collectLatest

            snackbarHostState.currentSnackbarData?.dismiss()

            snackbarHostState.showSnackbar(
                message = context.getString(R.string.settings_restart_needed),
                duration = SnackbarDuration.Short
            )
        }
    }

    val appTheme = settings?.appTheme ?: com.kunk.singbox.model.AppThemeMode.SYSTEM

    SingBoxTheme(appTheme = appTheme) {
        val navController = rememberNavController()

        // Handle pending navigation from App Shortcuts
        LaunchedEffect(pendingNavigation) {
            pendingNavigation?.let { route ->
                delay(100)
                navController.navigate(route) {
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
                pendingNavigation = null
            }
        }

        // Get current destination
        val navBackStackEntry = navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry.value?.destination?.route
        val showBottomBar = currentRoute in listOf(
            "dashboard", "nodes", "profiles", "settings"
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.padding(bottom = if (showBottomBar) 64.dp else 16.dp),
                        snackbar = { data ->
                            Surface(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .heightIn(min = 52.dp)
                                    .shadow(6.dp, RoundedCornerShape(12.dp)),
                                color = PureWhite,
                                contentColor = Color.Black,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = data.visuals.message,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = Color.Black,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Text(
                                        text = stringResource(R.string.main_restart),
                                        modifier = Modifier
                                            .heightIn(min = 24.dp)
                                            .clickable {
                                                data.dismiss()
                                                if (isRunning || isStarting) {
                                                    dashboardViewModel.restartVpn()
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF00C853)
                                    )
                                }
                            }
                        }
                    )
                },
                bottomBar = {
                    AnimatedVisibility(
                        visible = showBottomBar,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                        ) + expandVertically(
                            expandFrom = Alignment.Bottom,
                            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(durationMillis = 400)),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                        ) + shrinkVertically(
                            shrinkTowards = Alignment.Bottom,
                            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(durationMillis = 400))
                    ) {
                        AppNavBar(navController = navController)
                    }
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { innerPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding())
                ) {
                    AppNavigation(navController)
                }
            }
        }
    }
}
