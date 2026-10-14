package tw.com.recora.ui.editor

import java.io.InputStream

/**
 * 直接解析 MPEG-TS，算出一段時間範圍內主畫面與聲音的資料量（純 Kotlin，方便測試）。
 *
 * 為什麼不用 MediaExtractor：實測 Android 的 MediaExtractor 讀 Mufu 記錄器的 TS，只列出 AAC 聲音與
 * 640×360 的 H.264 子串流，H.265 主串流根本不在清單裡，量出來只有實際的十分之一。
 *
 * 做法：從 PAT／PMT 找出畫面與聲音的 PID，把每個 PES（一格畫面或一段聲音）的大小與 PTS 記下來；
 * 時間以第一個 PTS 為 0（跟播放器、剪輯的時間軸一致）。畫面從起點前一個關鍵影格
 * （adaptation field 的 random_access_indicator）算起，跟「不重新編碼」實際複製的範圍一樣；
 * 有好幾路畫面時取資料量最大的那一路（主串流）。過了終點就停止讀檔。
 */
object TsRangeBytes {
    private const val PACKET = 188
    private const val SYNC: Int = 0x47
    private const val PTS_HZ = 90_000L
    private const val PTS_WRAP = 1L shl 33

    /** 掃過終點後再多讀這麼久才停（各路 PES 的順序不完全照時間）。 */
    private const val TAIL_US = 2_000_000L

    private val VIDEO_TYPES = setOf(0x01, 0x02, 0x10, 0x1B, 0x24)
    private val AUDIO_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81)

    /** 開頭兩個封包都是 0x47 開頭，就當作 TS。 */
    fun looksLikeTs(head: ByteArray): Boolean =
        head.size > PACKET && head[0].toInt() and 0xFF == SYNC && head[PACKET].toInt() and 0xFF == SYNC

    private class Pes(val ptsUs: Long, var bytes: Long, val key: Boolean)

    private class Stream(val video: Boolean) {
        val units = ArrayList<Pes>()
        var current: Pes? = null
        var lastPts = -1L
        var wraps = 0L
    }

    /** [startMs]～[endMs]（null = 到結尾）的 (主畫面, 聲音) bytes；不是 TS 或找不到畫面時回 null。 */
    fun count(input: InputStream, startMs: Long, endMs: Long?, isCancelled: () -> Boolean = { false }): Pair<Long, Long>? {
        val endUs = endMs?.let { it * 1000 }
        val streams = HashMap<Int, Stream>()
        var pmtPid = -1
        var baseUs = -1L
        val buf = ByteArray(PACKET * 1024)
        var filled = 0
        var packets = 0L
        loop@ while (true) {
            val n = input.read(buf, filled, buf.size - filled)
            if (n <= 0) break
            filled += n
            var off = 0
            while (filled - off >= PACKET) {
                if (buf[off].toInt() and 0xFF != SYNC) {
                    off++ // 失去同步：往後找下一個 0x47
                    continue
                }
                val pid = ((buf[off + 1].toInt() and 0x1F) shl 8) or (buf[off + 2].toInt() and 0xFF)
                val start = buf[off + 1].toInt() and 0x40 != 0
                val afc = (buf[off + 3].toInt() shr 4) and 0x3
                var p = off + 4
                var randomAccess = false
                if (afc == 2 || afc == 3) {
                    val afLen = buf[p].toInt() and 0xFF
                    if (afLen > 0) randomAccess = buf[p + 1].toInt() and 0x40 != 0
                    p += 1 + afLen
                }
                val end = off + PACKET
                if ((afc == 1 || afc == 3) && p < end) {
                    when {
                        pid == 0 && start -> pmtPid = parsePat(buf, p, end)
                        pid == pmtPid && start && streams.isEmpty() -> parsePmt(buf, p, end, streams)
                        else -> streams[pid]?.let { s ->
                            if (start) {
                                val (pts, headerLen) = parsePes(buf, p, end)
                                if (pts >= 0) {
                                    val us = s.unwrap(pts) * 1_000_000 / PTS_HZ
                                    if (baseUs < 0) baseUs = us
                                    val unit = Pes(us - baseUs, 0, randomAccess || !s.video)
                                    s.units += unit
                                    s.current = unit
                                }
                                s.current?.let { it.bytes += (end - p - headerLen).coerceAtLeast(0) }
                            } else {
                                s.current?.let { it.bytes += end - p }
                            }
                        }
                    }
                }
                off = end
                if (++packets % 4096 == 0L) {
                    if (isCancelled()) return null
                    if (endUs != null && streams.isNotEmpty() && streams.values.all { st ->
                            st.units.lastOrNull()?.let { it.ptsUs > endUs + TAIL_US } ?: false
                        }
                    ) {
                        break@loop
                    }
                }
            }
            System.arraycopy(buf, off, buf, 0, filled - off)
            filled -= off
        }
        val startUs = startMs * 1000
        val video = streams.values.filter { it.video }.maxOfOrNull { it.videoBytes(startUs, endUs) } ?: return null
        val audio = streams.values.filter { !it.video }.maxOfOrNull { it.audioBytes(startUs, endUs) } ?: 0L
        return video to audio
    }

    private fun Stream.unwrap(pts: Long): Long {
        if (lastPts >= 0 && pts < lastPts - PTS_WRAP / 2) wraps++
        lastPts = pts
        return pts + wraps * PTS_WRAP
    }

    /** 畫面：從起點前（含）最後一個關鍵影格開始，到終點之前。 */
    private fun Stream.videoBytes(startUs: Long, endUs: Long?): Long {
        val first = units.indexOfLast { it.key && it.ptsUs <= startUs }.coerceAtLeast(0)
        return units.drop(first).filter { endUs == null || it.ptsUs < endUs }.sumOf { it.bytes }
    }

    private fun Stream.audioBytes(startUs: Long, endUs: Long?): Long =
        units.filter { it.ptsUs >= startUs && (endUs == null || it.ptsUs < endUs) }.sumOf { it.bytes }

    /** PAT：回傳第一個節目的 PMT PID。 */
    private fun parsePat(b: ByteArray, from: Int, end: Int): Int {
        val p = from + 1 + (b[from].toInt() and 0xFF) // pointer_field
        val sectionLen = ((b[p + 1].toInt() and 0x0F) shl 8) or (b[p + 2].toInt() and 0xFF)
        var q = p + 8
        val last = minOf(p + 3 + sectionLen - 4, end)
        while (q + 4 <= last) {
            val program = ((b[q].toInt() and 0xFF) shl 8) or (b[q + 1].toInt() and 0xFF)
            val pid = ((b[q + 2].toInt() and 0x1F) shl 8) or (b[q + 3].toInt() and 0xFF)
            if (program != 0) return pid
            q += 4
        }
        return -1
    }

    /** PMT：記下畫面與聲音的 PID。 */
    private fun parsePmt(b: ByteArray, from: Int, end: Int, streams: MutableMap<Int, Stream>) {
        val p = from + 1 + (b[from].toInt() and 0xFF)
        val sectionLen = ((b[p + 1].toInt() and 0x0F) shl 8) or (b[p + 2].toInt() and 0xFF)
        val infoLen = ((b[p + 10].toInt() and 0x0F) shl 8) or (b[p + 11].toInt() and 0xFF)
        var q = p + 12 + infoLen
        val last = minOf(p + 3 + sectionLen - 4, end)
        while (q + 5 <= last) {
            val type = b[q].toInt() and 0xFF
            val pid = ((b[q + 1].toInt() and 0x1F) shl 8) or (b[q + 2].toInt() and 0xFF)
            val esInfo = ((b[q + 3].toInt() and 0x0F) shl 8) or (b[q + 4].toInt() and 0xFF)
            when (type) {
                in VIDEO_TYPES -> streams[pid] = Stream(video = true)
                in AUDIO_TYPES -> streams[pid] = Stream(video = false)
            }
            q += 5 + esInfo
        }
    }

    /** PES 標頭：回傳 (PTS，沒有時 -1；標頭長度)。 */
    private fun parsePes(b: ByteArray, p: Int, end: Int): Pair<Long, Int> {
        if (end - p < 9 || b[p].toInt() != 0 || b[p + 1].toInt() != 0 || b[p + 2].toInt() != 1) return -1L to 0
        val headerLen = 9 + (b[p + 8].toInt() and 0xFF)
        val hasPts = b[p + 7].toInt() and 0x80 != 0
        if (!hasPts || end - p < 14) return -1L to headerLen
        val pts = ((b[p + 9].toLong() and 0x0E) shl 29) or
            ((b[p + 10].toLong() and 0xFF) shl 22) or
            ((b[p + 11].toLong() and 0xFE) shl 14) or
            ((b[p + 12].toLong() and 0xFF) shl 7) or
            ((b[p + 13].toLong() and 0xFE) shr 1)
        return pts to headerLen
    }
}
