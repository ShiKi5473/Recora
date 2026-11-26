package tw.com.recora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.recora.data.EditPlan
import tw.com.recora.data.gps.GpsPoint
import tw.com.recora.data.gps.GpsTrack
import tw.com.recora.data.gps.Gpx
import tw.com.recora.data.gps.NovatekGps
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
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
    fun nearestSecondForMapTaps() {
        val track = NovatekGps.read(source(mp4(listOf(real0, noFix, real1))))!!
        assertEquals(listOf(0, 2), track.fixes.map { it.first })
        // 第 0、2 秒相距約 18.6 m；點在第 2 秒北邊約 5 m
        assertEquals(2, track.nearestSecond(24.16624, 120.618831, maxMeters = 40.0))
        assertEquals(0, track.nearestSecond(24.166031901, 120.618798828, maxMeters = 1.0))
        // 太遠：不算點到路線
        assertNull(track.nearestSecond(24.17, 120.62, maxMeters = 40.0))
        assertNull(NovatekGps.read(source(mp4(listOf(noFix))))!!.nearestSecond(0.0, 0.0, 1e9))
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

    @Test
    fun clipSingleSegmentMiddleFullAndMinimum() {
        val t0 = Instant.parse("2026-11-20T01:00:00Z")
        val track = GpsTrack(
            List(60) { i ->
                GpsPoint(t0.plusSeconds(i.toLong()), fix = true, latitude = 24.0 + i * 0.001, longitude = 120.5 + i * 0.001, speedKmh = 50f, trackDeg = 90f)
            },
        )
        // 中間剪 15 秒：10..24
        val midPieces = EditPlan.pieces(listOf(60_050L), 10_000L, 25_000L)
        val mid = GpsTrack.clip(midPieces, listOf(60_050L), listOf(track))!!
        assertEquals(15, mid.points.size)
        assertEquals(track.points.subList(10, 25), mid.points)

        // 整段 60.05 秒不剪掉尾端：維持 60 筆，不多出一筆重複的
        val fullPieces = EditPlan.pieces(listOf(60_050L), 0L, 60_050L)
        val full = GpsTrack.clip(fullPieces, listOf(60_050L), listOf(track))!!
        assertEquals(60, full.points.size)
        assertEquals(track.points, full.points)

        // 最短 500ms 剪輯仍保留 1 筆
        val minPieces = EditPlan.pieces(listOf(60_050L), 12_000L, 12_500L)
        val minClip = GpsTrack.clip(minPieces, listOf(60_050L), listOf(track))!!
        assertEquals(listOf(track.points[12]), minClip.points)

        // 沒有 GPS 或全部為 null 回 null
        assertNull(GpsTrack.clip(midPieces, listOf(60_050L), listOf(null)))
        assertNull(GpsTrack.clip(midPieces, listOf(60_050L), listOf(GpsTrack(List(60) { null }))))
    }

    @Test
    fun clipMultiSegmentWithOverhangAndGpx() {
        val t0 = Instant.parse("2026-11-20T01:00:00Z")
        fun segTrack(startSec: Int, count: Int, baseLat: Double) = GpsTrack(
            List(count) { i ->
                GpsPoint(
                    utc = t0.plusSeconds((startSec + i).toLong()),
                    fix = true,
                    latitude = baseLat + i * 0.0001,
                    longitude = 120.6,
                    speedKmh = 60f,
                    trackDeg = 180f,
                )
            },
        )
        val durations = listOf(180_050L, 180_030L, 60_020L)
        val tracks = listOf(segTrack(0, 180, 24.0), segTrack(180, 180, 25.0), segTrack(360, 60, 26.0))
        val pieces = EditPlan.pieces(durations, 170_000L, 380_000L)
        val clipped = GpsTrack.clip(pieces, durations, tracks)!!
        assertEquals(210, clipped.points.size)
        val expected = tracks[0].points.subList(170, 180) + tracks[1].points + tracks[2].points.subList(0, 20)
        assertEquals(expected, clipped.points)

        val gpx = Gpx.write("multi", clipped)!!
        val trkpts = gpx.lines().filter { it.startsWith("<trkpt") }
        assertEquals(210, trkpts.size)
        assertTrue(trkpts.first().contains("<time>2026-11-20T01:02:50Z</time>"))
        assertTrue(trkpts.last().contains("<time>2026-11-20T01:06:19Z</time>"))

        // 中間那段沒有 GPS 時，前後兩段依然保留
        val partial = GpsTrack.clip(pieces, durations, listOf(tracks[0], null, tracks[2]))!!
        assertEquals(210, partial.points.size)
        assertEquals(tracks[0].points.subList(170, 180), partial.points.subList(0, 10))
        assertTrue(partial.points.subList(10, 190).all { it == null })
        assertEquals(tracks[2].points.subList(0, 20), partial.points.subList(190, 210))
    }

    @Test
    fun appendToMp4AndReadRoundTrip() {
        val t0 = Instant.parse("2026-11-16T00:59:59Z")
        val track = GpsTrack(
            listOf(
                GpsPoint(t0, fix = true, latitude = 24.16603191234567, longitude = 120.61879887654321, speedKmh = 68.5f, trackDeg = 123.25f),
                GpsPoint(t0.plusSeconds(1), fix = true, latitude = -33.875123456789, longitude = -151.204166712345, speedKmh = 12.25f, trackDeg = 270.5f),
                GpsPoint(t0.plusSeconds(2), fix = false, latitude = 0.0, longitude = 0.0, speedKmh = 0f, trackDeg = 0f),
                GpsPoint(null, fix = true, latitude = 25.033964, longitude = 121.564468, speedKmh = 45f, trackDeg = 0f),
                null,
            ),
        )
        // moov 在 mdat 前（含一個 Media3 常見的補白 free box）
        val moovFirstBytes = exportedMp4(moovFirst = true)
        val tmp = Files.createTempFile("recora_gps_", ".mp4").toFile()
        try {
            tmp.writeBytes(moovFirstBytes)
            assertTrue(NovatekGps.appendToMp4(tmp, track))
            val decoded = NovatekGps.read(source(tmp.readBytes()))!!
            assertEquals(track.points, decoded.points)
        } finally {
            tmp.delete()
        }

        // mdat 在 moov 前
        val mdatFirstBytes = exportedMp4(moovFirst = false)
        val tmp2 = Files.createTempFile("recora_gps2_", ".mp4").toFile()
        try {
            tmp2.writeBytes(mdatFirstBytes)
            assertTrue(NovatekGps.appendToMp4(tmp2, track))
            val decoded = NovatekGps.read(source(tmp2.readBytes()))!!
            assertEquals(track.points, decoded.points)
        } finally {
            tmp2.delete()
        }
    }

    @Test
    fun reEditClippedMp4PreservesGps() {
        val t0 = Instant.parse("2026-11-20T05:00:00Z")
        val orig = GpsTrack(
            List(20) { i ->
                GpsPoint(t0.plusSeconds(i.toLong()), fix = true, latitude = 24.1 + i * 0.0005, longitude = 120.6 + i * 0.0005, speedKmh = 40f + i, trackDeg = 45f)
            },
        )
        val firstFile = Files.createTempFile("recora_edit1_", ".mp4").toFile()
        val secondFile = Files.createTempFile("recora_edit2_", ".mp4").toFile()
        try {
            firstFile.writeBytes(exportedMp4(moovFirst = true))
            assertTrue(NovatekGps.appendToMp4(firstFile, orig))
            val readFirst = NovatekGps.read(source(firstFile.readBytes()))!!

            val subPieces = EditPlan.pieces(listOf(20_000L), 5_000L, 12_000L)
            val reClipped = GpsTrack.clip(subPieces, listOf(20_000L), listOf(readFirst))!!
            secondFile.writeBytes(exportedMp4(moovFirst = true))
            assertTrue(NovatekGps.appendToMp4(secondFile, reClipped))

            val readSecond = NovatekGps.read(source(secondFile.readBytes()))!!
            assertEquals(7, readSecond.points.size)
            assertEquals(orig.points.subList(5, 12), readSecond.points)
        } finally {
            firstFile.delete()
            secondFile.delete()
        }
    }

    @Test
    fun appendToMp4LeavesInvalidFilesUntouched() {
        val track = GpsTrack(listOf(GpsPoint(null, fix = true, latitude = 24.0, longitude = 120.5, speedKmh = 30f, trackDeg = 0f)))
        val tmp = Files.createTempFile("recora_invalid_", ".mp4").toFile()
        try {
            // 不是 MP4（例如 TS）
            val ts = ByteArray(188 * 2) { if (it % 188 == 0) 0x47 else 0x11 }
            tmp.writeBytes(ts)
            assertFalse(NovatekGps.appendToMp4(tmp, track))
            assertTrue(ts.contentEquals(tmp.readBytes()))

            // 含有 size == 0 的未封口 box
            val ftyp = box("ftyp", "mp42".toByteArray())
            val openMdat = ByteBuffer.allocate(24).putInt(0).put("mdat".toByteArray()).array()
            val unterminated = ftyp + openMdat
            tmp.writeBytes(unterminated)
            assertFalse(NovatekGps.appendToMp4(tmp, track))
            assertTrue(unterminated.contentEquals(tmp.readBytes()))

            // 長度與 box 宣告不符（截斷）
            val truncated = exportedMp4(moovFirst = true).let { it.copyOf(it.size - 10) }
            tmp.writeBytes(truncated)
            assertFalse(NovatekGps.appendToMp4(tmp, track))
            assertTrue(truncated.contentEquals(tmp.readBytes()))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun recGpsTruncatedOrCorruptedFilesNeverThrow() {
        val t0 = Instant.parse("2026-11-20T01:00:00Z")
        val track = GpsTrack(
            listOf(
                GpsPoint(t0, fix = true, latitude = 24.166, longitude = 120.618, speedKmh = 60f, trackDeg = 10f),
                null,
                GpsPoint(t0.plusSeconds(2), fix = false, latitude = 0.0, longitude = 0.0, speedKmh = 0f, trackDeg = 0f),
            ),
        )
        val file = exportedMp4(moovFirst = true) + NovatekGps.encodeBox(track)!!
        for (len in 0..file.size) NovatekGps.read(source(file.copyOf(len)))
        val random = Random(20261126)
        repeat(20_000) {
            val bad = file.copyOf()
            repeat(1 + random.nextInt(4)) { bad[random.nextInt(bad.size)] = random.nextInt(256).toByte() }
            NovatekGps.read(source(bad))
        }
    }

    /** 模擬 Transformer 匯出的 MP4：ftyp + moov（無 gps box）+ 補白 free box + mdat。 */
    private fun exportedMp4(moovFirst: Boolean): ByteArray {
        val ftyp = box("ftyp", "mp42".toByteArray() + ByteArray(4) + "isommp42".toByteArray())
        val moov = box("moov", box("mvhd", ByteArray(100)))
        val padFree = box("free", ByteArray(64))
        val mdat = box("mdat", ByteArray(512) { 0x55 })
        return if (moovFirst) ftyp + moov + padFree + mdat else ftyp + mdat + moov + padFree
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
