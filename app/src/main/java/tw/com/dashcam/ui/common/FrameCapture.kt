package tw.com.dashcam.ui.common

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.com.dashcam.AppContainer
import tw.com.dashcam.data.DashcamConfig
import tw.com.dashcam.data.LocalLibrary
import kotlin.math.roundToInt

/**
 * 截下播放器目前的畫面（原始解析度），存成 JPEG 到 Pictures/Dashcam。
 * [crop] = 只存這一部分（0..1 的比例，例如放大時看得到的範圍），null = 整張。
 * 檔名用 [LocalLibrary.screenshotName]，相簿才能算出畫面的實際拍攝時間。
 * 播放器與剪輯頁共用。
 */
@OptIn(UnstableApi::class)
fun captureFrame(
    container: AppContainer,
    playerView: PlayerView?,
    player: Player,
    videoName: String,
    scope: CoroutineScope,
    crop: Rect? = null,
    onMessage: (String) -> Unit,
) {
    val surface = playerView?.videoSurfaceView
    val video = player.videoSize
    if (surface == null || video.width <= 0 || video.height <= 0) {
        onMessage("畫面還沒準備好")
        return
    }
    val name = LocalLibrary.screenshotName(videoName, player.currentPosition)
    fun save(frame: Bitmap) = scope.launch {
        val message = try {
            withContext(Dispatchers.IO) {
                val bitmap = crop?.let { frame.cropTo(it) } ?: frame
                container.sink.saveJpeg(bitmap, name)
            }
            container.libraryChanged.tryEmit(Unit)
            "已存${if (crop != null) "放大範圍的" else ""}截圖 $name 到 Pictures/${DashcamConfig.LOCAL_DIR}"
        } catch (e: Exception) {
            "截圖儲存失敗：${e.message}"
        }
        onMessage(message)
    }
    when (surface) {
        // 播放畫面（可縮放）用 TextureView：取的是整張原始畫面，不受目前的放大範圍影響
        is TextureView -> surface.getBitmap(video.width, video.height)?.let(::save) ?: onMessage("截圖失敗")
        is SurfaceView -> {
            val bitmap = Bitmap.createBitmap(video.width, video.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(surface, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) save(bitmap) else onMessage("截圖失敗（$result）")
            }, Handler(Looper.getMainLooper()))
        }
        else -> onMessage("畫面還沒準備好")
    }
}

/** 依比例（0..1）裁出一部分，至少 1 個像素。 */
private fun Bitmap.cropTo(r: Rect): Bitmap {
    val x = (r.left * width).roundToInt().coerceIn(0, width - 1)
    val y = (r.top * height).roundToInt().coerceIn(0, height - 1)
    val w = (r.width * width).roundToInt().coerceIn(1, width - x)
    val h = (r.height * height).roundToInt().coerceIn(1, height - y)
    return Bitmap.createBitmap(this, x, y, w, h)
}
