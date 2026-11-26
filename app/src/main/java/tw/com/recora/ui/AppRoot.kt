package tw.com.recora.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tw.com.recora.AppContainer
import tw.com.recora.data.PlayItem
import tw.com.recora.ui.browser.BrowserScreen
import tw.com.recora.ui.common.Immersive
import tw.com.recora.ui.device.DeviceScreen
import tw.com.recora.ui.downloads.DownloadsScreen
import tw.com.recora.ui.editor.EditorScreen
import tw.com.recora.ui.library.LibraryScreen
import tw.com.recora.ui.live.LiveScreen
import tw.com.recora.ui.player.PlayerScreen
import tw.com.recora.ui.settings.SettingsScreen
import tw.com.recora.ui.update.AppUpdateDialog
import tw.com.recora.ui.update.launchInstallConfirmIntent
import tw.com.recora.ui.update.openInstallPermissionSettings
import tw.com.recora.update.AppUpdater

private data class TabItem(val route: Any, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    TabItem(FilesRoute, "檔案", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    TabItem(LiveRoute, "即時", Icons.Outlined.Videocam, Icons.Filled.Videocam),
    TabItem(DownloadsRoute, "下載", Icons.Outlined.Download, Icons.Filled.Download),
    TabItem(LibraryRoute, "相簿", Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
    TabItem(DeviceRoute, "裝置", Icons.Outlined.SdCard, Icons.Filled.SdCard),
)

/** 開播放畫面：[queue] = 左右滑動切換的清單（含這一段；空的就只播這一段）。 */
typealias OnPlay = (url: String, title: String, queue: List<PlayItem>) -> Unit

@Composable
fun AppRoot(container: AppContainer) {
    RequestRuntimePermissions()

    val context = LocalContext.current
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val appUpdate by AppUpdater.state.collectAsStateWithLifecycle()
    val transferActive by container.downloads.active.collectAsStateWithLifecycle()
    var hasInstallPermission by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    LifecycleResumeEffect(Unit) {
        hasInstallPermission = context.packageManager.canRequestPackageInstalls()
        onPauseOrDispose { }
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    // 只取進行中的數量：下載進度每 300 ms 更新一次 tasks，整個 AppRoot（Scaffold、導覽列）不必跟著重組
    val initialActive = remember(container) { container.downloads.tasks.value.count { it.isActive } }
    val activeCount by remember(container) {
        container.downloads.tasks.map { tasks -> tasks.count { it.isActive } }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = initialActive)
    // 清單交給 PlayQueues，導覽參數只帶編號
    val onPlay: OnPlay = { url, title, queue ->
        nav.navigate(PlayerRoute(url, title, if (queue.isEmpty()) 0L else container.playQueues.put(queue)))
    }
    val onEdit: (String, String) -> Unit = { uri, title -> nav.navigate(EditorRoute(uri, title)) }
    // debug：校正腳本指定的剪輯匯出；先關掉之前的剪輯畫面（釋放它的播放器），EditorScreen 自己接手這個工作
    LaunchedEffect(Unit) {
        container.calibrationJob.collect { job ->
            if (job != null) {
                nav.navigate(EditorRoute(job.uri, job.title)) { popUpTo(nav.graph.findStartDestination().id) }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = TABS.any { destination?.hasRoute(it.route::class) == true } && !Immersive.active,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = destination?.hasRoute(tab.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                val icon = if (selected) tab.selectedIcon else tab.icon
                                if (tab.route == DownloadsRoute && activeCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$activeCount") } }) {
                                        Icon(icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(icon, contentDescription = null)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = FilesRoute,
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) },
            modifier = Modifier.padding(inner).consumeWindowInsets(inner),
        ) {
            composable<FilesRoute> { BrowserScreen(container, onPlay) }
            composable<LiveRoute> { LiveScreen(container) }
            composable<DownloadsRoute> { DownloadsScreen(container, onPlay, onEdit) }
            composable<LibraryRoute> { LibraryScreen(container, onPlay, onEdit) }
            composable<DeviceRoute> { DeviceScreen(container, onOpenSettings = { nav.navigate(SettingsRoute) }) }
            composable<SettingsRoute>(
                enterTransition = { pushIn() },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { popOut() },
            ) { SettingsScreen(container, onBack = { nav.popBackStack() }) }
            composable<PlayerRoute>(
                enterTransition = { pushIn() },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { popOut() },
            ) { entry ->
                val route = entry.toRoute<PlayerRoute>()
                PlayerScreen(
                    container = container,
                    url = route.url,
                    title = route.title,
                    queueId = route.queue,
                    onBack = { nav.popBackStack() },
                    onEdit = onEdit,
                )
            }
            composable<EditorRoute>(
                enterTransition = { pushIn() },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { popOut() },
            ) { entry ->
                val route = entry.toRoute<EditorRoute>()
                EditorScreen(
                    container = container,
                    uri = route.uri,
                    title = route.title,
                    onBack = { nav.popBackStack() },
                    // 匯出完直接播剪好的影片（只播這一支）；剪輯畫面從返回堆疊移除，返回時回到開剪輯之前的畫面
                    onOpenExported = { exportedUri, exportedTitle ->
                        nav.navigate(PlayerRoute(exportedUri, exportedTitle)) {
                            popUpTo<EditorRoute> { inclusive = true }
                        }
                    },
                )
            }
        }
    }

    AppUpdateDialog(
        state = appUpdate,
        transferActive = transferActive,
        autoUpdateEnabled = settings.appAutoUpdate,
        hasInstallPermission = hasInstallPermission,
        onDownload = { rel -> AppUpdater.startDownloadAndInstall(context, rel, showPrompt = true) },
        onInstall = { rel, apk -> AppUpdater.installDownloaded(context, rel, apk, showPrompt = true) },
        onCancelDownload = { AppUpdater.cancelDownload(context) },
        onOpenInstallSettings = { openInstallPermissionSettings(context) },
        onRelaunchConfirm = { confirmIntent -> launchInstallConfirmIntent(context, confirmIntent) },
        onEnableAutoUpdate = { enabled -> container.settings.launchUpdate { it.copy(appAutoUpdate = enabled) } },
        onSkipVersion = { ver ->
            container.settings.launchUpdate { it.copy(skippedAppVersion = ver) }
            AppUpdater.dismissPrompt()
        },
        onDismiss = AppUpdater::dismissPrompt,
    )
}


/** 通知（下載進度）與附近 Wi-Fi 裝置（區域網路存取）權限，第一次開啟時請求一次。 */
@Composable
private fun RequestRuntimePermissions() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val missing = listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
}

/** 進入詳細頁（播放、剪輯、設定）：淡入加上輕微的由右滑入。 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushIn() =
    fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popOut() =
    fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 12 }
