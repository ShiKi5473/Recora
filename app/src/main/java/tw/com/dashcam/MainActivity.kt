package tw.com.dashcam

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tw.com.dashcam.data.DashcamConfig
import tw.com.dashcam.data.ThemeMode
import tw.com.dashcam.data.api.RtspProbe
import tw.com.dashcam.ui.AppRoot
import tw.com.dashcam.ui.theme.DashcamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as DashcamApp).container
        handleDebugIntent(intent)
        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // 狀態列／導覽列圖示的深淺跟著 App 的主題，而不是系統的
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose { }
            }
            DashcamTheme(dark = dark, dynamicColor = settings.dynamicColor) {
                AppRoot(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * debug 用：指定假記錄器位址，例如
     * `adb shell am start -n tw.com.dashcam/.MainActivity --es host_override 127.0.0.1:8080`（先 `adb reverse tcp:8080 tcp:8080`）
     * （傳空字串則取消覆寫）。
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val c = (application as DashcamApp).container
        // adb shell am start -n tw.com.dashcam/.MainActivity --ez probe_rtsp true
        if (intent?.getBooleanExtra(EXTRA_PROBE_RTSP, false) == true) {
            c.appScope.launch {
                val host = c.api.host.substringBefore(':')
                for (path in listOf("av4", "av5")) {
                    RtspProbe.describe(host, DashcamConfig.RTSP_PORT, path)
                }
            }
        }
        val host = intent?.getStringExtra(EXTRA_HOST_OVERRIDE) ?: return
        lifecycleScope.launch {
            c.settings.update { it.copy(hostOverride = host.trim()) }
            c.connection.disconnect()
            if (host.isNotBlank()) c.connection.connect()
        }
    }

    private companion object {
        const val EXTRA_HOST_OVERRIDE = "host_override"
        const val EXTRA_PROBE_RTSP = "probe_rtsp"

        // 與 enableEdgeToEdge() 預設的導覽列遮罩相同
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
