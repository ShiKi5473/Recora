package tw.com.recora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.recora.ui.editor.TsRangeBytes
import java.io.ByteArrayOutputStream

class TsRangeBytesTest {
    private val mainPid = 0x101
    private val subPid = 0x102
    private val audioPid = 0x103

    /**
     * 3 秒、30 fps 的假 TS（仿記錄器）：H.265 主串流每格 2 個封包、H.264 子串流每格 1 個封包，
     * 每 30 格一個關鍵影格；16 kHz AAC 每 64 ms 一個封包。PTS 不從 0 開始。
     * 每個帶 PES 標頭的封包有 170 bytes 資料（關鍵影格多 2 bytes adaptation field，只剩 168），其他封包 184 bytes。
     */
    private fun fakeTs(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pat())
        out.write(pmt())
        val base = 900_000L
        var audio = 0
        for (frame in 0 until 90) {
            val pts = base + frame * 3000L
            val key = frame % 30 == 0
            out.write(pesPacket(mainPid, pts, key))
            out.write(continuation(mainPid))
            out.write(pesPacket(subPid, pts, key))
            while (audio * 64_000L <= frame * 33_333L) {
                out.write(pesPacket(audioPid, base + audio * 5760L, key = false))
                audio++
            }
        }
        return out.toByteArray()
    }

    @Test
    fun countsMainStreamFromPreviousKeyframe() {
        // 1.5～2.5 秒：畫面從 1.0 秒的關鍵影格（第 30 格）算到第 74 格，共 45 格，其中第 30、60 格是關鍵影格
        val (video, audio) = TsRangeBytes.count(fakeTs().inputStream(), 1500, 2500)!!
        assertEquals(2 * (168 + 184) + 43 * (170 + 184).toLong(), video)
        // 聲音：1536～2496 ms 的 16 個封包
        assertEquals(16 * 170L, audio)
    }

    @Test
    fun wholeFileAndDetection() {
        val ts = fakeTs()
        assertTrue(TsRangeBytes.looksLikeTs(ts.copyOf(376)))
        assertFalse(TsRangeBytes.looksLikeTs(ByteArray(376)))
        val (video, _) = TsRangeBytes.count(ts.inputStream(), 0, null)!!
        assertEquals(3 * (168 + 184) + 87 * (170 + 184).toLong(), video)
        // 沒有 PAT／PMT（不是 TS）：找不到畫面
        assertNull(TsRangeBytes.count(ByteArray(188 * 4).inputStream(), 0, null))
    }

    private fun packet(pid: Int, start: Boolean, adaptation: ByteArray?, payload: ByteArray): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = ((if (adaptation != null) 0x30 else 0x10)).toByte()
        var i = 4
        adaptation?.let { af ->
            p[i++] = af.size.toByte()
            af.copyInto(p, i)
            i += af.size
        }
        payload.copyInto(p, i, 0, minOf(payload.size, 188 - i))
        return p
    }

    private fun section(bytes: List<Int>): ByteArray = (listOf(0) + bytes + listOf(0, 0, 0, 0)).map { it.toByte() }.toByteArray()

    private fun pat() = packet(0, true, null, section(listOf(0x00, 0xB0, 13, 0, 1, 0xC1, 0, 0, 0, 1, 0xE1, 0x00)))

    private fun pmt(): ByteArray {
        val entries = listOf(0x24 to mainPid, 0x1B to subPid, 0x0F to audioPid)
            .flatMap { (type, pid) -> listOf(type, 0xE0 or (pid shr 8), pid and 0xFF, 0xF0, 0) }
        val body = listOf(0, 1, 0xC1, 0, 0, 0xE1, 0x01, 0xF0, 0) + entries
        return packet(0x100, true, null, section(listOf(0x02, 0xB0, body.size + 4) + body))
    }

    /** 一個帶 PES 標頭（含 PTS）的封包，填滿資料。 */
    private fun pesPacket(pid: Int, pts: Long, key: Boolean): ByteArray {
        val header = byteArrayOf(
            0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or ((pts shr 29).toInt() and 0x0E)).toByte(),
            (pts shr 22).toByte(),
            (((pts shr 14).toInt() and 0xFE) or 1).toByte(),
            (pts shr 7).toByte(),
            (((pts shl 1).toInt() and 0xFE) or 1).toByte(),
        )
        return packet(pid, true, if (key) byteArrayOf(0x40) else null, header + ByteArray(184))
    }

    private fun continuation(pid: Int) = packet(pid, false, null, ByteArray(184))
}
