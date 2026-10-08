package tw.com.mufudashcam.download

import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tw.com.mufudashcam.data.net.DashcamOfflineException
import java.io.IOException
import java.io.OutputStream

/**
 * 串流下載 + 從目前位移續傳（GoAhead 支援 HTTP Range，API 文件 §3.3）。
 * 連線中斷時用 `Range: bytes=<已下載>-` 接續，不必整檔重來。
 */
class RangeDownloader(
    private val client: OkHttpClient,
    /** 重試間隔的基數（指數退避：1、2、4…倍）；單元測試用較短的值。 */
    private val retryBaseMs: Long = RETRY_BASE_MS,
) {

    class NotFoundException : IOException("記錄器上找不到此檔案 (404)")

    class RangeIgnoredException(total: Long, expected: Long) :
        IOException("續傳時伺服器回 HTTP 200（大小 $total，預期 $expected），不覆寫已下載的部分")

    /**
     * @param expectedSize 記錄器列表上的檔案大小（未知為 -1）；續傳時伺服器忽略 Range 才用得到
     * @param openOutput 開啟定位在指定位移的輸出串流（位移 0 = 從頭覆寫）
     * @return 最終檔案長度與伺服器回報的總長度（未知為 -1）
     */
    suspend fun download(
        url: String,
        startOffset: Long,
        expectedSize: Long,
        openOutput: (offset: Long) -> OutputStream,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Pair<Long, Long> = withContext(Dispatchers.IO) {
        var offset = startOffset
        var total = -1L
        var failures = 0
        while (true) {
            ensureActive()
            try {
                val request = Request.Builder().url(url)
                    .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                    .build()
                val call = client.newCall(request)
                // execute() 與 read() 是阻塞呼叫，協程被取消時不會自己停：另開一個監看的子協程，
                // 一取消就 call.cancel() 把連線切掉（invokeOnCompletion 要等協程結束才觸發，來不及）
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { resp ->
                        when (resp.code) {
                            206 -> total = parseTotal(resp.header("Content-Range")) ?: -1L
                            200 -> {
                                total = resp.body?.contentLength() ?: -1L
                                // 伺服器忽略 Range：只能從頭來。但記錄器實測支援 Range，續傳時收到 200
                                // 很可能根本不是記錄器回的，大小也對得上才覆寫已下載的部分
                                if (offset > 0 && (expectedSize <= 0 || total != expectedSize)) {
                                    throw RangeIgnoredException(total, expectedSize)
                                }
                                offset = 0
                            }
                            416 -> {
                                val t = parseTotal(resp.header("Content-Range"))
                                if (t != null && offset >= t) return@withContext offset to t
                                throw IOException("HTTP 416")
                            }
                            404 -> throw NotFoundException()
                            else -> throw IOException("HTTP ${resp.code}")
                        }
                        val body = resp.body ?: throw IOException("回應沒有內容")
                        openOutput(offset).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    offset += n
                                    failures = 0
                                    onProgress(offset, total)
                                    ensureActive()
                                }
                            }
                        }
                    }
                } finally {
                    canceller.cancel()
                }
                if (total < 0 || offset >= total) return@withContext offset to total
                // 串流提早結束：從目前位移接續
                Log.w(TAG, "stream ended early at $offset / $total: $url")
                if (++failures > MAX_RETRIES) throw IOException("下載一再中斷（$offset / $total）")
            } catch (e: NotFoundException) {
                throw e
            } catch (e: RangeIgnoredException) {
                throw e
            } catch (e: DashcamOfflineException) {
                throw e // 已經斷線，重試只會一直被 RouteGuard 擋下
            } catch (e: IOException) {
                ensureActive()
                Log.w(TAG, "interrupted at $offset / $total (attempt $failures): $url", e)
                if (++failures > MAX_RETRIES) throw e
                delay(retryBaseMs shl (failures - 1))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    private fun parseTotal(contentRange: String?): Long? =
        contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()

    private companion object {
        const val TAG = "RangeDownloader"
        const val BUFFER_SIZE = 256 * 1024
        const val MAX_RETRIES = 5
        const val RETRY_BASE_MS = 1000L
    }
}
