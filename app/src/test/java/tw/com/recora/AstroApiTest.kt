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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tw.com.recora.data.api.AstroApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class AstroApiTest {
    private lateinit var server: MockWebServer
    private val requestLines = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val overrides = ConcurrentHashMap<String, MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestLines += request.requestLine
                val cmd = Regex("cmd=(\\d+)").find(request.requestLine)?.groupValues?.get(1)
                val par = Regex("par=(\\d+)").find(request.requestLine)?.groupValues?.get(1)
                (overrides["$cmd&$par"] ?: overrides["$cmd"])?.let { return it }
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

    private fun api(formatTimeoutMs: Long = AstroApi.FORMAT_TIMEOUT_MS) =
        AstroApi(OkHttpClient(), scope, formatTimeoutMs) { "${server.hostName}:${server.port}" }

    private fun reply(cmd: String, status: Int) =
        MockResponse().setBody("<Function><Cmd>$cmd</Cmd><Status>$status</Status></Function>")

    /** 記憶卡正常、上面還有檔案：格式化沒有生效的樣子。 */
    private fun cardStillHasFiles() {
        overrides["3024"] = MockResponse().setBody("<Function><Cmd>3024</Cmd><Status>0</Status><Value>1</Value></Function>")
        overrides["3015"] = MockResponse().setBody(
            """<LIST><ALLFile><File><NAME>20260706084244_000052F.MP4</NAME><FPATH>A:\DCIM\EMERG_F\20260706084244_000052F.MP4</FPATH>
            <SIZE>100</SIZE><TIMECODE>0</TIMECODE><TIME>2026/07/06 08:43:44</TIME><ATTR>33</ATTR></File></ALLFile></LIST>""",
        )
    }

    /** 官方 App 紀錄：緊急錄影（ATTR 33）的 4003 一律回 -4。 */
    @Test
    fun deleteReportsRecorderRejection() = runBlocking {
        overrides["4003"] = reply("4003", -4)
        val result = api().delete("/DCIM/EMERG_F/20260706084244_000052F.MP4")
        assertEquals(false, result.ok)
        assertEquals("Status -4", result.body)
    }

    @Test
    fun formatWithoutReplySaysSoAndResumesRecording() = runBlocking {
        overrides["3010"] = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        cardStillHasFiles()
        val message = api(formatTimeoutMs = 300).formatCard {}
        assertTrue(message, message.startsWith("記錄器沒有回應格式化"))
        assertTrue(requestLines.toString(), requestLines.any { "cmd=2001&par=1" in it })
    }

    @Test
    fun formatTellsWhenRecordingCannotResume() = runBlocking {
        overrides["3010"] = reply("3010", 0)
        overrides["2001&1"] = reply("2001", -13)
        cardStillHasFiles()
        val message = api(formatTimeoutMs = 300).formatCard {}
        assertTrue(message, message.contains("無法開始錄影"))
        assertTrue(message, message.contains("Status -13"))
    }

    @Test
    fun formatCompletesWhenCardIsEmpty() = runBlocking {
        overrides["3024"] = MockResponse().setBody("<Function><Cmd>3024</Cmd><Status>0</Status><Value>1</Value></Function>")
        assertEquals("格式化完成，記憶卡已清空", api().formatCard {})
        assertTrue(requestLines.toString(), requestLines.any { "cmd=2001&par=1" in it })
    }

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
