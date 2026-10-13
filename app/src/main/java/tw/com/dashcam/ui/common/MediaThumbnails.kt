package tw.com.dashcam.ui.common

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.pxOrElse
import java.io.IOException

/** 相簿裡的檔案（content://）的縮圖，給 Coil 當 model 用。 */
data class MediaThumb(val uri: String, val isVideo: Boolean)

/**
 * 用系統的縮圖快取（[android.content.ContentResolver.loadThumbnail]）取縮圖：
 * 系統產生過就直接讀，重開 App 不必再從 1440p 的 H.265 影片解出一格。
 * 系統給不出來時（例如格式不支援）影片退回自己取第 1 秒的畫面。
 */
class MediaThumbFetcher(private val data: MediaThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val uri = Uri.parse(data.uri)
        val width = options.size.width.pxOrElse { DEFAULT_PX }
        val height = options.size.height.pxOrElse { DEFAULT_PX }
        val bitmap = try {
            options.context.contentResolver.loadThumbnail(uri, Size(width, height), null)
        } catch (e: IOException) {
            if (!data.isVideo) throw e
            MediaMetadataRetriever().use { r ->
                r.setDataSource(options.context, uri)
                r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
            } ?: throw IOException("無法取得影片畫面", e)
        }
        return DrawableResult(bitmap.toDrawable(options.context.resources), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbFetcher(data, options)
    }

    /** 記憶體快取用的 key。 */
    class Key : Keyer<MediaThumb> {
        override fun key(data: MediaThumb, options: Options): String = "thumb:${data.uri}"
    }

    private companion object {
        const val DEFAULT_PX = 320
    }
}
