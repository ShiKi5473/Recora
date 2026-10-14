package tw.com.recora.ui.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import tw.com.recora.ui.player.dashcamMediaSourceFactory
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 匯出的一段來源；[startMs]／[endMs] 為 null 表示從頭／到尾。 */
data class ExportPiece(val uri: String, val startMs: Long? = null, val endMs: Long? = null)

/**
 * 一次匯出：[pieces] 依序接成一支影片，每段可以各自剪頭尾。
 * [videoMimeType] = null 不重新編碼（直接複製原本的畫面與聲音，[outputHeight]、[bitrate] 不使用）；
 * [outputHeight] = null 維持原始解析度；[bitrate] = null 用編碼器預設的位元率。
 */
data class ExportRequest(
    val pieces: List<ExportPiece>,
    val removeAudio: Boolean,
    val videoMimeType: String? = MimeTypes.VIDEO_H264,
    val outputHeight: Int? = null,
    val bitrate: Int? = null,
) {
    val transmux: Boolean get() = videoMimeType == null
}

/**
 * 用 Media3 Transformer 把（TS）影片剪一段、轉成 MP4，可去除聲音；可以重新編碼，也可以直接複製原本的畫面（不重新編碼）。
 * 記錄器的 TS 內有 H.265 主串流與 H.264 子串流，Transformer 預設的軌道選擇會選畫質最好的那一軌。
 * 讀檔用與播放器相同的 TS 設定（[dashcamMediaSourceFactory]），否則起點不在 0 秒的剪輯會失敗。
 */
@OptIn(UnstableApi::class)
class ClipExporter(private val context: Context, http: OkHttpClient) {
    private val handler = Handler(Looper.getMainLooper())
    private val assetLoaders = DefaultAssetLoaderFactory(
        context,
        DefaultDecoderFactory.Builder(context).build(),
        Clock.DEFAULT,
        dashcamMediaSourceFactory(context, http, streaming = false),
        DataSourceBitmapLoader.Builder(context).build(),
    )

    /**
     * 匯出到 cacheDir 的暫存檔並回傳，呼叫端存進相簿後自行刪除。
     * 協程被取消或匯出失敗時，暫存檔一併刪除（Transformer.cancel() 本身不會刪）。必須在主執行緒呼叫。
     */
    suspend fun export(request: ExportRequest, onProgress: (Int) -> Unit): File {
        val first = request.pieces.first()
        if (!request.transmux || request.pieces.size == 1 || (first.startMs ?: 0L) == 0L) return exportOnce(request, onProgress)
        // 不重新編碼又跨段、第一段剪了頭：Media3 只有單一來源才能用 edit list 剪頭，多段時起點不在關鍵影格就會改成重新編碼。
        // 所以先把第一段單獨匯出（edit list 剪頭），再跟後面幾段（都從 0 秒開始）接起來；兩次都只是複製，很快
        val head = exportOnce(request.copy(pieces = listOf(first))) { p -> onProgress(p / 3) }
        try {
            val rest = request.copy(pieces = listOf(ExportPiece(Uri.fromFile(head).toString())) + request.pieces.drop(1))
            return exportOnce(rest) { p -> onProgress(33 + p * 2 / 3) }
        } finally {
            head.delete()
        }
    }

    private suspend fun exportOnce(request: ExportRequest, onProgress: (Int) -> Unit): File {
        val output = File(context.cacheDir, "$TEMP_PREFIX${System.nanoTime()}.mp4")
        val items = request.pieces.map { piece ->
            EditedMediaItem.Builder(mediaItem(piece)).setRemoveAudio(request.removeAudio).build()
        }
        // 舊的建構子會依來源判斷有哪些軌；新的 withAudioAndVideoFrom 要先講好，但後鏡頭的 TS 沒有聲音
        @Suppress("DEPRECATION")
        val sequence = EditedMediaItemSequence.Builder(items).build()
        val composition = Composition.Builder(sequence)
            .apply {
                if (request.transmux) {
                    // 跨段時有好幾段來源，不明講的話 Transformer 一律重新編碼
                    setTransmuxVideo(true)
                    setTransmuxAudio(true)
                } else {
                    request.outputHeight?.let { setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(it)))) }
                }
            }
            .build()
        return suspendCancellableCoroutine { cont ->
            val holder = ProgressHolder()
            lateinit var poll: Runnable
            val transformer = Transformer.Builder(context)
                .setAssetLoaderFactory(assetLoaders)
                .apply {
                    val mime = request.videoMimeType
                    if (mime != null) {
                        setVideoMimeType(mime)
                    } else {
                        // 起點不在關鍵影格時，Transformer 預設會改成重新編碼；開這個改成從前一個關鍵影格開始複製，
                        // 再用 MP4 的 edit list 標出真正的起點，播放時仍然從選的那一格開始（記錄器約每秒一個關鍵影格）
                        experimentalSetMp4EditListTrimEnabled(true)
                    }
                    request.bitrate?.takeIf { !request.transmux }?.let { bps ->
                        setEncoderFactory(
                            DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bps).build())
                                .build(),
                        )
                    }
                }
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        cont.resume(output)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        output.delete()
                        cont.resumeWithException(exportException)
                    }
                })
                .build()
            poll = object : Runnable {
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    handler.postDelayed(this, 300)
                }
            }
            cont.invokeOnCancellation {
                // Transformer 只能在主執行緒操作；已經完成的話 cancel() 不做事，輸出檔也不再需要
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                    output.delete()
                }
            }
            transformer.start(composition, output.absolutePath)
            handler.post(poll)
        }
    }

    private fun mediaItem(piece: ExportPiece): MediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
        piece.startMs?.let { clipping.setStartPositionMs(it) }
        piece.endMs?.let { clipping.setEndPositionMs(it) }
        return MediaItem.Builder().setUri(piece.uri).setClippingConfiguration(clipping.build()).build()
    }

    companion object {
        private const val TEMP_PREFIX = "export_"

        /** 手機有沒有 H.265 編碼器（沒有就只能匯出 H.264）。 */
        val hevcEncoderAvailable: Boolean by lazy { EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H265).isNotEmpty() }

        /** 刪掉上次沒收完的暫存檔（例如匯出中 App 被關掉）。App 啟動時在背景執行緒呼叫。 */
        fun cleanStale(context: Context) {
            context.cacheDir.listFiles { f -> f.name.startsWith(TEMP_PREFIX) && f.name.endsWith(".mp4") }
                ?.forEach { it.delete() }
        }
    }
}
