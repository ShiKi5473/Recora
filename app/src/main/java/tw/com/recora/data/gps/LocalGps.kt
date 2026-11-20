package tw.com.recora.data.gps

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** 讀手機上影片（content://）的 GPS：只讀檔頭的 `moov` 與每秒一小段，1 分鐘的檔約 40 KB。 */
object LocalGps {
    /** 沒有 GPS、不是 MP4 或讀不到都回 null。 */
    suspend fun read(context: Context, uri: String): GpsTrack? = withContext(Dispatchers.IO) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return@withContext null
            ParcelFileDescriptor.AutoCloseInputStream(pfd).channel.use { channel ->
                NovatekGps.read { offset, length -> channel.readAt(offset, length) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    private fun FileChannel.readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        return buf.array().copyOf(buf.position())
    }
}
