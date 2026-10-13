package tw.com.mufudashcam.data

/**
 * 可在 App 內調整的記錄器設定。
 * 寫入格式是 `set&property=<key>&value=<值>`（不加 Camera.Menu. 前綴，實測加了不會生效）；
 * 讀取是 `Camera.Menu.<key>`。選項值取自官方 App 對此系列機型的定義與本機目前的值。
 * 記錄器對任何指令都回 OK，所以 App 會在設定後重新讀取確認是否真的套用。
 */
data class SettingOption(val value: String, val label: String)

data class SettingDef(
    val key: String,
    val title: String,
    val options: List<SettingOption>,
    /** 需要較久才生效的設定（改解析度時記錄器會重啟錄影，約 1 分鐘沒有回應）。 */
    val slowMs: Long = 0,
    /** 說明文字（取自官方 App）。 */
    val hint: String? = null,
    /** 官方 App 沒有提供的設定（記錄器內部有、可改，但原廠未開放給此機型）。 */
    val advanced: Boolean = false,
    /** 送出後記錄器不一定馬上套用（Astro 的解析度要等 Wi-Fi 斷線後才變），讀回的值沒變也不算失敗。 */
    val deferred: Boolean = false,
) {
    fun labelOf(value: String?): String =
        options.firstOrNull { it.value.equals(value, ignoreCase = true) }?.label ?: (value ?: "?")
}

object SettingDefs {
    private val ON_OFF = listOf(SettingOption("ON", "開"), SettingOption("OFF", "關"))

    private fun onOff(key: String, title: String) = SettingDef(key, title, ON_OFF)

    private fun opts(vararg pairs: Pair<String, String>) = pairs.map { SettingOption(it.first, it.second) }

    private val EV = opts("0" to "-3", "1" to "-2", "2" to "-1", "3" to "0", "4" to "+1", "5" to "+2", "6" to "+3")

    /**
     * 實測可改（改完讀回確認、再改回）的項目。
     * 主要設定的名稱、順序與官方 App（V60P）一致，對應的指令是從官方 App 的紀錄確認的；
     * 進階設定是官方 App 沒有提供、但記錄器接受修改的項目。其他設定記錄器會忽略寫入，只唯讀顯示。
     */
    val all: List<SettingDef> = listOf(
        onOff("ParkingGuard", "動作感測開關機"),
        onOff("SoundRecord", "麥克風"),
        SettingDef(
            "VideoClipTime", "錄影間隔", opts("1MIN" to "1 分鐘", "3MIN" to "3 分鐘"),
            hint = "每段錄影的長度，記憶卡滿時會從最舊的影片開始覆蓋",
        ),
        onOff("EmerRec", "緊急錄影"),
        SettingDef(
            "VideoRes", "影像解析度",
            opts("1440P30FPS" to "2560×1440 30fps WDR", "1080P60FPS" to "1920×1080 60fps WDR", "1080P30FPS" to "1920×1080 30fps HDR"),
            slowMs = 120_000,
            hint = "變更後記錄器約 1 分鐘沒有回應（重新啟動錄影）",
        ),
        onOff("TimeStamp", "浮水印"),
        SettingDef("Led", "主機指示燈", opts("ON" to "綠燈開啟", "OFF" to "關閉")),
        SettingDef("FrontEV", "前鏡頭 EV", EV),
        SettingDef("RearEV", "後鏡頭 EV", EV),
        // 以下官方 App 未提供
        SettingDef(
            "ParkingMonitor", "停車監控", opts("ENABLE" to "開", "DISABLE" to "關"), advanced = true,
            hint = "官方說明：停車關機時感應到碰撞自動開機錄影，需搭配降壓線，可能造成電瓶虧電",
        ),
        SettingDef(
            "GSensor", "碰撞感應靈敏度",
            opts("LEVEL0" to "關", "LEVEL1" to "低", "LEVEL2" to "中", "LEVEL3" to "高", "LEVEL4" to "最高"),
            advanced = true,
        ),
        SettingDef("VoiceSwitch", "語音提示", ON_OFF, advanced = true),
        SettingDef("AutoCloseWifi", "Wi-Fi 閒置自動關閉", ON_OFF, advanced = true),
    )
}
