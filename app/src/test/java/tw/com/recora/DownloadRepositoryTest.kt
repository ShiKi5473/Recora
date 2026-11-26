package tw.com.recora

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.com.recora.data.Brand
import tw.com.recora.data.DashcamFile
import tw.com.recora.data.Folder
import tw.com.recora.data.SettingDef
import tw.com.recora.data.api.DashcamApi
import tw.com.recora.download.DownloadRepository
import tw.com.recora.download.DownloadSink
import tw.com.recora.download.QueueStore
import tw.com.recora.download.TaskStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DownloadRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private class FakeSink : DownloadSink {
        data class Stored(val name: String, val isImage: Boolean, var data: ByteArray, var pending: Boolean)

        val entries = ConcurrentHashMap<String, Stored>()
        private var seq = 1

        override fun find(name: String, isImage: Boolean): DownloadSink.Entry? =
            entries.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) && it.value.isImage == isImage }
                ?.let { (uri, s) -> DownloadSink.Entry(uri, s.name, s.data.size.toLong(), s.pending) }

        override fun create(name: String, isImage: Boolean, subdir: String?): String {
            val uri = "mem://${seq++}/$name"
            entries[uri] = Stored(name, isImage, ByteArray(0), pending = true)
            return uri
        }

        override fun openOutput(uri: String, offset: Long): OutputStream {
            val stored = entries.getValue(uri)
            val buffer = ByteArrayOutputStream().apply {
                write(stored.data.copyOf(offset.toInt()))
                stored.data = toByteArray()
            }
            return object : OutputStream() {
                override fun write(b: Int) {
                    buffer.write(b)
                    stored.data = buffer.toByteArray()
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    buffer.write(b, off, len)
                    stored.data = buffer.toByteArray()
                }

                override fun close() {
                    stored.data = buffer.toByteArray()
                    super.close()
                }
            }
        }

        override fun finish(uri: String) {
            entries[uri]?.pending = false
        }

        override fun delete(uri: String) {
            entries.remove(uri)
        }
    }

    private class FakeApi(
        override val brand: Brand,
        private val urlOf: (String) -> String,
    ) : DashcamApi {
        override val host: String = "127.0.0.1"
        override val liveViews: List<DashcamApi.LiveView> = emptyList()
        override suspend fun resolveUrl(sdPath: String): String = urlOf(sdPath)
        override fun httpUrl(sdPath: String): String = urlOf(sdPath)
        override suspend fun probe(network: Network?) = TODO()
        override suspend fun syncTime(now: ZonedDateTime) = TODO()
        override fun listFolder(folder: Folder): Flow<List<DashcamFile>> = flowOf(emptyList())
        override suspend fun delete(sdPath: String) = TODO()
        override suspend fun startLive(view: DashcamApi.LiveView) = TODO()
        override suspend fun recordingStatus() = TODO()
        override suspend fun cardStatus() = TODO()
        override suspend fun storageInfo() = TODO()
        override suspend fun readSettings() = TODO()
        override suspend fun changeSetting(def: SettingDef, value: String) = TODO()
        override suspend fun formatCard(onProgress: (String) -> Unit) = TODO()
    }

    private fun file(name: String, size: Long, brand: Brand = Brand.Mufu) =
        DashcamFile(name, "/mnt/mmc/Video/F/$name", size, "", Folder.Normal, brand = brand)

    private fun rangeResponse(content: ByteArray, request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range")
        if (range == null) {
            return MockResponse().setResponseCode(200).setBody(Buffer().write(content))
        }
        val start = Regex("""bytes=(\d+)-""").find(range)!!.groupValues[1].toInt()
        return if (start >= content.size) {
            MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${content.size}")
        } else {
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                .setBody(Buffer().write(content, start, content.size - start))
        }
    }

    @Test
    fun skipsExistingCompletedFileAndDownloadsNewOne() = runBlocking {
        val content = Random(1).nextBytes(4096)
        val sink = FakeSink()
        val existingUri = sink.create("EXISTING.TS", isImage = false)
        sink.openOutput(existingUri, 0).use { it.write(content) }
        sink.finish(existingUri)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = rangeResponse(content, request)
        }
        val repo = DownloadRepository(
            api = { b -> FakeApi(b) { server.url(it).toString() } },
            sink = sink,
            httpClient = OkHttpClient(),
            scope = scope,
            queueStore = null,
            connectedBrand = { Brand.Mufu },
        )

        val existing = file("EXISTING.TS", content.size.toLong())
        val fresh = file("FRESH.TS", content.size.toLong())
        assertEquals(2, repo.enqueue(listOf(existing, fresh)))

        withTimeout(5_000) { repo.active.first { !it } }
        val tasks = repo.tasks.value
        assertEquals(TaskStatus.Done, tasks[0].status)
        assertEquals(existingUri, tasks[0].resultUri)
        assertEquals("已存在，略過", tasks[0].message)
        assertEquals(TaskStatus.Done, tasks[1].status)
        assertArrayEquals(content, sink.entries.getValue(tasks[1].resultUri!!).data)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun holdPausesRunningTaskAndResumesViaRangeAfterRelease() = runBlocking {
        val content = Random(2).nextBytes(200 * 1024)
        val firstChunkSent = CountDownLatch(1)
        val rangeHeaders = CopyOnWriteArrayList<String?>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                rangeHeaders += range
                if (range == null) {
                    // 慢慢傳送完整內容，讓測試有足夠時間寫入部分位元組再由 hold() 主動切斷
                    firstChunkSent.countDown()
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(content))
                        .throttleBody(4 * 1024, 200, TimeUnit.MILLISECONDS)
                }
                return rangeResponse(content, request)
            }
        }

        val sink = FakeSink()
        val repo = DownloadRepository(
            api = { b -> FakeApi(b) { server.url(it).toString() } },
            sink = sink,
            httpClient = OkHttpClient(),
            scope = scope,
            queueStore = null,
            connectedBrand = { Brand.Mufu },
        )
        val f = file("PAUSE.TS", content.size.toLong())
        repo.enqueue(listOf(f))
        assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) {
            while (sink.find(f.name, false)?.size?.let { it > 0 } != true) {
                kotlinx.coroutines.delay(10)
            }
        }

        assertTrue(repo.hold("串流播放"))
        repo.awaitPaused()
        assertEquals(TaskStatus.Queued, repo.tasks.value.single().status)
        assertTrue(repo.tasks.value.single().message?.startsWith("暫停中：") == true)

        repo.release()
        withTimeout(5_000) { repo.active.first { !it } }

        val done = repo.tasks.value.single()
        assertEquals(TaskStatus.Done, done.status)
        assertArrayEquals(content, sink.entries.getValue(done.resultUri!!).data)
        assertTrue(rangeHeaders.any { it != null && it.startsWith("bytes=") })
    }

    @Test
    fun disconnectWhileHeldWakesWorkerAndStopsService() = runBlocking {
        val content = Random(3).nextBytes(64 * 1024)
        var connected: Brand? = Brand.Mufu
        val firstChunkSent = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                firstChunkSent.countDown()
                return MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(content))
                    .throttleBody(1024, 200, TimeUnit.MILLISECONDS)
            }
        }

        val sink = FakeSink()
        val repo = DownloadRepository(
            api = { b -> FakeApi(b) { server.url(it).toString() } },
            sink = sink,
            httpClient = OkHttpClient(),
            scope = scope,
            queueStore = null,
            connectedBrand = { connected },
        )
        val f = file("DISCONNECT.TS", content.size.toLong())
        repo.enqueue(listOf(f))
        assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS))

        assertTrue(repo.hold("即時畫面"))
        repo.awaitPaused()
        assertTrue(repo.active.value)

        // 關鍵回歸測試：在 holds > 0 期間斷線，active 必須變 false（worker 不能永久卡住）
        connected = null
        repo.pauseForDisconnect()
        withTimeout(3_000) { repo.active.first { !it } }
        assertFalse(repo.active.value)
        assertEquals(TaskStatus.Queued, repo.tasks.value.single().status)
        assertTrue(repo.tasks.value.single().message?.contains("等待連線") == true)
    }

    @Test
    fun pauseForTimeoutKeepsQueueForNextResume() = runBlocking {
        val content = Random(4).nextBytes(128 * 1024)
        val firstChunkSent = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range == null) {
                    firstChunkSent.countDown()
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(content))
                        .throttleBody(4 * 1024, 200, TimeUnit.MILLISECONDS)
                }
                return rangeResponse(content, request)
            }
        }

        val queueFile = File(tmp.root, "queue.tsv")
        val queueStore = QueueStore(queueFile)
        val sink = FakeSink()
        val repo = DownloadRepository(
            api = { b -> FakeApi(b) { server.url(it).toString() } },
            sink = sink,
            httpClient = OkHttpClient(),
            scope = scope,
            queueStore = queueStore,
            connectedBrand = { Brand.Mufu },
        )
        val f = file("TIMEOUT.TS", content.size.toLong())
        repo.enqueue(listOf(f))
        assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) {
            while (sink.find(f.name, false)?.size?.let { it > 0 } != true) {
                kotlinx.coroutines.delay(10)
            }
        }

        repo.pauseForTimeout()
        assertFalse(repo.active.value)
        withTimeout(3_000) {
            while (repo.tasks.value.single().status != TaskStatus.Queued) {
                kotlinx.coroutines.delay(10)
            }
        }
        // QueueStore 仍保有此檔，沒有被清除
        withTimeout(3_000) {
            while (queueStore.load().isEmpty()) kotlinx.coroutines.delay(10)
        }
        assertEquals(listOf(f.sdPath), queueStore.load().map { it.sdPath })

        // 下次恢復時應接續完成
        repo.resume()
        withTimeout(5_000) { repo.active.first { !it } }
        val done = repo.tasks.value.single()
        assertEquals(TaskStatus.Done, done.status)
        assertArrayEquals(content, sink.entries.getValue(done.resultUri!!).data)
    }

    @Test
    fun mixedBrandsOnlyDownloadsConnectedBrand() = runBlocking {
        val content = Random(5).nextBytes(2048)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = rangeResponse(content, request)
        }
        var connected: Brand? = Brand.Mufu
        val sink = FakeSink()
        val repo = DownloadRepository(
            api = { b -> FakeApi(b) { server.url(it).toString() } },
            sink = sink,
            httpClient = OkHttpClient(),
            scope = scope,
            queueStore = null,
            connectedBrand = { connected },
        )
        val mufu = file("MUFU.TS", content.size.toLong(), Brand.Mufu)
        val astro = DashcamFile("ASTRO.MP4", "/DCIM/VIDEO_F/ASTRO.MP4", content.size.toLong(), "", Folder.Normal, brand = Brand.Astro)
        repo.enqueue(listOf(astro, mufu))

        withTimeout(5_000) { repo.active.first { !it } }
        val tasks1 = repo.tasks.value
        assertEquals(TaskStatus.Queued, tasks1[0].status)
        assertTrue(tasks1[0].message?.contains("Astro") == true)
        assertEquals(TaskStatus.Done, tasks1[1].status)

        // 切換連上 Astro 後呼叫 resume，剩下那個接著完成
        connected = Brand.Astro
        repo.resume()
        withTimeout(5_000) { repo.active.first { !it } }
        assertEquals(TaskStatus.Done, repo.tasks.value[0].status)
    }
}
