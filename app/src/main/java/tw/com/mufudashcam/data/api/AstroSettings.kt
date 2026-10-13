package tw.com.mufudashcam.data.api

import tw.com.mufudashcam.data.SettingDef
import tw.com.mufudashcam.data.SettingOption

/**
 * Astro 的記錄器設定（ASTRO_LEO3_API.md §2.5）。key = Novatek 的 Cmd，值 = 3014 的 Status = 寫入時的 par。
 * 標題用官方 App 的繁中字串；選項以記錄器回報的為準（3031 的選單、3030 的解析度），讀不到才用這裡的備用選項。
 */
object AstroSettings {
    private val ON_OFF = listOf(SettingOption("0", "關"), SettingOption("1", "開"))

    // 下面的 KNOWN 會用到，要先初始化
    private val EV = listOf("+2.0", "+1.6", "+1.3", "+1.0", "+0.6", "+0.3", "0", "-0.3", "-0.6", "-1.0", "-1.3", "-1.6", "-2.0")

    private data class Known(
        val cmd: String,
        val title: String,
        val fallback: List<SettingOption> = ON_OFF,
        val hint: String? = null,
        val advanced: Boolean = false,
        val deferred: Boolean = false,
    )

    private val KNOWN = listOf(
        Known("2002", "錄影分辨率", emptyList(), hint = "官方 App：解析度會在中斷 Wi-Fi 連線後才變更", deferred = true),
        Known("2003", "循環錄影時間", opts("0" to "1 分鐘", "1" to "3 分鐘", "2" to "5 分鐘"), hint = "每段錄影的長度"),
        Known("2005", "曝光值", (0..12).map { SettingOption("$it", EV[it]) }),
        Known("2007", "錄音"),
        Known("2008", "日期標誌"),
        Known("2023", "後鏡頭鏡像"),
        Known("8101", "光源頻率", opts("0" to "50Hz", "1" to "60Hz")),
        Known("8103", "時區", (0..26).map { SettingOption("$it", utcLabel(14 - it)) }, hint = "對時送的是 UTC，記錄器用這裡的時區換算成檔名的當地時間"),
        Known("8104", "GPS浮水印"),
        Known("8105", "速度水印"),
        Known("8113", "夜間模式", hint = "官方說明：17:00～06:00 強制 2560×1440 30fps HDR，切換時約有 6 秒空檔"),
        Known("2004", "寬動態", advanced = true),
        Known("2006", "移動偵測", advanced = true),
        Known("2012", "自動錄影", advanced = true),
    )

    /** 3014 有、但不能改（選項不明或不是設定）的項目，唯讀顯示時的名稱。 */
    private val READ_ONLY_NAMES = mapOf(
        "1002" to "照片解析度",
        "2011" to "碰撞感應靈敏度",
        "3007" to "自動關機",
        "3008" to "語言",
        "3009" to "電視制式",
        "3028" to "即時畫面",
    )

    /** 錄影狀態，不當成設定顯示。 */
    private val HIDDEN = setOf("2001", "2016")

    private fun opts(vararg pairs: Pair<String, String>) = pairs.map { SettingOption(it.first, it.second) }

    private fun utcLabel(hours: Int) = if (hours >= 0) "UTC+$hours" else "UTC$hours"

    /**
     * [status] = 3014、[menu] = 3031、[sizes] = 3030（讀不到時傳空的）。記錄器有回報目前值的已知項目才可以改；
     * [extra] 放在唯讀清單最前面（韌體版本、型號）。
     */
    fun snapshot(
        status: Map<String, String>,
        menu: List<NovatekParsers.MenuItem>,
        sizes: List<Pair<String, String>>,
        extra: Map<String, String> = emptyMap(),
    ): DashcamApi.SettingsSnapshot {
        val menuByCmd = menu.associateBy { it.cmd }
        val defs = KNOWN.filter { it.cmd in status }.mapNotNull { k ->
            val options = when {
                k.cmd == "2002" -> sizes.map { (index, name) -> SettingOption(index, sizeLabel(name)) }
                else -> menuByCmd[k.cmd]?.options?.map { (index, id) -> SettingOption(index, optionLabel(id)) }
                    ?.takeIf { it.isNotEmpty() } ?: k.fallback
            }
            if (options.isEmpty()) return@mapNotNull null
            SettingDef(k.cmd, k.title, options, hint = k.hint, advanced = k.advanced, deferred = k.deferred)
        }
        val editable = defs.map { it.key }.toSet()
        val readOnly = LinkedHashMap(extra)
        status.filterKeys { it !in editable && it !in HIDDEN }.toSortedMap().forEach { (cmd, value) ->
            readOnly[READ_ONLY_NAMES[cmd]?.let { "$it（$cmd）" } ?: "Cmd $cmd"] = value
        }
        return DashcamApi.SettingsSnapshot(defs, status.filterKeys { it in editable }, readOnly)
    }

    /** 3030 的名稱：2560x1440P30 HDR → 2560×1440 30fps HDR */
    fun sizeLabel(name: String): String =
        Regex("""^(\d+)[xX](\d+)P(\d+)(.*)$""").matchEntire(name.trim())?.let { m ->
            "${m.groupValues[1]}×${m.groupValues[2]} ${m.groupValues[3]}fps${m.groupValues[4]}"
        } ?: name

    /** 3031 的選項：ON／OFF → 開／關、1MIN → 1 分鐘、50HZ → 50Hz，其他原樣。 */
    fun optionLabel(id: String): String {
        val t = id.trim()
        Regex("""^(\d+)MIN$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { return "${it.groupValues[1]} 分鐘" }
        Regex("""^(\d+)HZ$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { return "${it.groupValues[1]}Hz" }
        return when (t.uppercase()) {
            "ON" -> "開"
            "OFF" -> "關"
            else -> t
        }
    }
}
