package tw.com.recora

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tw.com.recora.data.api.AstroApi
import java.util.concurrent.CopyOnWriteArrayList

class AstroApiTest {
    private lateinit var server: MockWebServer
    private val requestLines = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestLines += request.requestLine
                val cmd = Regex("cmd=(\\d+)").find(request.requestLine)?.groupValues?.get(1)
                val body = when (cmd) {
                    "3014" -> "<Function><Cmd>3014</Cmd><Status>0</Status></Function>" +
                        "<Function><Cmd>2001</Cmd><Status>1</Status></Function>"
                    "3012" -> "<Function><Cmd>3012</Cmd><Status>0</Status><String>MB:V1.00.11</String></Function>"
                    else -> "<Function><Cmd>$cmd</Cmd><Status>0</Status></Function>"
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun api() = AstroApi(OkHttpClient(), scope) { "${server.hostName}:${server.port}" }

    @Test
    fun deleteSendsBackslashesAndColonUnencodedAfterEnteringPlayback() = runBlocking {
        val result = api().delete("/DCIM/VIDEO_F/20260813120000_002000F.MP4")
        assertTrue(result.ok)
        val line = requestLines.first { "cmd=4003" in it }
        assertTrue(line, line.contains("""str=A:\DCIM\VIDEO_F\20260813120000_002000F.MP4"""))
        // 先停錄、切到回放模式，才刪除
        val order = requestLines.map { Regex("cmd=(\\d+)").find(it)!!.groupValues[1] }
        assertTrue(order.indexOf("2001") in 0 until order.indexOf("4003"))
        assertTrue(order.indexOf("3001") in 0 until order.indexOf("4003"))
        assertTrue(requestLines.first { "cmd=3001" in it }.contains("par=2"))
    }

    @Test
    fun syncTimeSendsUtcUnencoded() = runBlocking {
        val now = java.time.ZonedDateTime.of(2026, 10, 13, 20, 38, 59, 0, java.time.ZoneId.of("Asia/Taipei"))
        val result = api().syncTime(now)
        assertTrue(result.ok)
        assertTrue(requestLines.any { it.contains("cmd=3005&str=2026-10-13") })
        assertTrue(requestLines.any { it.contains("cmd=3006&str=12:38:59") })
    }

    @Test
    fun probeRequiresNovatekXmlForCmd3012() = runBlocking {
        val ok = api().probe(null)
        assertTrue(ok.detail, ok.ok)

        // 家用路由器的 192.168.1.x 也會回 HTTP 200：不是 <Cmd>3012</Cmd> 就不算記錄器
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>router</html>")
        }
        val router = api().probe(null)
        assertEquals(false, router.ok)
    }

    @Test
    fun httpUrlIsHostPlusSdPath() {
        val url = api().httpUrl("/DCIM/VIDEO_F/a.MP4")
        assertEquals("http://${server.hostName}:${server.port}/DCIM/VIDEO_F/a.MP4", url)
    }
}
