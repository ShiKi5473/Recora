package tw.com.mufudashcam.data

import java.time.LocalDateTime
import java.util.Locale

enum class Camera { Front, Rear }

/**
 * 下載網址的兩種路徑樣式：Mnt = 列表回傳的完整路徑（/mnt/mmc/Video/F/...），
 * Root = 去掉 /mnt/mmc（/Video/F/...）。實測 V60P 是 Mnt；保留 Root 當備用。
 */
enum class PathStyle { Mnt, Root }

/** 記錄器檔名與路徑規則（對應 dashcam_download.py 的 to_url / rear_of）。 */
object FileNames {
    private val NAME_RE = Regex("""^([A-Za-z]+)(\d{6})-(\d{6})([FR])?\.(\w+)$""", RegexOption.IGNORE_CASE)
    private val FRONT_RE = Regex("""/F/([^/]+)F\.(\w+)$""")
    private val IMAGE_EXT = setOf("JPG", "JPEG", "PNG")

    /** FILE260923-131459F.TS → prefix=FILE, date=260923, time=131459, camera=Front, ext=TS */
    data class Info(
        val prefix: String,
        val date: String,
        val time: String,
        val camera: Camera?,
        val ext: String,
    ) {
        /** 前後鏡頭配對用：同一時間點的前後檔案 key 相同。 */
        val key: String get() = "$prefix$date-$time"

        val dateTime: LocalDateTime?
            get() = runCatching {
                LocalDateTime.of(
                    2000 + date.substring(0, 2).toInt(), date.substring(2, 4).toInt(), date.substring(4, 6).toInt(),
                    time.substring(0, 2).toInt(), time.substring(2, 4).toInt(), time.substring(4, 6).toInt(),
                )
            }.getOrNull()
    }

    fun parse(name: String): Info? {
        val m = NAME_RE.matchEntire(name) ?: return null
        val (prefix, date, time, cam, ext) = m.destructured
        val camera = when (cam.uppercase()) {
            "F" -> Camera.Front
            "R" -> Camera.Rear
            else -> null
        }
        return Info(prefix.uppercase(), date, time, camera, ext.uppercase())
    }

    fun basename(path: String): String = path.substringAfterLast('/')

    /** 列表 <name> → SD 卡完整路徑。`$` 分隔的路徑轉成 `/`，只有檔名時補上預設的前鏡頭目錄。 */
    fun sdPath(rawName: String, folder: Folder): String {
        val p = rawName.trim().replace('$', '/')
        return if (p.startsWith("/")) p else "${DashcamConfig.SD_ROOT}/${folder.dirName}/F/$p"
    }

    fun cameraOf(path: String): Camera? = when {
        "/R/" in path -> Camera.Rear
        "/F/" in path -> Camera.Front
        else -> parse(basename(path))?.camera
    }

    /** 前鏡頭路徑 → 對應的後鏡頭路徑（/F/xxxF.TS → /R/xxxR.TS），不是前鏡頭則回 null。 */
    fun rearPathOf(frontPath: String): String? {
        val m = FRONT_RE.find(frontPath) ?: return null
        return frontPath.replaceRange(m.range, "/R/${m.groupValues[1]}R.${m.groupValues[2]}")
    }

    fun httpPath(sdPath: String, style: PathStyle): String = when (style) {
        PathStyle.Mnt -> sdPath
        PathStyle.Root -> sdPath.removePrefix(DashcamConfig.SD_ROOT).ifEmpty { "/" }
    }

    /** action=del 用的 property：路徑的 `/` 換成 `$`。 */
    fun deleteProperty(sdPath: String): String = sdPath.replace('/', '$')

    /** TimeSettings 的值：YYYY$MM$DD$hh$mm$ss（官方 App 實際送出的格式，結尾沒有 API 文件寫的 `$`）。 */
    fun timeSettingsValue(t: LocalDateTime): String = String.format(
        Locale.ROOT, "%04d\$%02d\$%02d\$%02d\$%02d\$%02d",
        t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second,
    )

    /**
     * 查詢參數的百分比編碼，但 `*` 與 `$` 原樣送出：
     * `Camera.Menu.*` 的萬用字元要原樣；實測記錄器的 CGI 不會把 `%24` 解回 `$`，
     * del 的路徑與 TimeSettings 都必須送原始的 `$`（送 `%24` 會回 "no key found!"）。
     */
    fun encodeQueryValue(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~*$") {
                sb.append(ch)
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", c))
            }
        }
        return sb.toString()
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").uppercase()

    fun isImage(name: String): Boolean = extension(name) in IMAGE_EXT

    fun mimeType(name: String): String = when (extension(name)) {
        "TS" -> "video/mp2t"
        "MP4" -> "video/mp4"
        "MOV" -> "video/quicktime"
        "JPG", "JPEG" -> "image/jpeg"
        "PNG" -> "image/png"
        else -> "application/octet-stream"
    }
}
