package tw.com.mufudashcam

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.com.mufudashcam.data.net.DashcamOfflineException
import tw.com.mufudashcam.data.net.RouteGuard
import tw.com.mufudashcam.download.RangeDownloader
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class RangeDownloaderTest {
    /** 大於 RangeDownloader 的 256 KB 緩衝區，會分好幾次讀。 */
    private val content = Random(1).nextBytes(300 * 1024)

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val downloader = RangeDownloader(client, retryBaseMs = 10)

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun downloadsWholeFile() {
        server.dispatcher = RangeDispatcher(content)
        val file = FakeFile()
        val (bytes, total) = download(file, startOffset = 0)
        assertEquals(content.size.toLong(), bytes)
        assertEquals(content.size.toLong(), total)
        assertArrayEquals(content, file.data)
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun resumesFromOffsetWithRange() {
        server.dispatcher = RangeDispatcher(content)
        val file = FakeFile(content.copyOf(1000))
        download(file, startOffset = 1000)
        assertArrayEquals(content, file.data)
        assertEquals("bytes=1000-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun continuesFromWhereTheStreamBroke() {
        server.dispatcher = RangeDispatcher(content).apply { breakFirst = true }
        val file = FakeFile()
        download(file, startOffset = 0)
        assertArrayEquals(content, file.data)
        assertNull(server.takeRequest().getHeader("Range"))
        // 第二次從斷掉的位置接著要，不是從頭
        val resumed = server.takeRequest().getHeader("Range")!!
        assertTrue(resumed, Regex("""bytes=[1-9]\d*-""").matches(resumed))
    }

    @Test
    fun resumeKeepsPartialFileWhen200IsSomethingElse() {
        // 續傳時收到不相干的 200（例如斷線後碰到網際網路上的 192.72.1.1）：不能把已下載的部分截掉
        server.dispatcher = RangeDispatcher(content).apply {
            ignoreRange = true
            bodyOverride = "<html>not a dashcam</html>".toByteArray()
        }
        val file = FakeFile(content.copyOf(1000))
        try {
            download(file, startOffset = 1000)
            fail("應該丟出 RangeIgnoredException")
        } catch (e: RangeDownloader.RangeIgnoredException) {
            // 預期
        }
        assertArrayEquals(content.copyOf(1000), file.data)
        assertEquals(1, server.requestCount) // 不重試
    }

    @Test
    fun resumeRestartsWhenServerIgnoresRangeButSizeMatches() {
        server.dispatcher = RangeDispatcher(content).apply { ignoreRange = true }
        val file = FakeFile(content.copyOf(1000))
        download(file, startOffset = 1000, expectedSize = content.size.toLong())
        assertArrayEquals(content, file.data)
    }

    @Test
    fun alreadyCompleteFileEndsOn416() {
        server.dispatcher = RangeDispatcher(content)
        val file = FakeFile(content.copyOf())
        val (bytes, total) = download(file, startOffset = content.size.toLong())
        assertEquals(content.size.toLong(), bytes)
        assertEquals(content.size.toLong(), total)
        assertArrayEquals(content, file.data)
    }

    @Test
    fun notFoundFailsWithoutRetry() {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            download(FakeFile(), startOffset = 0)
            fail("應該丟出 NotFoundException")
        } catch (e: RangeDownloader.NotFoundException) {
            // 預期
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun offlineFailsWithoutRetryOrRequest() {
        server.dispatcher = RangeDispatcher(content)
        val guarded = RangeDownloader(client.newBuilder().addInterceptor(RouteGuard { false }).build(), retryBaseMs = 10)
        try {
            runBlocking { guarded.download(server.url("/f.TS").toString(), 0, -1, FakeFile()::open) { _, _ -> } }
            fail("應該丟出 DashcamOfflineException")
        } catch (e: DashcamOfflineException) {
            // 預期
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancelStopsStalledRequestImmediately() = runBlocking {
        // 記錄器卡住：收下請求但不回應。取消要馬上切斷連線，而不是等 30 秒的 readTimeout
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.Default) {
            downloader.download(server.url("/f.TS").toString(), 0, -1, FakeFile()::open) { _, _ -> }
        }
        server.takeRequest(5, TimeUnit.SECONDS) ?: fail("請求沒有送出")
        val started = System.nanoTime()
        job.cancelAndJoin()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("取消花了 $ms ms", ms < 3000)
    }

    private fun download(file: FakeFile, startOffset: Long, expectedSize: Long = -1): Pair<Long, Long> = runBlocking {
        downloader.download(server.url("/mnt/mmc/Video/F/FILE.TS").toString(), startOffset, expectedSize, file::open) { _, _ -> }
    }

    /** 模擬 MediaStoreSink：開啟時截斷到 offset，再往後寫。 */
    private class FakeFile(var data: ByteArray = ByteArray(0)) {
        fun open(offset: Long): OutputStream {
            data = data.copyOf(offset.toInt())
            return object : OutputStream() {
                override fun write(b: Int) {
                    data += b.toByte()
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    data += b.copyOfRange(off, off + len)
                }
            }
        }
    }

    /** 支援 Range 的檔案伺服器（同記錄器的 GoAhead），可模擬各種狀況。 */
    private class RangeDispatcher(private val content: ByteArray) : Dispatcher() {
        /** 忽略 Range，一律回 200 + 完整內容。 */
        var ignoreRange = false

        /** 回別的內容（例如網頁）。 */
        var bodyOverride: ByteArray? = null

        /** 第一個回應送到一半就斷線。 */
        var breakFirst = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = bodyOverride ?: content
            val range = request.getHeader("Range")
            val response = if (range == null || ignoreRange) {
                MockResponse().setResponseCode(200).setBody(Buffer().write(body))
            } else {
                val start = Regex("""bytes=(\d+)-""").find(range)!!.groupValues[1].toInt()
                if (start >= body.size) {
                    MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${body.size}")
                } else {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                        .setBody(Buffer().write(body, start, body.size - start))
                }
            }
            if (breakFirst) {
                breakFirst = false
                response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }
            return response
        }
    }
}
