package tw.com.recora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.recora.data.gps.GpsPoint
import tw.com.recora.data.gps.GpsTrack
import tw.com.recora.data.gps.Gpx
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class GpxTest {
    private val t0 = Instant.parse("2026-11-16T00:59:59Z")

    private fun point(utc: Instant?, fix: Boolean = true, lat: Double = 24.1660319, lon: Double = 120.6187988) =
        GpsPoint(utc = utc, fix = fix, latitude = lat, longitude = lon, speedKmh = 68f, trackDeg = 9f)

    @Test
    fun timesFollowVideoSecondsNotGpsClock() {
        val track = GpsTrack(
            listOf(
                point(t0, fix = false), // 第 0 秒未定位：不寫
                point(t0.plusSeconds(1)),
                point(t0.plusSeconds(1), lat = -33.875, lon = -151.2041667), // GPS 時間重複
                null, // 讀不出來的一秒
                point(t0.plusSeconds(5)), // GPS 時間跳秒
            ),
        )
        val trkpts = Gpx.write("a&b", track)!!.lines().filter { it.startsWith("<trkpt") }
        assertEquals(
            listOf(
                "<trkpt lat=\"24.1660319\" lon=\"120.6187988\"><time>2026-11-16T01:00:00Z</time></trkpt>",
                "<trkpt lat=\"-33.8750000\" lon=\"-151.2041667\"><time>2026-11-16T01:00:01Z</time></trkpt>",
                "<trkpt lat=\"24.1660319\" lon=\"120.6187988\"><time>2026-11-16T01:00:03Z</time></trkpt>",
            ),
            trkpts,
        )
    }

    @Test
    fun firstFixWithoutTimeUsesLaterOne() {
        // 第 0 秒已定位但沒有 GPS 時間，第 2 秒才有：起點往回推 2 秒
        val track = GpsTrack(listOf(point(null), point(null), point(t0)))
        val times = Gpx.write("x", track)!!.lines().filter { it.startsWith("<trkpt") }.map { it.substringAfter("<time>").substringBefore("</time>") }
        assertEquals(listOf("2026-11-16T00:59:57Z", "2026-11-16T00:59:58Z", "2026-11-16T00:59:59Z"), times)
    }

    @Test
    fun headerNameAndEdgeCases() {
        val gpx = Gpx.write("a&b<c>\"", GpsTrack(listOf(point(t0), point(t0.plusSeconds(1)))))!!
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\""))
        // 是合法的 XML，名稱跳脫後讀回來不變
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(gpx.byteInputStream())
        assertEquals("http://www.topografix.com/GPX/1/1", doc.documentElement.namespaceURI)
        assertEquals("a&b<c>\"", doc.getElementsByTagName("name").item(0).textContent)
        assertEquals(2, doc.getElementsByTagName("trkpt").length)
        // 沒有 GPS 時間：不寫 <time>
        assertTrue("<time>" !in Gpx.write("x", GpsTrack(listOf(point(null))))!!)
        // 沒有已定位的點
        assertNull(Gpx.write("x", GpsTrack(listOf(point(t0, fix = false), null))))
        assertNull(Gpx.write("x", GpsTrack(emptyList())))
    }
}
