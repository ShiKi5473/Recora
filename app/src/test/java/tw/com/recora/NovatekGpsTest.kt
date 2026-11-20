package tw.com.recora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.recora.data.gps.NovatekGps
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.random.Random

class NovatekGpsTest {
    /** Astro 實機檔 20261116085959_001112F.MP4 第 1、2 筆 GPS 區塊的前 0x3C bytes（原樣）。 */
    private val real0 = hex(
        "00 00 40 00 66 72 65 65 47 50 53 20 2c 00 00 00 " +
            "00 00 00 00 3b 00 00 00 3b 00 00 00 ea 07 00 00 " +
            "0b 00 00 00 10 00 00 00 41 4e 45 00 64 9f 16 45 " +
            "83 14 3c 46 00 00 88 42 00 00 10 41",
    )
    private val real1 = hex(
        "00 00 40 00 66 72 65 65 47 50 53 20 2c 00 00 00 " +
            "01 00 00 00 00 00 00 00 00 00 00 00 ea 07 00 00 " +
            "0b 00 00 00 10 00 00 00 41 4e 45 00 8d 9f 16 45 " +
            "85 14 3c 46 00 00 88 42 00 00 10 41",
    )
    private val noFix = record(status = 'V', ns = 'N', ew = 'E', lat = 0f, lon = 0f)
    private val notGps = ByteArray(0x40) { 0x11 }

    @Test
    fun decodesRealAstroRecords() {
        val track = NovatekGps.read(source(mp4(listOf(real0, real1, noFix, notGps))))!!
        assertEquals(4, track.points.size)

        val p0 = track.points[0]!!
        assertTrue(p0.fix)
        assertEquals(Instant.parse("2026-11-16T00:59:59Z"), p0.utc) // +8 小時 = 檔名的 08:59:59
        assertEquals(24.166031901, p0.latitude, 1e-8)
        assertEquals(120.618798828, p0.longitude, 1e-8)
        assertEquals(68f, p0.speedKmh)
        assertEquals(9f, p0.trackDeg)

        val p1 = track.points[1]!!
        assertEquals(Instant.parse("2026-11-16T01:00:00Z"), p1.utc)
        assertEquals(24.166198730, p1.latitude, 1e-8)
        assertEquals(120.618831380, p1.longitude, 1e-8)

        assertFalse(track.points[2]!!.fix)
        // 索引指到的不是 GPS 區塊：那一秒是 null，其他照常
        assertNull(track.points[3])
    }

    @Test
    fun fieldOrderAndHemispheres() {
        // 時、分、秒各不相同，才看得出欄位順序
        val se = NovatekGps.parseRecord(record('A', 'S', 'E', lat = 3352.5f, lon = 15112.25f, speed = 12.5f, track = 270f))!!
        assertEquals(Instant.parse("2026-11-20T12:30:05Z"), se.utc)
        assertTrue(se.fix)
        assertEquals(-33.875, se.latitude, 1e-9)
        assertEquals(151.204166667, se.longitude, 1e-8)
        assertEquals(12.5f, se.speedKmh)
        assertEquals(270f, se.trackDeg)

        val nw = NovatekGps.parseRecord(record('A', 'N', 'W', lat = 3352.5f, lon = 15112.25f))!!
        assertEquals(33.875, nw.latitude, 1e-9)
        assertEquals(-151.204166667, nw.longitude, 1e-8)

        // 已定位但座標超出範圍：不可用
        assertFalse(NovatekGps.parseRecord(record('A', 'N', 'E', lat = 9500f, lon = 12000f))!!.fix)
        assertFalse(NovatekGps.parseRecord(record('A', 'N', 'E', lat = 2400f, lon = 18100f))!!.fix)
        // 日期不合理：時間為 null，座標照常
        val badDate = record('A', 'N', 'E', lat = 2400f, lon = 12000f).also { it[0x20] = 13 } // 13 月
        assertNull(NovatekGps.parseRecord(badDate)!!.utc)
        assertNull(NovatekGps.parseRecord(real0.copyOf(0x3B)))
    }

    @Test
    fun pointAtUsesRecordIndexAsVideoSecond() {
        val track = NovatekGps.read(source(mp4(listOf(real0, real1, noFix))))!!
        assertEquals(track.points[0], track.pointAt(0))
        assertEquals(track.points[0], track.pointAt(999))
        assertEquals(track.points[1], track.pointAt(1000))
        assertEquals(track.points[2], track.pointAt(2999))
        assertNull(track.pointAt(3000))
        assertNull(track.pointAt(-1))
    }

    @Test
    fun findsMoovAfterMdat() {
        val track = NovatekGps.read(source(mp4(listOf(real0, real1), moovFirst = false)))!!
        assertEquals(2, track.points.size)
        assertEquals(24.166031901, track.points[0]!!.latitude, 1e-8)
    }

    @Test
    fun returnsNullWithoutGpsIndex() {
        // 剪輯匯出的 MP4：有 moov 但沒有 gps box
        assertNull(NovatekGps.read(source(mp4(emptyList(), withGpsBox = false))))
        // Mufu 的 TS
        assertNull(NovatekGps.read(source(ByteArray(188 * 4) { if (it % 188 == 0) 0x47 else 0 })))
        // 空檔
        assertNull(NovatekGps.read(source(ByteArray(0))))
    }

    @Test
    fun malformedBoxesNeverReachInvalidOffsets() {
        val ftyp = box("ftyp", ByteArray(20))
        // largesize 接近 Long.MAX_VALUE 的 skip
        val huge = ByteBuffer.allocate(16).putInt(1).put("skip".toByteArray()).putLong(Long.MAX_VALUE).array()
        assertNull(NovatekGps.read(source(ftyp + huge)))
        // 延伸到檔尾（size 0）的 mdat 在 moov 前面：後面不會有 moov
        val mdatToEnd = ByteBuffer.allocate(8 + 32).putInt(0).put("mdat".toByteArray()).array()
        assertNull(NovatekGps.read(source(ftyp + mdatToEnd)))
        // 筆數超過 gps box 裝得下的
        val overCount = box("moov", box("gps ", ByteBuffer.allocate(16).putInt(0x101).putInt(2).putInt(0).putInt(0x40).array()))
        assertNull(NovatekGps.read(source(ftyp + overCount)))
        // 筆數超過上限（3600）
        val tooMany = 3601
        val bigIndex = ByteBuffer.allocate(8 + tooMany * 8).putInt(0x101).putInt(tooMany).array()
        assertNull(NovatekGps.read(source(ftyp + box("moov", box("gps ", bigIndex)))))
    }

    @Test
    fun indexPastEndOfFile() {
        // 沒下載完的檔：索引列到檔尾之後的記錄讀不到，那幾秒是 null
        val file = mp4(listOf(real0, real1))
        val cut = file.copyOf(file.size - 0x100 - 0x30) // 切掉最後一筆的後半
        val track = NovatekGps.read(source(cut))!!
        assertEquals(24.166031901, track.points[0]!!.latitude, 1e-8)
        assertNull(track.points[1])
    }

    @Test
    fun truncatedOrCorruptedFilesNeverThrow() {
        val file = mp4(listOf(real0, real1, noFix))
        for (len in 0..file.size) NovatekGps.read(source(file.copyOf(len)))
        val random = Random(20261120)
        repeat(20_000) {
            val bad = file.copyOf()
            repeat(1 + random.nextInt(4)) { bad[random.nextInt(bad.size)] = random.nextInt(256).toByte() }
            NovatekGps.read(source(bad))
        }
    }

    // --- 假 MP4 ---

    /** 跟 LocalGps 一樣：超出檔案範圍讀到空陣列；也檢查解析器不會傳出不合法的位置。 */
    private fun source(file: ByteArray) = NovatekGps.ByteSource { offset, length ->
        assertTrue("offset=$offset length=$length", offset >= 0 && length >= 0 && offset + length >= 0)
        if (offset >= file.size) ByteArray(0) else file.copyOfRange(offset.toInt(), minOf(file.size.toLong(), offset + length).toInt())
    }

    /** ftyp + moov（mvhd、gps 索引）+ mdat（每筆 GPS 區塊前塞一段假的影像資料），或 moov 放在 mdat 後面。 */
    private fun mp4(records: List<ByteArray>, moovFirst: Boolean = true, withGpsBox: Boolean = true): ByteArray {
        val ftyp = box("ftyp", "mp42".toByteArray() + ByteArray(4) + "isomavc1mp42".toByteArray())
        val filler = ByteArray(1000) { 0x55 }
        val moovSize = 8 + (8 + 100) + (if (withGpsBox) 8 + 8 + records.size * 8 else 0)
        val mdatStart = ftyp.size + (if (moovFirst) moovSize else 0)
        val mdatBody = ByteArrayOutputStream()
        val offsets = records.map { rec ->
            mdatBody.write(filler)
            val at = mdatStart + 8 + mdatBody.size()
            mdatBody.write(rec + ByteArray(0x100)) // 區塊比 0x3C 大，後面是 0
            at to rec.size + 0x100
        }
        val gps = ByteBuffer.allocate(8 + records.size * 8).putInt(0x101).putInt(records.size)
        offsets.forEach { (at, size) -> gps.putInt(at).putInt(size) }
        val moov = box("moov", box("mvhd", ByteArray(100)) + (if (withGpsBox) box("gps ", gps.array()) else ByteArray(0)))
        assertEquals(moovSize, moov.size)
        val mdat = box("mdat", mdatBody.toByteArray())
        return if (moovFirst) ftyp + moov + mdat else ftyp + mdat + moov
    }

    private fun box(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.US_ASCII)).put(body).array()

    /** 自己造的記錄：2026-11-20 12:30:05 UTC。 */
    private fun record(status: Char, ns: Char, ew: Char, lat: Float, lon: Float, speed: Float = 0f, track: Float = 0f): ByteArray {
        val b = ByteBuffer.allocate(0x3C).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0, 0, 0x40, 0)).put("freeGPS ".toByteArray(Charsets.US_ASCII)).putInt(0x2C) // box 大小是 BE
        listOf(12, 30, 5, 2026, 11, 20).forEach { b.putInt(it) } // 時、分、秒、年、月、日
        b.put(status.code.toByte()).put(ns.code.toByte()).put(ew.code.toByte()).put(0)
        b.putFloat(lat).putFloat(lon).putFloat(speed).putFloat(track)
        return b.array()
    }

    private fun hex(s: String): ByteArray = s.trim().split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
