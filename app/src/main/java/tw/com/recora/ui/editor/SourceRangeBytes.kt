package tw.com.recora.ui.editor

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 來源檔在一段時間範圍內的實際資料量，「不重新編碼」時預估檔案大小用：
 * 匯出就是把這些樣本原封不動複製進 MP4，所以加總起來幾乎就是匯出檔的大小。
 * 記錄器的位元率隨畫面變化很大（Astro 同一支影片 2～12 Mbps），用整支檔案的平均會差到 ±15%。
 */
object SourceRangeBytes {
    /** 主畫面與聲音的樣本大小（bytes）；[audio] = 0 表示沒有聲音軌。 */
    data class Bytes(val video: Long, val audio: Long)

    /**
     * 量 [uri] 在 [startMs]～[endMs]（null = 到結尾）之間的樣本大小。
     * 畫面從起點前一個關鍵影格算起（不重新編碼時就是從那裡開始複製，見 [ClipExporter]）。讀不到回 null。
     * TS（Mufu）自己解析（見 [TsRangeBytes]），要從頭掃到終點，1 分鐘的檔案約零點幾秒；
     * 其他（Astro、官方 App 的 MP4）用 MediaExtractor，有索引很快。可以取消。
     */
    suspend fun measure(context: Context, uri: String, startMs: Long, endMs: Long?): Bytes? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val head = resolver.openInputStream(Uri.parse(uri))?.use { it.readNBytes(TS_SNIFF_BYTES) } ?: return@withContext null
            if (TsRangeBytes.looksLikeTs(head)) {
                val job = coroutineContext
                resolver.openInputStream(Uri.parse(uri))?.buffered(1 shl 20)?.use { input ->
                    TsRangeBytes.count(input, startMs, endMs) { !job.isActive }
                }?.let { (video, audio) -> Bytes(video, audio) }
            } else {
                measureWithExtractor(context, uri, startMs, endMs)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }.also { coroutineContext.ensureActive() }
    }

    private suspend fun measureWithExtractor(context: Context, uri: String, startMs: Long, endMs: Long?): Bytes? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val videos = formats.indices.filter { formats[it].getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            if (videos.isEmpty()) return null
            val audio = formats.indices.firstOrNull { formats[it].getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            return Bytes(
                video = videos.maxOf { sumTrack(extractor, it, startMs, endMs) },
                audio = audio?.let { sumTrack(extractor, it, startMs, endMs) } ?: 0L,
            )
        } finally {
            extractor.release()
        }
    }

    private const val TS_SNIFF_BYTES = 376

    /**
     * 一個軌在範圍內的樣本大小總和。時間以這個軌第一個樣本為 0
     * （TS 的時間戳不是從 0 開始；播放器與剪輯也都是這樣換算的）。
     */
    private suspend fun sumTrack(extractor: MediaExtractor, track: Int, startMs: Long, endMs: Long?): Long {
        extractor.selectTrack(track)
        try {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val baseUs = extractor.sampleTime.takeIf { it >= 0 } ?: return 0L
            extractor.seekTo(baseUs + startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = endMs?.let { baseUs + it * 1000 }
            var total = 0L
            var count = 0
            while (true) {
                val t = extractor.sampleTime
                if (t < 0 || (endUs != null && t >= endUs)) break
                total += extractor.sampleSize.coerceAtLeast(0)
                extractor.advance()
                if (++count % 200 == 0) coroutineContext.ensureActive()
            }
            return total
        } finally {
            extractor.unselectTrack(track)
        }
    }
}
