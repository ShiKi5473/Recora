package tw.com.mufudashcam.ui.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * 用 Media3 Transformer 把（TS）影片剪一段、轉成 H.264 MP4，可去除聲音。
 * 記錄器的 TS 內有 H.265 主串流與 H.264 子串流，Transformer 會選畫質最好的那一軌。
 * 必須在主執行緒呼叫。
 */
@OptIn(UnstableApi::class)
class ClipExporter(private val context: Context) {
    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val holder = ProgressHolder()

    fun start(
        source: Uri,
        startMs: Long,
        endMs: Long,
        removeAudio: Boolean,
        output: File,
        onProgress: (Int) -> Unit,
        onDone: (Result<File>) -> Unit,
    ) {
        cancel()
        output.delete()
        val item = MediaItem.Builder()
            .setUri(source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build(),
            )
            .build()
        val edited = EditedMediaItem.Builder(item).setRemoveAudio(removeAudio).build()
        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264) // 相容性最好
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPolling()
                    transformer = null
                    onDone(Result.success(output))
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    stopPolling()
                    transformer = null
                    output.delete()
                    onDone(Result.failure(exportException))
                }
            })
            .build()
        transformer = t
        t.start(edited, output.absolutePath)
        poll = object : Runnable {
            override fun run() {
                val current = transformer ?: return
                if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                handler.postDelayed(this, 300)
            }
        }.also { handler.post(it) }
    }

    fun cancel() {
        stopPolling()
        transformer?.cancel()
        transformer = null
    }

    private var poll: Runnable? = null

    private fun stopPolling() {
        poll?.let(handler::removeCallbacks)
        poll = null
    }
}
