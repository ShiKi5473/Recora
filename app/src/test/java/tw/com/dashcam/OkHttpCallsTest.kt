package tw.com.dashcam

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.dashcam.data.net.await

class OkHttpCallsTest {
    @Test
    fun awaitReturnsResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val resp = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).await()
            resp.use { assertEquals("ok", it.body!!.string()) }
        }
    }

    @Test
    fun timeoutCancelsHangingCallQuickly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient()
            val started = System.nanoTime()
            try {
                withTimeout(300) { client.newCall(Request.Builder().url(server.url("/")).build()).await() }
                throw AssertionError("應該逾時")
            } catch (e: TimeoutCancellationException) {
                // 預期
            }
            // OkHttp 預設讀取逾時 10 秒；有切斷連線的話遠小於這個時間
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        }
    }
}
