package tw.com.mufudashcam.data

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/** 剪輯時間軸上的一段來源（跨段剪輯時依錄影時間接起來）。 */
data class Segment(val uri: String, val title: String, val durationMs: Long)

/** 跨段剪輯的時間換算：組合時間軸 = 各段依序接起來（純函式，方便測試）。 */
object EditPlan {
    /** 匯出時一段來源的剪輯範圍；[endMs] = null 表示剪到該段結尾（長度以檔案本身為準）。 */
    data class Piece(val segment: Int, val startMs: Long, val endMs: Long?)

    /** 比這短的片段不匯出（例如範圍只跨進下一段幾毫秒）。 */
    private const val MIN_PIECE_MS = 100L

    /** 每段在組合時間軸上的起點。 */
    fun offsets(durations: List<Long>): List<Long> = durations.runningFold(0L) { acc, d -> acc + d }.dropLast(1)

    /** 組合時間軸上的位置 → (第幾段, 段內位置)；超過結尾算在最後一段的結尾。 */
    fun locate(durations: List<Long>, positionMs: Long): Pair<Int, Long> {
        var offset = 0L
        durations.forEachIndexed { i, d ->
            if (positionMs < offset + d || i == durations.lastIndex) return i to (positionMs - offset).coerceIn(0, d)
            offset += d
        }
        return 0 to 0L
    }

    /** 組合時間軸的範圍拆成各段的剪輯：第一段從起點開始、中間的段整段、最後一段剪到終點。 */
    fun pieces(durations: List<Long>, startMs: Long, endMs: Long): List<Piece> {
        val out = ArrayList<Piece>()
        var offset = 0L
        durations.forEachIndexed { i, d ->
            val segStart = offset
            val segEnd = offset + d
            offset = segEnd
            val s = maxOf(startMs, segStart)
            val e = minOf(endMs, segEnd)
            if (e - s >= MIN_PIECE_MS) out += Piece(i, s - segStart, if (e >= segEnd) null else e - segStart)
        }
        return out
    }

    /**
     * 匯出檔名用的範圍：相對於第一段來源的開始（可以超過該檔長度），例如 2m50s0-3m20s0；
     * 這樣相簿顯示的來源與時間、截圖時間的換算都不用改。回傳 (第一段, 範圍)。
     */
    fun nameRange(durations: List<Long>, startMs: Long, endMs: Long): Pair<Int, LongRange>? {
        val first = pieces(durations, startMs, endMs).firstOrNull() ?: return null
        return first.segment to first.startMs..(first.startMs + (endMs - startMs))
    }

    /** 前一段結束的時間與下一段開始的時間差在 [toleranceMs] 內 = 首尾相接（檔名時間只到秒）。 */
    fun isContiguous(prevStart: LocalDateTime, prevDurationMs: Long, nextStart: LocalDateTime, toleranceMs: Long = 5_000): Boolean =
        abs(Duration.between(prevStart.plusNanos(prevDurationMs * 1_000_000), nextStart).toMillis()) <= toleranceMs
}
