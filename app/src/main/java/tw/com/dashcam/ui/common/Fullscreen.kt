package tw.com.dashcam.ui.common

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** 目前是否有畫面在全螢幕（AppRoot 依此隱藏底部分頁列）。 */
object Immersive {
    var active by mutableStateOf(false)
}

/**
 * 全螢幕：轉橫向並隱藏狀態列 / 導覽列，按返回鍵先離開全螢幕。
 * 呼叫端離開畫面時自動還原。
 */
@Composable
fun FullscreenEffect(fullscreen: Boolean, onExit: () -> Unit) {
    val activity = LocalContext.current as? Activity
    LaunchedEffect(fullscreen) {
        activity?.applyFullscreen(fullscreen)
        Immersive.active = fullscreen
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.applyFullscreen(false)
            Immersive.active = false
        }
    }
    BackHandler(enabled = fullscreen, onBack = onExit)
}

private fun Activity.applyFullscreen(on: Boolean) {
    requestedOrientation = if (on) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (on) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
