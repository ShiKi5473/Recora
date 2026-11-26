package tw.com.recora.data

import java.time.LocalDateTime
import java.util.Locale

enum class Camera { Front, Rear }

/** 檔案來自哪一種行車記錄器（看檔名格式）。 */
enum class Brand(val label: String) {
    Mufu("Mufu"),
    Astro("Astro"),

    /** 看不出來源，例如改過名的檔案。 */
    Other("其它"),
    ;

    companion object {
        /** App 能連線的記錄器（連線卡片的切換順序）。 */
        val dashcams: List<Brand> = listOf(Mufu, Astro)
    }
}

/**
 * 下載網址的兩種路徑樣式：Mnt = 列表回傳的完整路徑（/mnt/mmc/Video/F/...），
 * Root = 去掉 /mnt/mmc（/Video/F/...）。實測 V60P 是 Mnt；保留 Root 當備用。
 */
enum class PathStyle { Mnt, Root }

/** 記錄器檔名與路徑規則（對應 dashcam_download.py 的 to_url / rear_of）。 */
object FileNames {
    /** 記錄器檔名；官方 App 下載的會多 `_P1`（前鏡頭）／`_P2`（後鏡頭）：EMER260909-090223F_P1.mp4 */
    private val NAME_RE = Regex("""^([A-Za-z]+)(\d{6})-(\d{6})([FR])?(?:_P\d)?\.(\w+)$""", RegexOption.IGNORE_CASE)
    private val FRONT_RE = Regex("""/F/([^/]+)F\.(\w+)$""")
    private val REAR_RE = Regex("""/R/([^/]+)R\.(\w+)$""")

    /** Astro（Novatek）的資料夾：/DCIM/VIDEO_F/xxxF.MP4 ↔ /DCIM/VIDEO_R/xxxR.MP4。 */
    private val NVT_FRONT_RE = Regex("""_F/([^/]+)F\.(\w+)$""")
    private val NVT_REAR_RE = Regex("""_R/([^/]+)R\.(\w+)$""")

    /** Astro 行車記錄器（Astro 的 App 存到手機的）：20260814081925_002902F.MP4 = 年月日時分秒_序號＋鏡頭。 */
    private val ASTRO_RE = Regex("""^\d{2}(\d{6})(\d{6})_\d+([FR])?\.(\w+)$""", RegexOption.IGNORE_CASE)

    /**
     * 記錄器檔名開頭（前綴＋時間＋鏡頭；Astro 是時間_序號＋鏡頭），後面可以接剪輯片段或截圖位置：
     * FILE260923-131459F_0m10s0-0m15s0.mp4
     */
    private val CAMERA_RE = Regex("""^([A-Za-z]+\d{6}-\d{6}|\d{14}_\d+)([FR])(.*)$""", RegexOption.IGNORE_CASE)
    private val IMAGE_EXT = setOf("JPG", "JPEG", "PNG")

    /** FILE260923-131459F.TS → prefix=FILE, date=260923, time=131459, camera=Front, ext=TS */
    data class Info(
        val prefix: String,
        val date: String,
        val time: String,
        val camera: Camera?,
        val ext: String,
        val brand: Brand = Brand.Mufu,
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

    /** 也認得 Astro 的檔名（prefix 為空字串）。 */
    fun parse(name: String): Info? {
        NAME_RE.matchEntire(name)?.let { m ->
            val (prefix, date, time, cam, ext) = m.destructured
            return Info(prefix.uppercase(), date, time, camera(cam), ext.uppercase())
        }
        val m = ASTRO_RE.matchEntire(name) ?: return null
        val (date, time, cam, ext) = m.destructured
        return Info("", date, time, camera(cam), ext.uppercase(), Brand.Astro)
    }

    private fun camera(letter: String): Camera? = when (letter.uppercase()) {
        "F" -> Camera.Front
        "R" -> Camera.Rear
        else -> null
    }

    fun basename(path: String): String = path.substringAfterLast('/')

    /** 列表 <name> → SD 卡完整路徑。`$` 分隔的路徑轉成 `/`，只有檔名時補上預設的前鏡頭目錄。 */
    fun sdPath(rawName: String, folder: Folder): String {
        val p = rawName.trim().replace('$', '/')
        return if (p.startsWith("/")) p else "${DashcamConfig.SD_ROOT}/${folder.dirName}/F/$p"
    }

    fun cameraOf(path: String): Camera? = when {
        "/R/" in path || "_R/" in path -> Camera.Rear
        "/F/" in path || "_F/" in path -> Camera.Front
        else -> parse(basename(path))?.camera
    }

    /** 前鏡頭路徑 → 對應的後鏡頭路徑（/F/xxxF.TS → /R/xxxR.TS），不是前鏡頭則回 null。 */
    fun rearPathOf(frontPath: String): String? {
        val m = FRONT_RE.find(frontPath) ?: return null
        return frontPath.replaceRange(m.range, "/R/${m.groupValues[1]}R.${m.groupValues[2]}")
    }

    /**
     * 另一鏡頭的 SD 卡路徑：MUFU /F/xxxF.TS ↔ /R/xxxR.TS，Astro /DCIM/VIDEO_F/xxxF.MP4 ↔ /DCIM/VIDEO_R/xxxR.MP4；
     * 看不出鏡頭則回 null。
     */
    fun counterpartPath(sdPath: String): String? =
        rearPathOf(sdPath)
            ?: REAR_RE.find(sdPath)?.let { m -> sdPath.replaceRange(m.range, "/F/${m.groupValues[1]}F.${m.groupValues[2]}") }
            ?: NVT_FRONT_RE.find(sdPath)?.let { m -> sdPath.replaceRange(m.range, "_R/${m.groupValues[1]}R.${m.groupValues[2]}") }
            ?: NVT_REAR_RE.find(sdPath)?.let { m -> sdPath.replaceRange(m.range, "_F/${m.groupValues[1]}F.${m.groupValues[2]}") }

    /** 檔名看得出的鏡頭；剪輯檔、截圖檔看來源的部分。 */
    fun cameraOfName(name: String): Camera? = when (CAMERA_RE.matchEntire(name)?.groupValues?.get(2)?.uppercase()) {
        "F" -> Camera.Front
        "R" -> Camera.Rear
        else -> null
    }

    /**
     * 另一鏡頭的檔名：FILE260923-131459F.TS ↔ FILE260923-131459R.TS（剪輯檔、截圖檔名也適用），看不出鏡頭則回 null。
     * 官方 App 下載的前後鏡頭還差在 `_P1`／`_P2`：EMER260909-090223F_P1.mp4 ↔ EMER260909-090223R_P2.mp4。
     */
    fun counterpartName(name: String): String? {
        val m = CAMERA_RE.matchEntire(name) ?: return null
        val cam = m.groupValues[2]
        val other = when (cam) {
            "F" -> "R"
            "R" -> "F"
            "f" -> "r"
            else -> "f"
        }
        val rest = m.groupValues[3]
        val otherRest = when {
            rest.startsWith("_P1", ignoreCase = true) -> "_P2" + rest.drop(3)
            rest.startsWith("_P2", ignoreCase = true) -> "_P1" + rest.drop(3)
            else -> rest
        }
        return m.groupValues[1] + other + otherRest
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
     * 查詢參數的百分比編碼，預設保留 `*` 與 `$` 原樣送出（MUFU）：
     * `Camera.Menu.*` 的萬用字元要原樣；實測記錄器的 CGI 不會把 `%24` 解回 `$`，
     * del 的路徑與 TimeSettings 都必須送原始的 `$`（送 `%24` 會回 "no key found!"）。
     * Astro 可傳入 `keep = "\\:"` 保留路徑反斜線與冒號。
     */
    fun encodeQueryValue(value: String, keep: String = "*$"): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~" || ch in keep) {
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
