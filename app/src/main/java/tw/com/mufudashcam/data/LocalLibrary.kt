package tw.com.mufudashcam.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/** 相簿分頁的三種類別。 */
enum class MediaKind(val label: String) {
    /** 記錄器錄的原始影片：本 App 下載的 .TS、官方 App 與 Astro App 存的 MP4、自訂資料夾裡的其他影片。 */
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
    /** 哪一種行車記錄器（看檔名；已剪輯與截圖看來源影片）。 */
    val brand: Brand = Brand.Other,
)

/**
 * 相簿收 App 自己的資料夾（Movies/MufuDashcam、Pictures/MufuDashcam，一定會讀），以及使用者在設定勾選的資料夾
 * （預設 [DEFAULT_FOLDERS]）；依檔名分成未剪輯／已剪輯／照片，並決定每個檔案的時間。
 * 剪輯檔名對應 [tw.com.mufudashcam.ui.editor.EditorScreen] 的輸出：
 * 已剪輯 `<原檔名>_0m10s0-0m15s0[_靜音].mp4`、截圖 `<原檔名>_0m15s0.jpg`。
 * 同名檔案已存在時 MediaStore 會自動改名成 `… (1).mp4`，這種也認得。
 * 官方 App 存的也認得：`EMER…F_P1.mp4` 是原始檔，`video_2026-0922-150830_10_E_O_N_38072.mp4` 是剪輯（檔名時間 = 存檔時間）。
 */
object LocalLibrary {
    private val MOVIES = "Movies/${DashcamConfig.LOCAL_DIR}/"
    private val PICTURES = "Pictures/${DashcamConfig.LOCAL_DIR}/"

    /** 相簿預設也讀的資料夾：Astro 記錄器 App（com.ligo.aAstroCam）存原始影片的地方。 */
    val DEFAULT_FOLDERS: Set<String> = setOf("Movies/astoramily/")

    /** 認得的資料夾 → 記錄器種類（檔名看不出種類時用）。 */
    private val FOLDER_BRANDS = mapOf("Movies/astoramily/" to Brand.Astro)

    private val EDITED_RE =
        Regex("""^(.+)_(\d+)m(\d{2})s(\d)-(\d+)m(\d{2})s(\d)(_靜音)?(?: \(\d+\))?\.mp4$""", RegexOption.IGNORE_CASE)
    private val SHOT_RE = Regex("""^(.+)_(\d+)m(\d{2})s(\d)(?: \(\d+\))?\.jpe?g$""", RegexOption.IGNORE_CASE)

    /** 官方 App 的剪輯：video_年-月日-時分秒_秒數_E_O_N_亂數.mp4（時間是存檔時間，不是錄影時間）。 */
    private val OFFICIAL_EDIT_RE =
        Regex("""^video_(\d{4})-(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})_\d+_E_O_N_\d+(?: \(\d+\))?\.mp4$""", RegexOption.IGNORE_CASE)

    /** App 自己的資料夾（含子資料夾）：下載與匯出都放這裡，相簿一定會讀。 */
    fun isAppFolder(relativePath: String): Boolean =
        relativePath.startsWith(MOVIES, ignoreCase = true) || relativePath.startsWith(PICTURES, ignoreCase = true)

    /** App 自己的資料夾或 [folders] 裡（含子資料夾）。 */
    fun isIncluded(relativePath: String, folders: Collection<String>): Boolean =
        isAppFolder(relativePath) || folders.any { relativePath.startsWith(it, ignoreCase = true) }

    /**
     * MediaStore 的一列 → [LocalMedia]；不在相簿資料夾（App 的資料夾與 [folders]）的檔案回 null。
     * [isImage] = 這列來自照片的 MediaStore（預設看副檔名）。
     */
    fun build(
        uri: String,
        name: String,
        relativePath: String,
        sizeBytes: Long,
        durationMs: Long,
        dateAddedSec: Long,
        dateTakenMs: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
        isImage: Boolean = FileNames.isImage(name),
        folders: Collection<String> = DEFAULT_FOLDERS,
    ): LocalMedia? {
        if (!isIncluded(relativePath, folders)) return null
        val added = LocalDateTime.ofInstant(Instant.ofEpochSecond(dateAddedSec), zone)
        val taken = dateTakenMs?.takeIf { it > 0 }?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
        val folderBrand = FOLDER_BRANDS.entries.firstOrNull { relativePath.startsWith(it.key, ignoreCase = true) }?.value
        if (isImage) {
            val info = photoInfo(name)
            return LocalMedia(
                uri, name, MediaKind.Photo, photoTime(name) ?: taken ?: added, sizeBytes, 0,
                camera = info?.camera,
                brand = info?.brand ?: folderBrand ?: Brand.Other,
            )
        }
        OFFICIAL_EDIT_RE.matchEntire(name)?.let { m ->
            return LocalMedia(uri, name, MediaKind.Edited, officialEditTime(m) ?: added, sizeBytes, durationMs, brand = Brand.Mufu)
        }
        EDITED_RE.matchEntire(name)?.let { m ->
            val source = m.groupValues[1]
            val sourceInfo = FileNames.parse("$source.TS")
            return LocalMedia(
                uri, name, MediaKind.Edited, added, sizeBytes, durationMs,
                source = source,
                rangeMs = ms(m, 2)..ms(m, 5),
                muted = m.groupValues[8].isNotEmpty(),
                camera = sourceInfo?.camera,
                brand = sourceInfo?.brand ?: Brand.Other,
            )
        }
        val info = FileNames.parse(name)
        val inAppMovies = relativePath.startsWith(MOVIES, ignoreCase = true)
        // App 資料夾裡看不出來源的 MP4：本 App 匯出、後來改過名的剪輯
        if (info == null && inAppMovies && FileNames.extension(name) == "MP4") {
            return LocalMedia(uri, name, MediaKind.Edited, added, sizeBytes, durationMs)
        }
        // 原始影片（記錄器的 TS、官方 App 的 _P1／_P2、Astro 的 MP4，或自訂資料夾裡的其他影片）：時間、種類看檔名
        return LocalMedia(
            uri, name, MediaKind.Original, info?.dateTime ?: taken ?: added, sizeBytes, durationMs,
            camera = info?.camera,
            brand = info?.brand ?: folderBrand ?: if (inAppMovies) Brand.Mufu else Brand.Other,
        )
    }

    private fun officialEditTime(m: MatchResult): LocalDateTime? = runCatching {
        val v = m.groupValues.drop(1).map { it.toInt() }
        LocalDateTime.of(v[0], v[1], v[2], v[3], v[4], v[5])
    }.getOrNull()

    /** 影片開頭的實際錄影時間：原始檔看檔名；剪輯檔 = 來源開始時間 + 片段起點。看不出來回 null。 */
    fun recordingStart(name: String): LocalDateTime? {
        FileNames.parse(name)?.dateTime?.let { return it }
        val m = EDITED_RE.matchEntire(name) ?: return null
        val start = FileNames.parse(m.groupValues[1] + ".TS")?.dateTime ?: return null
        return start.plusNanos(ms(m, 2) * 1_000_000)
    }

    /** 記錄器照片看檔名；截圖 = 原影片開始時間 + 截圖位置。 */
    private fun photoTime(name: String): LocalDateTime? {
        FileNames.parse(name)?.dateTime?.let { return it }
        val m = SHOT_RE.matchEntire(name) ?: return null
        val start = FileNames.parse(m.groupValues[1] + ".TS")?.dateTime ?: return null
        return start.plusNanos(ms(m, 2) * 1_000_000)
    }

    /** 鏡頭、記錄器種類：記錄器照片看檔名；截圖看來源影片。 */
    private fun photoInfo(name: String): FileNames.Info? =
        FileNames.parse(name)
            ?: SHOT_RE.matchEntire(name)?.let { FileNames.parse(it.groupValues[1] + ".TS") }

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

    /**
     * 剪輯輸出的檔名 `<原檔名>_0m10s0-0m15s0[_靜音].mp4`。格式要符合 [EDITED_RE]，
     * 相簿才分辨得出「已剪輯」並顯示來源與片段，所以檔名只在這裡組。
     */
    fun editedName(videoName: String, startMs: Long, endMs: Long, muted: Boolean): String {
        val base = videoName.substringBeforeLast('.').ifBlank { "clip" }
        return "${base}_${nameTime(startMs)}-${nameTime(endMs)}${if (muted) "_靜音" else ""}.mp4"
    }

    /** 檔名用的時間：0m12s3 */
    fun nameTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "${t / 60000}m${(t / 1000 % 60).toString().padStart(2, '0')}s${t / 100 % 10}"
    }

    /**
     * 跨段剪輯：同鏡頭、錄影時間最接近的下一段（[forward]）或前一段原始檔。
     * 不限檔名前綴（緊急錄影後面接的是一般錄影）；是否首尾相接由呼叫端用實際長度確認。
     */
    fun adjacent(items: List<LocalMedia>, name: String, forward: Boolean): LocalMedia? {
        val start = FileNames.parse(name)?.dateTime ?: return null
        val camera = FileNames.cameraOfName(name)
        return items
            .mapNotNull { m ->
                val t = FileNames.parse(m.name)?.dateTime
                if (m.kind != MediaKind.Original || t == null || FileNames.cameraOfName(m.name) != camera) return@mapNotNull null
                if (if (forward) t > start else t < start) m to t else null
            }
            .minByOrNull { (_, t) -> abs(Duration.between(start, t).toMillis()) }
            ?.first
    }

    /** 整理空間：已經剪輯過的原始檔（檔名去掉副檔名 = 某支剪輯檔的來源）。 */
    fun editedOriginals(items: List<LocalMedia>): List<LocalMedia> {
        val sources = items.filter { it.kind == MediaKind.Edited }.mapNotNullTo(HashSet()) { it.source?.uppercase() }
        return items.filter { it.kind == MediaKind.Original && it.name.substringBeforeLast('.').uppercase() in sources }
    }

    /**
     * 整理空間：[cutoff] 之前錄的原始檔。只算檔名看得出是記錄器錄的，
     * 自訂資料夾裡的其他影片（例如手機相機拍的）不會被當成可以清掉的原始檔。
     */
    fun originalsRecordedBefore(items: List<LocalMedia>, cutoff: LocalDateTime): List<LocalMedia> =
        items.filter { it.kind == MediaKind.Original && it.time < cutoff && FileNames.parse(it.name) != null }

    /** 最新的在最上面。 */
    fun sorted(items: List<LocalMedia>): List<LocalMedia> =
        items.sortedWith(compareByDescending<LocalMedia> { it.time }.thenByDescending { it.name })
}
