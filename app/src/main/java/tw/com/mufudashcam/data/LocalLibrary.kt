package tw.com.mufudashcam.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 相簿分頁的三種類別。 */
enum class MediaKind(val label: String) {
    /** 從記錄器下載的原始影片（.TS）。 */
    Original("未剪輯"),

    /** 剪輯匯出的 MP4。 */
    Edited("已剪輯"),

    /** 記錄器照片與剪輯時的截圖。 */
    Photo("照片"),
}

/** 相簿裡的一個檔案。uri 用字串，讓分類邏輯可在 JVM 單元測試執行。 */
data class LocalMedia(
    val uri: String,
    val name: String,
    val kind: MediaKind,
    /** 排序與顯示用的時間：未剪輯／照片 = 拍攝時間；已剪輯 = 剪輯（匯出）時間。 */
    val time: LocalDateTime,
    val sizeBytes: Long,
    val durationMs: Long,
    /** 已剪輯：來源影片檔名（不含副檔名）。 */
    val source: String? = null,
    /** 已剪輯：片段起訖（毫秒）。 */
    val rangeMs: LongRange? = null,
    /** 已剪輯：匯出時去除了聲音。 */
    val muted: Boolean = false,
    /** 前／後鏡頭（看檔名結尾的 F／R；已剪輯與截圖看來源影片），分辨不出為 null。 */
    val camera: Camera? = null,
)

/**
 * 只收 App 自己的資料夾（Movies/MufuDashcam、Pictures/MufuDashcam），分成未剪輯／已剪輯／照片，
 * 並決定每個檔案的時間。檔名規則對應 [tw.com.mufudashcam.ui.editor.EditorScreen] 的輸出：
 * 已剪輯 `<原檔名>_0m10s0-0m15s0[_靜音].mp4`、截圖 `<原檔名>_0m15s0.jpg`。
 */
object LocalLibrary {
    private val MOVIES = "Movies/${DashcamConfig.LOCAL_DIR}/"
    private val PICTURES = "Pictures/${DashcamConfig.LOCAL_DIR}/"
    private val EDITED_RE = Regex("""^(.+)_(\d+)m(\d{2})s(\d)-(\d+)m(\d{2})s(\d)(_靜音)?\.mp4$""", RegexOption.IGNORE_CASE)
    private val SHOT_RE = Regex("""^(.+)_(\d+)m(\d{2})s(\d)\.jpe?g$""", RegexOption.IGNORE_CASE)

    /** MediaStore 的一列 → [LocalMedia]；不是 App 資料夾的檔案回 null。 */
    fun build(
        uri: String,
        name: String,
        relativePath: String,
        sizeBytes: Long,
        durationMs: Long,
        dateAddedSec: Long,
        dateTakenMs: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalMedia? {
        val added = LocalDateTime.ofInstant(Instant.ofEpochSecond(dateAddedSec), zone)
        val taken = dateTakenMs?.takeIf { it > 0 }?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
        return when {
            relativePath.startsWith(MOVIES, ignoreCase = true) -> {
                if (FileNames.extension(name) == "MP4") {
                    val m = EDITED_RE.matchEntire(name)
                    val source = m?.groupValues?.get(1)
                    LocalMedia(
                        uri, name, MediaKind.Edited, added, sizeBytes, durationMs,
                        source = source,
                        rangeMs = m?.let { ms(it, 2)..ms(it, 5) },
                        muted = m?.groupValues?.get(8)?.isNotEmpty() == true,
                        camera = source?.let { FileNames.parse("$it.TS")?.camera },
                    )
                } else {
                    val info = FileNames.parse(name)
                    LocalMedia(
                        uri, name, MediaKind.Original, info?.dateTime ?: taken ?: added, sizeBytes, durationMs,
                        camera = info?.camera,
                    )
                }
            }
            relativePath.startsWith(PICTURES, ignoreCase = true) ->
                LocalMedia(
                    uri, name, MediaKind.Photo, photoTime(name) ?: taken ?: added, sizeBytes, 0,
                    camera = photoCamera(name),
                )
            else -> null
        }
    }

    /** 記錄器照片看檔名；截圖 = 原影片開始時間 + 截圖位置。 */
    private fun photoTime(name: String): LocalDateTime? {
        FileNames.parse(name)?.dateTime?.let { return it }
        val m = SHOT_RE.matchEntire(name) ?: return null
        val start = FileNames.parse(m.groupValues[1] + ".TS")?.dateTime ?: return null
        return start.plusNanos(ms(m, 2) * 1_000_000)
    }

    /** 記錄器照片看檔名；截圖看來源影片。 */
    private fun photoCamera(name: String): Camera? =
        FileNames.parse(name)?.camera
            ?: SHOT_RE.matchEntire(name)?.let { FileNames.parse(it.groupValues[1] + ".TS")?.camera }

    /** 由 group(i..i+2) 的 分、秒、十分之一秒 算出毫秒。 */
    private fun ms(m: MatchResult, i: Int): Long =
        m.groupValues[i].toLong() * 60_000 + m.groupValues[i + 1].toLong() * 1000 + m.groupValues[i + 2].toLong() * 100

    /**
     * 截圖檔名 `<原影片>_<位置>.jpg`，讓相簿能算出畫面的實際拍攝時間。
     * 在已剪輯的 MP4 上截圖時，換算回原影片與原影片中的位置（片段起點 + 播放位置）。
     */
    fun screenshotName(videoName: String, positionMs: Long): String {
        val edited = EDITED_RE.matchEntire(videoName)
        return if (edited != null) {
            "${edited.groupValues[1]}_${nameTime(ms(edited, 2) + positionMs)}.jpg"
        } else {
            "${videoName.substringBeforeLast('.').ifBlank { "video" }}_${nameTime(positionMs)}.jpg"
        }
    }

    /** 檔名用的時間：0m12s3 */
    fun nameTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "${t / 60000}m${(t / 1000 % 60).toString().padStart(2, '0')}s${t / 100 % 10}"
    }

    /** 最新的在最上面。 */
    fun sorted(items: List<LocalMedia>): List<LocalMedia> =
        items.sortedWith(compareByDescending<LocalMedia> { it.time }.thenByDescending { it.name })
}
