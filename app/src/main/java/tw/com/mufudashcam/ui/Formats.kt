package tw.com.mufudashcam.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formats {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd（E）", Locale.TAIWAN)
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun size(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun speed(bytesPerSecond: Long): String = size(bytesPerSecond) + "/s"

    fun progress(bytes: Long, total: Long): String =
        if (total > 0) "${size(bytes)} / ${size(total)}" else size(bytes)

    fun time(t: java.time.LocalDateTime?): String = t?.format(TIME) ?: "--:--:--"

    fun date(d: LocalDate?): String = d?.format(DATE) ?: "日期不明"

    fun dateTime(t: java.time.LocalDateTime): String = t.format(DATE_TIME)

    /** 影片長度 / 片段位置：1:05 或 0:10.5 */
    fun duration(ms: Long, tenths: Boolean = false): String {
        val t = ms.coerceAtLeast(0)
        val base = String.format(Locale.ROOT, "%d:%02d", t / 60000, t / 1000 % 60)
        return if (tenths) "$base.${t / 100 % 10}" else base
    }
}
