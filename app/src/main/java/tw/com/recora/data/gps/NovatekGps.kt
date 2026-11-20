package tw.com.recora.data.gps

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
}

/**
 * 解析 Novatek 記錄器（Astro LEO3）MP4 裡的 GPS（純 Kotlin，方便測試）。
 *
 * 格式（2026-11-20 用 35 個 Astro 原始檔實測，前後鏡頭的檔都有）：
 * - `moov` 底下有 `gps ` box：BE u32 版本（0x101）、BE u32 筆數，接著每筆 BE u32 檔案位置、BE u32 大小，
 *   每秒一筆，指向 `mdat` 裡的 `free` box。Astro 的 `moov` 在檔頭，只要讀前面一小段。
 * - 每個 `free` box：`'free' 'GPS '` 之後，0x10 起 LE u32 時、分、秒、年、月、日（UTC），
 *   0x28 起狀態 `A`／`V`、`N`／`S`、`E`／`W`，0x2C 起 LE float 緯度、經度（DDMM.MMMM）、時速（km/h）、方向（度）。
 *   沒有加密。
 * 剪輯匯出的 MP4（Transformer）沒有這些資料。
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
    private val GPS_MAGIC = "freeGPS ".toByteArray(Charsets.US_ASCII)

    /** 讀出整支影片的 GPS；檔案裡沒有 GPS 索引（例如 Mufu 的 TS、剪輯匯出的檔）回 null。 */
    fun read(source: ByteSource): GpsTrack? {
        val index = findIndex(source) ?: return null
        return GpsTrack(index.map { (offset, size) -> if (size < RECORD_BYTES) null else parseRecord(source.read(offset, RECORD_BYTES)) })
    }

    /** 走過最上層的 box 找 `moov`（跳過 `mdat` 不讀內容），回傳 `gps ` 索引的 (位置, 大小)。 */
    private fun findIndex(source: ByteSource): List<Pair<Long, Int>>? {
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
            val isMoov = String(type, Charsets.US_ASCII) == "moov"
            if (size == 0L) {
                // 延伸到檔尾：後面不會再有別的 box
                return if (isMoov) parseMoov(source.read(pos + headerLen, MAX_MOOV_BYTES)) else null
            }
            if (size < headerLen) return null
            if (isMoov) {
                val bodyLen = size - headerLen
                if (bodyLen > MAX_MOOV_BYTES) return null
                return parseMoov(source.read(pos + headerLen, bodyLen.toInt()))
            }
            if (size > Long.MAX_VALUE - pos - 16) return null
            pos += size
        }
        return null
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
