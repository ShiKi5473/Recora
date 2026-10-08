package tw.com.mufudashcam.ui.common

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.DashcamConfig
import tw.com.mufudashcam.data.LocalLibrary

/**
 * 截下播放器目前的畫面（原始解析度），存成 JPEG 到 Pictures/MufuDashcam。
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
    onMessage: (String) -> Unit,
) {
    val surface = playerView?.videoSurfaceView as? SurfaceView
    val video = player.videoSize
    if (surface == null || video.width <= 0 || video.height <= 0) {
        onMessage("畫面還沒準備好")
        return
    }
    val bitmap = Bitmap.createBitmap(video.width, video.height, Bitmap.Config.ARGB_8888)
    val name = LocalLibrary.screenshotName(videoName, player.currentPosition)
    PixelCopy.request(surface, bitmap, { result ->
        scope.launch {
            val message = if (result == PixelCopy.SUCCESS) {
                try {
                    withContext(Dispatchers.IO) { container.sink.saveJpeg(bitmap, name) }
                    container.libraryChanged.tryEmit(Unit)
                    "已存截圖 $name 到 Pictures/${DashcamConfig.LOCAL_DIR}"
                } catch (e: Exception) {
                    "截圖儲存失敗：${e.message}"
                }
            } else {
                "截圖失敗（$result）"
            }
            onMessage(message)
        }
    }, Handler(Looper.getMainLooper()))
}
