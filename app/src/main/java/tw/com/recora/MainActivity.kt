package tw.com.recora

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tw.com.recora.data.AppSettings
import tw.com.recora.data.DashcamConfig
import tw.com.recora.data.DiagLog
import tw.com.recora.data.ThemeMode
import tw.com.recora.data.api.RtspProbe
import tw.com.recora.data.enumOr
import tw.com.recora.ui.AppRoot
import tw.com.recora.ui.editor.CalibrationJob
import tw.com.recora.ui.theme.RecoraTheme
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as RecoraApp).container
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
            RecoraTheme(dark = dark, dynamicColor = settings.dynamicColor) {
                AppRoot(container)
            }
        }
    }

    /**
     * 退到背景：畫面拿的停錄租約在 ON_STOP 已經歸還（ComponentActivity 先分派 ON_STOP 才執行這裡），
     * Astro 不等 10 秒、馬上恢復錄影，之後 App 被滑掉關閉也不會停在回放模式。
     * 佇列裡還有這台記錄器的檔案就不動：下載馬上又要停錄，恢復了只會多錄一小段。
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        val c = (application as RecoraApp).container
        c.appScope.launch {
            val brand = c.connection.boundBrand ?: return@launch
            if (!c.connection.isConnected) return@launch
            if (c.downloads.tasks.value.any { it.isActive && it.file.brand == brand }) return@launch
            try {
                c.api.onAppBackground()
            } catch (e: IOException) {
                DiagLog.log("退到背景時恢復錄影失敗：${e.message}")
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
     * `adb shell am start -n tw.com.recora/.MainActivity --es host_override 127.0.0.1:8080`（先 `adb reverse tcp:8080 tcp:8080`）
     * （傳空字串則取消覆寫）。
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val c = (application as RecoraApp).container
        // adb shell am start -n tw.com.recora/.MainActivity --ez probe_rtsp true
        if (intent?.getBooleanExtra(EXTRA_PROBE_RTSP, false) == true) {
            c.appScope.launch {
                val host = c.api.host.substringBefore(':')
                for (path in listOf("av4", "av5")) {
                    RtspProbe.describe(host, DashcamConfig.RTSP_PORT, path)
                }
            }
        }
        if (intent != null) handleCalibrationIntent(intent)
        val host = intent?.getStringExtra(EXTRA_HOST_OVERRIDE) ?: return
        lifecycleScope.launch {
            c.settings.update { it.copy(hostOverride = host.trim()) }
            c.connection.disconnect()
            if (host.isNotBlank()) c.connection.connect()
        }
    }

    /**
     * debug 用：校正匯出檔案大小的預估（tools/calibration/calibrate.sh）。先改匯出設定，再開剪輯畫面匯出相簿裡的一支影片：
     * `adb shell am start -n tw.com.recora/.MainActivity --es cal_codec H264 --es cal_res Original --es cal_quality Standard
     *  --es cal_file 20261013090104_004646F.MP4 --el cal_start_ms 20000 --el cal_len_ms 30000 --ez cal_mute false`
     * 設定值是 enum 名稱；沒有 cal_file 時只改設定。結果寫在 logcat tag RecoraExport。
     */
    private fun handleCalibrationIntent(intent: Intent) {
        val c = (application as RecoraApp).container
        val codec = intent.getStringExtra(EXTRA_CAL_CODEC)
        val file = intent.getStringExtra(EXTRA_CAL_FILE)
        if (codec == null && file == null) return
        lifecycleScope.launch {
            if (codec != null) {
                var target: AppSettings? = null
                c.settings.update {
                    it.copy(
                        exportCodec = enumOr(codec, it.exportCodec),
                        exportResolution = enumOr(intent.getStringExtra(EXTRA_CAL_RES), it.exportResolution),
                        exportQuality = enumOr(intent.getStringExtra(EXTRA_CAL_QUALITY), it.exportQuality),
                    ).also { s -> target = s }
                }
                // 寫入後 state 要等 DataStore 發出新值才更新；等到了再記錄、再開剪輯畫面
                val t = target ?: return@launch
                val s = withTimeoutOrNull(5_000) {
                    c.settings.state.first {
                        it.exportCodec == t.exportCodec && it.exportResolution == t.exportResolution && it.exportQuality == t.exportQuality
                    }
                } ?: c.settings.state.value
                Log.i(CAL_TAG, "settings codec=${s.exportCodec} res=${s.exportResolution} quality=${s.exportQuality}")
            }
            if (file == null) return@launch
            val uri = withContext(Dispatchers.IO) { c.sink.findVideo(file) }
            if (uri == null) {
                Log.i(CAL_TAG, "done saved=0 failure=找不到 $file")
                return@launch
            }
            c.calibrationJob.value = CalibrationJob(
                id = SystemClock.elapsedRealtimeNanos(),
                uri = uri.toString(),
                title = file,
                startMs = intent.getLongExtra(EXTRA_CAL_START_MS, 0L),
                lengthMs = intent.getLongExtra(EXTRA_CAL_LEN_MS, 30_000L),
                removeAudio = intent.getBooleanExtra(EXTRA_CAL_MUTE, false),
            )
        }
    }

    private companion object {
        const val EXTRA_HOST_OVERRIDE = "host_override"
        const val EXTRA_PROBE_RTSP = "probe_rtsp"
        const val EXTRA_CAL_FILE = "cal_file"
        const val EXTRA_CAL_START_MS = "cal_start_ms"
        const val EXTRA_CAL_LEN_MS = "cal_len_ms"
        const val EXTRA_CAL_CODEC = "cal_codec"
        const val EXTRA_CAL_RES = "cal_res"
        const val EXTRA_CAL_QUALITY = "cal_quality"
        const val EXTRA_CAL_MUTE = "cal_mute"
        const val CAL_TAG = "RecoraExport"

        // 與 enableEdgeToEdge() 預設的導覽列遮罩相同
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
