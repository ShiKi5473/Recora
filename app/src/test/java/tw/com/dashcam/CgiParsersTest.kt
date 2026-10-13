package tw.com.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.dashcam.data.CgiParsers

class CgiParsersTest {
    /** 實測回應格式：根元素是 <Video>（不是 <Normal>），name 為完整路徑，attr 為 RW。 */
    private val realDir = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <Video>
           <file>
              <name>/mnt/mmc/Video/F/FILE260923-131459F.TS</name>
              <format>TS</format>
              <size>15126292</size>
              <attr>RW</attr>
              <time>2026-09-23 13:14:59</time>
           </file>
           <file>
              <name>/mnt/mmc/Video/F/FILE260923-131359F.TS</name>
              <format>TS</format>
              <size>15002000</size>
              <attr>RW</attr>
              <time>2026-09-23 13:13:59</time>
           </file>
        </Video>
    """.trimIndent()

    /** API 文件的格式：<Normal> 根元素、有 <amount>。 */
    private val docDir = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <Normal>
          <amount>123</amount>
          <file>
             <name>FILE260923-131459F.TS</name>
             <format>TS</format>
             <size>88493104</size>
             <attr>0</attr>
             <time>2026-09-23 13:14:59</time>
          </file>
        </Normal>
    """.trimIndent()

    @Test
    fun parsesRealResponse() {
        val page = CgiParsers.parseDir(realDir)
        assertEquals(2, page.entries.size)
        assertNull(page.amount)
        val first = page.entries[0]
        assertEquals("/mnt/mmc/Video/F/FILE260923-131459F.TS", first.name)
        assertEquals(15126292L, first.size)
        assertEquals("RW", first.attr)
        assertEquals("2026-09-23 13:14:59", first.time)
    }

    @Test
    fun parsesDocumentedResponseWithAmount() {
        val page = CgiParsers.parseDir(docDir)
        assertEquals(123, page.amount)
        assertEquals("FILE260923-131459F.TS", page.entries.single().name)
    }

    @Test
    fun emptyListing() {
        val page = CgiParsers.parseDir("<?xml version=\"1.0\" ?>\n<Video>\n</Video>")
        assertTrue(page.entries.isEmpty())
    }

    @Test
    fun keyValuesIgnoreNoise() {
        // 實測 get 回應會夾雜 CGI_PROCESS.sh 的輸出
        val body = "0\nOK\nUsage ./CGI_PROCESS.sh {no this parameter}\n" +
            "Camera.Menu.VideoRes=1440P30FPS;;\nCamera.Menu.GpsStamp=ON;;\n0 OK no this key"
        val kv = CgiParsers.parseKeyValues(body)
        assertEquals(mapOf("Camera.Menu.VideoRes" to "1440P30FPS", "Camera.Menu.GpsStamp" to "ON"), kv)
    }

    @Test
    fun keyValuesRealFormatWithoutSemicolons() {
        // 實機 V60P 的回應：沒有 ;;，值可能含空白與 -
        val body = "0\nOK\nCamera.Menu.charge=ON\nCamera.Menu.VideoQuality=Super Fine\n" +
            "Camera.Menu.DatTime=2024-01-01-01-01-01\n0\nOK\n"
        val kv = CgiParsers.parseKeyValues(body)
        assertEquals("ON", kv["Camera.Menu.charge"])
        assertEquals("Super Fine", kv["Camera.Menu.VideoQuality"])
        assertEquals("2024-01-01-01-01-01", kv["Camera.Menu.DatTime"])
        assertEquals(3, kv.size)
    }

    @Test
    fun sd0Status() {
        val kv = CgiParsers.parseKeyValues("READY\n0\nOK\nCamera.Menu.SD0=READY\n")
        assertEquals(mapOf("Camera.Menu.SD0" to "READY"), kv)
    }

    @Test
    fun noKeyValues() {
        assertTrue(CgiParsers.parseKeyValues("0\nOK\n0 OK no this key").isEmpty())
    }
}
