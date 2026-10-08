package tw.com.mufudashcam

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tw.com.mufudashcam.data.DashcamConfig
import tw.com.mufudashcam.data.api.RtspProbe
import tw.com.mufudashcam.ui.AppRoot
import tw.com.mufudashcam.ui.theme.MufuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MufuApp).container
        handleDebugIntent(intent)
        setContent {
            MufuTheme {
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
     * `adb shell am start -n tw.com.mufudashcam/.MainActivity --es host_override 127.0.0.1:8080`（先 `adb reverse tcp:8080 tcp:8080`）
     * （傳空字串則取消覆寫）。
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val c = (application as MufuApp).container
        // adb shell am start -n tw.com.mufudashcam/.MainActivity --ez probe_rtsp true
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
    }
}
