package tw.com.recora.data.api

import tw.com.recora.data.FileNames
import tw.com.recora.data.Folder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Astro（Novatek）回應的解析與路徑轉換（ASTRO_LEO3_API.md）。XML 都很單純，同 CgiParsers 用正規式解析，方便在 JVM 單元測試執行。
 */
object NovatekParsers {
    /** `<Function><Cmd/><Status/>[<Value/>|<String/>]</Function>` */
    data class Reply(val cmd: Int?, val status: Int?, val value: String?, val string: String?) {
        val ok: Boolean get() = status == 0
    }

    data class FileEntry(val name: String, val fpath: String, val size: Long, val time: String, val attr: Int?)

    /** 3031 的一個選單項目：選項是 Index → Id（例如 0 → 1MIN）。 */
    data class MenuItem(val cmd: String, val name: String, val options: List<Pair<String, String>>)

    private val CMD_RE = Regex("""<Cmd>\s*(-?\d+)\s*</Cmd>""")
    private val STATUS_RE = Regex("""<Status>\s*(-?\d+)\s*</Status>""")
    private val VALUE_RE = Regex("""<Value>(.*?)</Value>""", RegexOption.DOT_MATCHES_ALL)
    private val STRING_RE = Regex("""<String>(.*?)</String>""", RegexOption.DOT_MATCHES_ALL)
    private val PAIR_RE = Regex("""<Cmd>\s*(\d+)\s*</Cmd>\s*<Status>\s*(-?\d+)\s*</Status>""")
    private val FILE_RE = Regex("""<File>(.*?)</File>""", RegexOption.DOT_MATCHES_ALL)
    private val ITEM_RE = Regex("""<Item>(.*?)</Item>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG_RE = Regex("""<(\w+)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val OPTION_RE = Regex("""<Option>\s*<Index>\s*(\d+)\s*</Index>\s*<Id>(.*?)</Id>\s*</Option>""", RegexOption.DOT_MATCHES_ALL)
    private val SIZE_ITEM_RE = Regex("""<Name>(.*?)</Name>\s*<Index>\s*(\d+)\s*</Index>""", RegexOption.DOT_MATCHES_ALL)
    private val LIVE_RE = Regex("""<MovieLiveViewLink>(.*?)</MovieLiveViewLink>""", RegexOption.DOT_MATCHES_ALL)

    /** 檔名、路徑以外沒看過其他磁碟代號；刪除時要送回完整的 FPATH。 */
    const val DRIVE = "A:"

    fun parseReply(xml: String): Reply = Reply(
        cmd = CMD_RE.find(xml)?.groupValues?.get(1)?.toIntOrNull(),
        status = STATUS_RE.find(xml)?.groupValues?.get(1)?.toIntOrNull(),
        value = VALUE_RE.find(xml)?.groupValues?.get(1)?.trim(),
        string = STRING_RE.find(xml)?.groupValues?.get(1)?.trim(),
    )

    /** 3014：成對的 `<Cmd>X</Cmd><Status>值</Status>` → X → 值。 */
    fun parseStatusPairs(xml: String): Map<String, String> =
        PAIR_RE.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    /** 3002：支援的指令清單。 */
    fun parseCommandList(xml: String): List<Int> = CMD_RE.findAll(xml).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()

    /** 3015：沒有 NAME 或 FPATH 的項目略過。 */
    fun parseFileList(xml: String): List<FileEntry> = FILE_RE.findAll(xml).mapNotNull { block ->
        val tags = TAG_RE.findAll(block.groupValues[1]).associate { it.groupValues[1].uppercase() to it.groupValues[2].trim() }
        val fpath = tags["FPATH"].orEmpty()
        val name = tags["NAME"]?.takeIf { it.isNotEmpty() } ?: fpath.substringAfterLast('\\')
        if (name.isEmpty() || fpath.isEmpty()) return@mapNotNull null
        FileEntry(
            name = name,
            fpath = fpath,
            size = tags["SIZE"]?.toLongOrNull() ?: 0L,
            time = normalizeTime(tags["TIME"].orEmpty()),
            attr = tags["ATTR"]?.toIntOrNull(),
        )
    }.toList()

    /** 3031&str=all */
    fun parseMenu(xml: String): List<MenuItem> = ITEM_RE.findAll(xml).mapNotNull { item ->
        val body = item.groupValues[1]
        val cmd = CMD_RE.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
        val name = Regex("""<Name>(.*?)</Name>""").find(body)?.groupValues?.get(1)?.trim().orEmpty()
        MenuItem(cmd, name, OPTION_RE.findAll(body).map { it.groupValues[1] to it.groupValues[2].trim() }.toList())
    }.toList()

    /** 3030：這台實際能用的解析度，Index → 名稱（2002 的值用這裡的 Index）。 */
    fun parseMovieSizes(xml: String): List<Pair<String, String>> =
        SIZE_ITEM_RE.findAll(xml).map { it.groupValues[2] to it.groupValues[1].trim() }.toList()

    /** 2019：即時串流網址。 */
    fun parseLiveLink(xml: String): String? = LIVE_RE.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** `A:\DCIM\VIDEO_F\x.MP4` → `/DCIM/VIDEO_F/x.MP4`（App 裡的 sdPath，也是下載網址的路徑）。 */
    fun sdPathOf(fpath: String): String {
        val path = fpath.substringAfter(':').replace('\\', '/')
        return if (path.startsWith("/")) path else "/$path"
    }

    /** sdPath → 刪除時要送的 FPATH（反斜線、原樣送出）。 */
    fun fpathOf(sdPath: String): String = DRIVE + sdPath.replace('/', '\\')

    /**
     * 依資料夾分類：圖片 → 照片；EMERG（緊急，實測）→ 緊急；PARK → 停車；其他（VIDEO，實測）→ 一般。
     * 照片與停車的資料夾名稱還沒看過，所以用關鍵字比對。
     */
    fun folderOf(sdPath: String): Folder {
        val dir = sdPath.substringBeforeLast('/').substringAfterLast('/').uppercase()
        return when {
            FileNames.isImage(sdPath) || "PHOTO" in dir || "PIC" in dir -> Folder.Photo
            "EMER" in dir || "EVENT" in dir || "LOCK" in dir -> Folder.Event
            "PARK" in dir -> Folder.Parking
            else -> Folder.Normal
        }
    }

    /** `2026/07/06 08:36:42` → `2026-07-06 08:36:42`（同 MUFU 的格式，Clip 讀得懂）。 */
    fun normalizeTime(t: String): String = t.trim().replace('/', '-')

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** 對時送 UTC（官方 App 紀錄：手機 20:38:59（UTC+8）時送 12:38:59），記錄器再套自己的時區設定。 */
    fun utcDate(now: ZonedDateTime): String = now.withZoneSameInstant(ZoneOffset.UTC).format(DATE)

    fun utcTime(now: ZonedDateTime): String = now.withZoneSameInstant(ZoneOffset.UTC).format(TIME)
}
