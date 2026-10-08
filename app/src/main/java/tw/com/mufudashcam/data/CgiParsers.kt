package tw.com.mufudashcam.data

/**
 * Config.cgi 回應解析。dir 的 XML 很單純，用正規式解析（同 dashcam_download.py），
 * 也方便在 JVM 單元測試執行。根元素名稱不固定（實測 Normal 回 `<Video>`），所以不看根元素。
 */
object CgiParsers {
    data class DirEntry(
        val name: String,
        val format: String,
        val size: Long,
        val attr: String,
        val time: String,
    )

    data class DirPage(val entries: List<DirEntry>, val amount: Int?)

    private val FILE_RE = Regex("<file>(.*?)</file>", RegexOption.DOT_MATCHES_ALL)
    private val TAG_RE = Regex("""<(\w+)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val AMOUNT_RE = Regex("""<amount>\s*(\d+)\s*</amount>""")
    /** 一行一組 `鍵=值`；API 文件說結尾是 `;;`，實機沒有，兩種都接受。 */
    private val KV_RE = Regex("""^\s*([A-Za-z][\w.\-]*)=([^\r\n]*?)(?:;;)?\s*$""", RegexOption.MULTILINE)

    fun parseDir(xml: String): DirPage {
        val entries = FILE_RE.findAll(xml).mapNotNull { block ->
            val tags = TAG_RE.findAll(block.groupValues[1])
                .associate { it.groupValues[1] to unescape(it.groupValues[2].trim()) }
            val name = tags["name"].orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            DirEntry(
                name = name,
                format = tags["format"].orEmpty(),
                size = tags["size"]?.toLongOrNull() ?: 0L,
                attr = tags["attr"].orEmpty(),
                time = tags["time"].orEmpty(),
            )
        }.toList()
        val amount = AMOUNT_RE.find(xml)?.groupValues?.get(1)?.toIntOrNull()
        return DirPage(entries, amount)
    }

    /**
     * action=get 的回應：每行 `鍵=值`（實機沒有文件寫的 `;;`）。實測回應中還會夾雜 `0`、`OK`、
     * `Usage ./CGI_PROCESS.sh ...` 等雜訊行，只取符合格式的部分。
     */
    fun parseKeyValues(body: String): Map<String, String> =
        KV_RE.findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
