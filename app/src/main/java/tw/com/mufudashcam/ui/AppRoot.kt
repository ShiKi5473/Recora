package tw.com.mufudashcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.MediaKind
import tw.com.mufudashcam.ui.browser.BrowserScreen
import tw.com.mufudashcam.ui.common.Immersive
import tw.com.mufudashcam.ui.device.DeviceScreen
import tw.com.mufudashcam.ui.downloads.DownloadsScreen
import tw.com.mufudashcam.ui.editor.EditorScreen
import tw.com.mufudashcam.ui.library.LibraryScreen
import tw.com.mufudashcam.ui.live.LiveScreen
import tw.com.mufudashcam.ui.player.PlayerScreen
import tw.com.mufudashcam.ui.settings.SettingsScreen

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem("files", "檔案", Icons.Filled.VideoLibrary),
    TabItem("live", "即時", Icons.Filled.Videocam),
    TabItem("downloads", "下載", Icons.Filled.Download),
    TabItem("library", "相簿", Icons.Filled.PhotoLibrary),
    TabItem("device", "裝置", Icons.Filled.Settings),
)

private const val PLAYER_ROUTE = "player?url={url}&title={title}"

private fun playerRoute(url: String, title: String) =
    "player?url=${Uri.encode(url)}&title=${Uri.encode(title)}"

private const val EDITOR_ROUTE = "editor?uri={uri}&title={title}"

private fun editorRoute(uri: String, title: String) =
    "editor?uri=${Uri.encode(uri)}&title=${Uri.encode(title)}"

@Composable
fun AppRoot(container: AppContainer) {
    RequestRuntimePermissions()

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tasks by container.downloads.tasks.collectAsStateWithLifecycle()
    val activeCount = tasks.count { it.isActive }
    val onPlay: (String, String) -> Unit = { url, title -> nav.navigate(playerRoute(url, title)) }
    val onEdit: (String, String) -> Unit = { uri, title -> nav.navigate(editorRoute(uri, title)) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (TABS.any { it.route == route } && !Immersive.active) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.route == "downloads" && activeCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$activeCount") } }) {
                                        Icon(tab.icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
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
            startDestination = "files",
            modifier = Modifier.padding(inner).consumeWindowInsets(inner),
        ) {
            composable("files") { BrowserScreen(container, onPlay) }
            composable("live") { LiveScreen(container) }
            composable("downloads") { DownloadsScreen(container, onPlay, onEdit) }
            composable("library") { LibraryScreen(container, onPlay, onEdit) }
            composable("device") { DeviceScreen(container, onOpenSettings = { nav.navigate("settings") }) }
            composable("settings") { SettingsScreen(container, onBack = { nav.popBackStack() }) }
            composable(
                PLAYER_ROUTE,
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                PlayerScreen(
                    container = container,
                    url = entry.arguments?.getString("url").orEmpty(),
                    title = entry.arguments?.getString("title").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onEdit = onEdit,
                )
            }
            composable(
                EDITOR_ROUTE,
                arguments = listOf(
                    navArgument("uri") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                EditorScreen(
                    container = container,
                    uri = entry.arguments?.getString("uri").orEmpty(),
                    title = entry.arguments?.getString("title").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenLibrary = {
                        container.libraryTabRequest.value = MediaKind.Edited
                        nav.navigate("library") {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
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
