package tw.com.recora

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.recora.data.net.DashcamAccess

@OptIn(ExperimentalCoroutinesApi::class)
class DashcamAccessTest {
    private class FakeDownloads : DashcamAccess.Downloads {
        val events = mutableListOf<String>()

        /** 不是 null 時，awaitPaused 等到它完成（模擬下載還在收尾）。 */
        var stopping: CompletableDeferred<Unit>? = null

        override fun hold(reason: String): Boolean {
            events += "hold"
            return true
        }

        override fun release() {
            events += "release"
        }

        override suspend fun awaitPaused() {
            stopping?.await()
        }

        fun count(event: String) = events.count { it == event }
    }

    private fun TestScope.access(downloads: FakeDownloads, idleMs: Long = 1_000) =
        DashcamAccess(downloads, { error("不會用到") }, backgroundScope, idleMs)

    @Test
    fun consecutiveCommandsPauseDownloadsOnceAndResumeAfterIdle() = runTest {
        val downloads = FakeDownloads()
        val access = access(downloads)
        access.command { }
        advanceTimeBy(500)
        access.command { } // 計時中又來一個指令：不重新暫停、也還不恢復
        advanceTimeBy(900)
        runCurrent()
        assertEquals(listOf("hold"), downloads.events)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("hold", "release"), downloads.events)
    }

    @Test
    fun commandWaitsUntilDownloadHasStopped() = runTest {
        val downloads = FakeDownloads().apply { stopping = CompletableDeferred() }
        val access = access(downloads)
        var ran = false
        val job = launch { access.command { ran = true } }
        runCurrent()
        assertEquals(1, downloads.count("hold"))
        assertFalse(ran)
        downloads.stopping!!.complete(Unit)
        job.join()
        assertTrue(ran)
    }

    @Test
    fun commandsAreSentOneAtATime() = runTest {
        val access = access(FakeDownloads())
        var inside = 0
        var most = 0
        List(3) {
            launch {
                access.command {
                    inside++
                    most = maxOf(most, inside)
                    delay(100)
                    inside--
                }
            }
        }.joinAll()
        assertEquals(1, most)
    }

    @Test
    fun grantKeepsDownloadsPausedUntilClosed() = runTest {
        val downloads = FakeDownloads()
        val access = access(downloads)
        val grant = access.acquire("播放")
        assertTrue(grant.pausedDownloads)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("hold"), downloads.events)
        grant.close()
        grant.close() // 重複 close 沒有副作用
        assertEquals(listOf("hold", "release"), downloads.events)
    }

    @Test
    fun commandDuringGrantDoesNotReleaseTheGrantsHold() = runTest {
        val downloads = FakeDownloads()
        val access = access(downloads)
        val grant = access.acquire("播放")
        access.command { }
        advanceTimeBy(5_000)
        runCurrent()
        // 指令自己的暫停歸還了，播放的還在：hold 2 次、release 1 次
        assertEquals(2, downloads.count("hold"))
        assertEquals(1, downloads.count("release"))
        grant.close()
        assertEquals(2, downloads.count("release"))
    }

    @Test
    fun photoRequestHoldsDownloadsAndReturnsTheWholeBody() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("jpeg-bytes"))
        server.start()
        try {
            val downloads = FakeDownloads()
            // 0 ms：最後一個指令結束就馬上恢復（Unconfined 在同一個執行緒上跑完）
            val access = DashcamAccess(downloads, { error("不會用到") }, CoroutineScope(Dispatchers.Unconfined), commandIdleMs = 0)
            val client = OkHttpClient.Builder().addInterceptor(access.interceptor).build()
            val body = client.newCall(Request.Builder().url(server.url("/photo.jpg")).build()).execute().use { it.body!!.string() }
            assertEquals("jpeg-bytes", body)
            assertEquals(listOf("hold", "release"), downloads.events)
        } finally {
            server.shutdown()
        }
    }
}
