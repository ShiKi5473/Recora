package tw.com.recora.ui.player

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.com.recora.data.gps.GpsTrack
import tw.com.recora.data.gps.Gpx
import java.io.File

/** 分享用的 GPX 暫存檔保留多久。 */
private const val GPX_KEEP_MS = 60 * 60 * 1000L

/**
 * 整段的 GPS 軌跡寫成 GPX，存在快取的 gpx/（FileProvider 只開放這裡），回傳可以分享的網址；沒有定位或寫不出來回 null。
 * 只清掉 1 小時前的舊檔：雲端硬碟等會在背景晚一點才讀，剛分享的不能馬上刪。
 */
suspend fun writeGpxForShare(context: Context, name: String, track: GpsTrack): Uri? = withContext(Dispatchers.IO) {
    runCatching {
        val text = Gpx.write(name, track) ?: return@runCatching null
        val dir = File(context.cacheDir, "gpx").apply { mkdirs() }
        val expired = System.currentTimeMillis() - GPX_KEEP_MS
        dir.listFiles()?.filter { it.lastModified() < expired }?.forEach { it.delete() }
        val file = File(dir, "$name.gpx").apply { writeText(text) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.getOrNull()
}
