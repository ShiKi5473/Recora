package tw.com.recora.data.gps

import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import tw.com.recora.data.EditPlan
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.floor

/** 影片某一秒的 GPS 記錄。 */
data class GpsPoint(
    /** 記錄器寫的 GPS 時間（UTC，加上時區才是檔名的時間）；欄位不合理時為 null。 */
    val utc: Instant?,
    /** 記錄器標記為已定位（`A`）且座標在合理範圍內。false 時座標不可用。 */
    val fix: Boolean,
    /** 緯度（度），南緯為負。 */
    val latitude: Double,
    /** 經度（度），西經為負。 */
    val longitude: Double,
    val speedKmh: Float,
    /** 行進方向（度，正北為 0）。 */
    val trackDeg: Float,
)

/**
 * 一支影片的 GPS 軌跡：[points] 的第 i 筆就是影片的第 i 秒；讀不出來的那一秒為 null。
 *
 * 用第幾筆對時，不用 GPS 時間：實測 Astro 的 GPS 時間偶爾會重複或跳 2 秒，但每秒都會寫一筆。
 */
class GpsTrack(val points: List<GpsPoint?>) {
    /** 播放位置 [positionMs] 那一秒的記錄（可能未定位，看 [GpsPoint.fix]）；超出範圍回 null。 */
    fun pointAt(positionMs: Long): GpsPoint? =
        if (positionMs < 0) null else points.getOrNull((positionMs / 1000).toInt())

    /**
     * 播放器顯示用的那一秒：同 [pointAt]，但片長比筆數多一點點（例如 60.05 秒、60 筆；播完會停在片長）時，
     * 最後那一點點算最後一筆。
     */
    fun pointForPosition(positionMs: Long): GpsPoint? =
        pointAt(positionMs) ?: points.lastOrNull().takeIf { positionMs / 1000 == points.size.toLong() }

    /** 已定位的點（第幾秒, 記錄），畫路線用。 */
    val fixes: List<Pair<Int, GpsPoint>> by lazy {
        points.mapIndexedNotNull { i, p -> if (p?.fix == true) i to p else null }
    }

    /** 離 ([latitude], [longitude]) 最近、而且在 [maxMeters] 以內的已定位點是第幾秒；沒有回 null。點地圖跳到那一秒用。 */
    fun nearestSecond(latitude: Double, longitude: Double, maxMeters: Double): Int? {
        val target = LatLng(latitude, longitude)
        return fixes.map { (i, p) -> i to GeoUtils.haversine(target, LatLng(p.latitude, p.longitude)) }
            .filter { it.second <= maxMeters }
            .minByOrNull { it.second }?.first
    }

    private data class ResolvedPiece(val segment: Int, val startMs: Long, val lengthMs: Long)

    companion object {
        /**
         * 依剪輯分段 [pieces]（來源段索引、段內起訖毫秒）與各段長度 [durations]、GPS 軌跡 [tracks]，
         * 裁切並接出匯出影片每一秒對應的 GPS 軌跡。完全沒有可用 GPS 時回 null。
         */
        fun clip(pieces: List<EditPlan.Piece>, durations: List<Long>, tracks: List<GpsTrack?>): GpsTrack? {
            val resolved = ArrayList<ResolvedPiece>(pieces.size)
            for (piece in pieces) {
                val segDur = durations.getOrNull(piece.segment) ?: continue
                val startMs = piece.startMs.coerceAtLeast(0L)
                val endMs = (piece.endMs ?: segDur).coerceAtLeast(startMs)
                val lengthMs = endMs - startMs
                if (lengthMs > 0L) resolved += ResolvedPiece(piece.segment, startMs, lengthMs)
            }
            if (resolved.isEmpty() || resolved.none { tracks.getOrNull(it.segment) != null }) return null
            val totalMs = resolved.sumOf { it.lengthMs }
            val seconds = maxOf(1, ((totalMs + 500L) / 1000L).coerceAtMost(NovatekGps.MAX_EXPORT_RECORDS.toLong()).toInt())
            var pieceIdx = 0
            var pieceOffset = 0L
            val points = List(seconds) { sec ->
                val outMs = (sec * 1000L + 500L).coerceAtMost(totalMs - 1L)
                while (pieceIdx < resolved.lastIndex && outMs >= pieceOffset + resolved[pieceIdx].lengthMs) {
                    pieceOffset += resolved[pieceIdx].lengthMs
                    pieceIdx++
                }
                val p = resolved[pieceIdx]
                val localMs = p.startMs + (outMs - pieceOffset)
                tracks.getOrNull(p.segment)?.pointForPosition(localMs)
            }
            if (points.all { it == null }) return null
            return GpsTrack(points)
        }
    }
}

/**
 * 解析與寫入 MP4 裡的 GPS（純 Kotlin，方便測試）。
 *
 * 支援兩種格式：
 * 1. Novatek 記錄器（Astro LEO3）原始 MP4（2026-11-20 用 35 個實機檔實測，前後鏡頭都有）：
 *    - `moov` 底下有 `gps ` box：BE u32 版本（0x101）、BE u32 筆數，接著每筆 BE u32 檔案位置、BE u32 大小，
 *      每秒一筆，指向 `mdat` 裡的 `free` box。
 *    - 每個 `free` box：`'free' 'GPS '` 之後，0x10 起 LE u32 時、分、秒、年、月、日（UTC），
 *      0x28 起狀態 `A`／`V`、`N`／`S`、`E`／`W`，0x2C 起 LE float 緯度、經度（DDMM.MMMM）、時速（km/h）、方向（度）。
 * 2. 本 App 剪輯匯出的 MP4：
 *    - 在檔尾附加一個標準最上層 `free` box（不更動 `moov` 與 `mdat` 的 chunk 位移），
 *      內容為 `'RECGPS01'` + BE u32 筆數 + 每秒 33 bytes 固定格式（flags、epoch 秒、Double 經緯度、Float 時速與方向）。
 */
object NovatekGps {
    /**
     * 從檔案 [offset] 讀最多 [length] bytes；到檔尾時回傳較短的陣列。
     * 呼叫時 [offset] ≥ 0 且 offset + length 不會溢位；讀檔失敗可以丟 IOException，由 [read] 的呼叫端處理。
     */
    fun interface ByteSource {
        fun read(offset: Long, length: Int): ByteArray
    }

    private const val RECORD_BYTES = 0x3C
    private const val MAX_TOP_BOXES = 64
    /** 實測 1 分鐘的檔 `moov` 約 35 KB；5 分鐘約 175 KB。 */
    private const val MAX_MOOV_BYTES = 2 shl 20
    /** 每秒一筆，最長的檔 5 分鐘 = 300 筆；超過一小時的索引當作壞掉的檔。 */
    private const val MAX_RECORDS = 3600
    /** 剪輯匯出最多保留 4 小時的逐秒 GPS。 */
    internal const val MAX_EXPORT_RECORDS = 14_400
    private const val RECGPS_HEADER_BYTES = 12
    private const val RECGPS_RECORD_BYTES = 33
    private const val MAX_RECGPS_BYTES = RECGPS_HEADER_BYTES + MAX_EXPORT_RECORDS * RECGPS_RECORD_BYTES
    private const val FLAG_PRESENT = 0x01
    private const val FLAG_FIX = 0x02
    private const val FLAG_HAS_UTC = 0x04
    private val GPS_MAGIC = "freeGPS ".toByteArray(Charsets.US_ASCII)
    private val RECGPS_MAGIC = "RECGPS01".toByteArray(Charsets.US_ASCII)

    /** 讀出整支影片的 GPS；檔案裡沒有 GPS（例如 Mufu 的 TS、沒有 GPS 的影片）回 null。 */
    fun read(source: ByteSource): GpsTrack? {
        var pos = 0L
        repeat(MAX_TOP_BOXES) {
            val header = source.read(pos, 16)
            if (header.size < 8) return null
            val type = header.copyOfRange(4, 8)
            if (!type.all { it in 0x20..0x7E }) return null // 不是 MP4
            var size = u32be(header, 0)
            var headerLen = 8
            if (size == 1L) {
                if (header.size < 16) return null
                size = ByteBuffer.wrap(header, 8, 8).long
                headerLen = 16
            }
            val boxType = String(type, Charsets.US_ASCII)
            if (size == 0L) {
                // 延伸到檔尾：後面不會再有別的 box
                return when (boxType) {
                    "moov" -> readNovatekTrack(source, parseMoov(source.read(pos + headerLen, MAX_MOOV_BYTES)))
                    "free" -> readRecoraFree(source, pos + headerLen, MAX_RECGPS_BYTES.toLong(), header, headerLen)
                    else -> null
                }
            }
            if (size < headerLen) return null
            val bodyLen = size - headerLen
            when (boxType) {
                "moov" -> if (bodyLen <= MAX_MOOV_BYTES) {
                    parseMoov(source.read(pos + headerLen, bodyLen.toInt()))?.let { index ->
                        return readNovatekTrack(source, index)
                    }
                }
                "free" -> readRecoraFree(source, pos + headerLen, bodyLen, header, headerLen)?.let { return it }
            }
            if (size > Long.MAX_VALUE - pos - 16) return null
            pos += size
        }
        return null
    }

    /** 把 [track] 編碼成最上層 MP4 `free` box（`RECGPS01`）；沒有任何點時回 null。 */
    fun encodeBox(track: GpsTrack): ByteArray? {
        if (track.points.isEmpty() || track.points.all { it == null }) return null
        val count = minOf(track.points.size, MAX_EXPORT_RECORDS)
        val payloadSize = RECGPS_HEADER_BYTES + count * RECGPS_RECORD_BYTES
        val boxSize = 8 + payloadSize
        val buf = ByteBuffer.allocate(boxSize)
        buf.putInt(boxSize)
        buf.put("free".toByteArray(Charsets.US_ASCII))
        buf.put(RECGPS_MAGIC)
        buf.putInt(count)
        for (i in 0 until count) {
            val p = track.points[i]
            if (p == null) {
                buf.put(0)
                buf.position(buf.position() + (RECGPS_RECORD_BYTES - 1))
            } else {
                var flags = FLAG_PRESENT
                if (p.fix) flags = flags or FLAG_FIX
                val utc = p.utc
                if (utc != null) flags = flags or FLAG_HAS_UTC
                buf.put(flags.toByte())
                buf.putLong(utc?.epochSecond ?: 0L)
                buf.putDouble(p.latitude)
                buf.putDouble(p.longitude)
                buf.putFloat(p.speedKmh)
                buf.putFloat(p.trackDeg)
            }
        }
        return buf.array()
    }

    /**
     * 驗證 [file] 是結構完整的 MP4 後，在檔尾附加一個 `RECGPS01` `free` box。
     * 不是 MP4、結構不完整（含 size == 0 的未封口 box）或沒有可用 GPS 時不改動檔案並回傳 false。
     */
    @Throws(IOException::class)
    fun appendToMp4(file: File, track: GpsTrack): Boolean {
        val box = encodeBox(track) ?: return false
        RandomAccessFile(file, "rw").use { raf ->
            val end = verifiedMp4End(raf) ?: return false
            raf.seek(end)
            try {
                raf.write(box)
            } catch (e: IOException) {
                runCatching { raf.setLength(end) }
                throw e
            }
        }
        return true
    }

    private fun verifiedMp4End(raf: RandomAccessFile): Long? {
        val len = raf.length()
        if (len < 8L) return null
        var pos = 0L
        val header = ByteArray(16)
        repeat(MAX_TOP_BOXES) {
            if (pos == len) return len
            if (pos > len || len - pos < 8L) return null
            raf.seek(pos)
            val toRead = minOf(16L, len - pos).toInt()
            raf.readFully(header, 0, toRead)
            if (!(4..7).all { header[it] in 0x20..0x7E }) return null
            var size = u32be(header, 0)
            var headerLen = 8
            if (size == 1L) {
                if (toRead < 16) return null
                size = ByteBuffer.wrap(header, 8, 8).long
                headerLen = 16
            }
            if (size == 0L || size < headerLen || size > len - pos) return null
            pos += size
        }
        return if (pos == len) len else null
    }

    private fun readNovatekTrack(source: ByteSource, index: List<Pair<Long, Int>>?): GpsTrack? {
        if (index == null) return null
        return GpsTrack(index.map { (offset, size) -> if (size < RECORD_BYTES) null else parseRecord(source.read(offset, RECORD_BYTES)) })
    }

    private fun readRecoraFree(
        source: ByteSource,
        bodyPos: Long,
        bodyLen: Long,
        header: ByteArray,
        headerLen: Int,
    ): GpsTrack? {
        if (bodyLen < RECGPS_HEADER_BYTES) return null
        if (headerLen == 8) {
            if (header.size < 16 || !RECGPS_MAGIC.indices.all { header[8 + it] == RECGPS_MAGIC[it] }) return null
        }
        val toRead = minOf(bodyLen, MAX_RECGPS_BYTES.toLong()).toInt()
        return parseRecoraGps(source.read(bodyPos, toRead))
    }

    private fun parseRecoraGps(body: ByteArray): GpsTrack? {
        if (body.size < RECGPS_HEADER_BYTES) return null
        if (!RECGPS_MAGIC.indices.all { body[it] == RECGPS_MAGIC[it] }) return null
        val buf = ByteBuffer.wrap(body)
        val countLong = buf.getInt(8).toLong() and 0xFFFF_FFFFL
        if (countLong == 0L || countLong > MAX_EXPORT_RECORDS) return null
        val count = countLong.toInt()
        if (body.size < RECGPS_HEADER_BYTES + count * RECGPS_RECORD_BYTES) return null
        var anyNonNull = false
        val points = List(count) { i ->
            val off = RECGPS_HEADER_BYTES + i * RECGPS_RECORD_BYTES
            val flags = body[off].toInt() and 0xFF
            if (flags == 0 || (flags and FLAG_PRESENT) == 0) {
                null
            } else {
                anyNonNull = true
                val epochSec = buf.getLong(off + 1)
                val lat = buf.getDouble(off + 9)
                val lon = buf.getDouble(off + 17)
                val speed = buf.getFloat(off + 25).let { if (it.isFinite()) it else 0f }
                val trackDeg = buf.getFloat(off + 29).let { if (it.isFinite()) it else 0f }
                val utc = if ((flags and FLAG_HAS_UTC) != 0) {
                    runCatching { Instant.ofEpochSecond(epochSec) }.getOrNull()
                } else {
                    null
                }
                val fix = (flags and FLAG_FIX) != 0 && lat.isFinite() && lon.isFinite() && abs(lat) <= 90.0 && abs(lon) <= 180.0
                GpsPoint(
                    utc = utc,
                    fix = fix,
                    latitude = if (lat.isFinite()) lat else 0.0,
                    longitude = if (lon.isFinite()) lon else 0.0,
                    speedKmh = speed,
                    trackDeg = trackDeg,
                )
            }
        }
        return if (anyNonNull) GpsTrack(points) else null
    }

    private fun parseMoov(moov: ByteArray): List<Pair<Long, Int>>? {
        var pos = 0
        while (pos + 8 <= moov.size) {
            val size = u32be(moov, pos)
            if (size < 8 || pos + size > moov.size) return null
            if (String(moov, pos + 4, 4, Charsets.US_ASCII) == "gps ") return parseIndex(moov, pos + 8, pos + size.toInt())
            pos += size.toInt()
        }
        return null
    }

    private fun parseIndex(buf: ByteArray, start: Int, end: Int): List<Pair<Long, Int>>? {
        if (end - start < 8) return null
        val count = u32be(buf, start + 4)
        if (count > (end - start - 8) / 8 || count > MAX_RECORDS) return null
        return (0 until count.toInt()).map { i ->
            val p = start + 8 + i * 8
            u32be(buf, p) to u32be(buf, p + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /** 一筆 `free` box 的開頭 [RECORD_BYTES] bytes；不是 GPS 記錄回 null。 */
    internal fun parseRecord(rec: ByteArray): GpsPoint? {
        if (rec.size < RECORD_BYTES || !GPS_MAGIC.indices.all { rec[4 + it] == GPS_MAGIC[it] }) return null
        val le = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
        val t = IntArray(6) { le.getInt(0x10 + it * 4) } // 時、分、秒、年、月、日
        val utc = runCatching {
            LocalDateTime.of(t[3], t[4], t[5], t[0], t[1], t[2]).toInstant(ZoneOffset.UTC)
        }.getOrNull()
        val status = rec[0x28].toInt().toChar()
        val south = rec[0x29].toInt().toChar() == 'S'
        val west = rec[0x2A].toInt().toChar() == 'W'
        val lat = degrees(le.getFloat(0x2C)) * (if (south) -1 else 1)
        val lon = degrees(le.getFloat(0x30)) * (if (west) -1 else 1)
        val valid = status == 'A' && !lat.isNaN() && !lon.isNaN() && abs(lat) <= 90 && abs(lon) <= 180
        return GpsPoint(
            utc = utc,
            fix = valid,
            latitude = lat,
            longitude = lon,
            speedKmh = le.getFloat(0x34),
            trackDeg = le.getFloat(0x38),
        )
    }

    /** DDMM.MMMM（度分）換成度。 */
    private fun degrees(ddmm: Float): Double {
        val v = ddmm.toDouble()
        val deg = floor(v / 100)
        return deg + (v - deg * 100) / 60
    }

    private fun u32be(buf: ByteArray, at: Int): Long =
        ((buf[at].toLong() and 0xFF) shl 24) or ((buf[at + 1].toLong() and 0xFF) shl 16) or
            ((buf[at + 2].toLong() and 0xFF) shl 8) or (buf[at + 3].toLong() and 0xFF)
}
