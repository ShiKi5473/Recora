package tw.com.recora.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.com.recora.BuildConfig

/** 外觀主題。 */
enum class ThemeMode(val label: String) { System("跟隨系統"), Light("淺色"), Dark("深色") }

/** 剪輯匯出的影像編碼；[Original] = 不重新編碼，直接複製原本的畫面（Mufu 是 H.265、Astro 是 H.264；解析度、畫質都跟原檔一樣）。 */
enum class ExportCodec(val label: String) { H264("H.264"), H265("H.265"), Original("不重新編碼") }

/** 剪輯匯出的解析度（只縮小、不放大）；[height] = null 維持原始解析度。 */
enum class ExportResolution(val label: String, val height: Int?) {
    Original("原始解析度", null),
    P1080("1080p", 1080),
    P720("720p", 720),
}

/** 下載的檔案放在 App 資料夾底下的哪個子資料夾。 */
enum class SubfolderMode(val label: String) {
    None("不分"),
    ByFolder("依類型（一般／緊急／停車）"),
    ByDate("依錄影日期"),
}

/** 剪輯匯出的畫質（位元率）。 */
enum class ExportQuality(val label: String) { High("高"), Standard("標準"), Small("省空間") }

data class AppSettings(
    /** MUFU 的 Wi-Fi 密碼與 SSID 開頭（沿用改版前的設定名稱）。 */
    val wifiPassword: String = DashcamConfig.DEFAULT_PASSWORD,
    val ssidPrefix: String = DashcamConfig.DEFAULT_SSID_PREFIX,
    /** 連線卡片上選的記錄器（記住上次的選擇）。 */
    val dashcamBrand: Brand = Brand.Mufu,
    val astroWifiPassword: String = DashcamConfig.DEFAULT_PASSWORD,
    val astroSsidPrefix: String = DashcamConfig.ASTRO_SSID_PREFIX,
    /** 下載時預設一併下載後鏡頭。 */
    val includeRear: Boolean = true,
    /** 連上記錄器時，把還沒下載的緊急錄影自動加入下載佇列。 */
    val autoBackupEvents: Boolean = false,
    val downloadSubfolder: SubfolderMode = SubfolderMode.None,
    /** 下載、匯出、截圖存到 Movies／Pictures 底下的哪個資料夾（只有一層資料夾名稱）。 */
    val albumDir: String = DashcamConfig.LOCAL_DIR,
    /** 僅 debug build 有效：記錄器位址覆寫（例如 127.0.0.1:8080 的假記錄器，搭配 adb reverse），設定後略過 Wi-Fi 綁定。 */
    val hostOverride: String = "",
    /** 開啟 App 時「檔案」頁顯示的資料夾。 */
    val startFolder: Folder = Folder.Normal,
    /** 相簿用格狀顯示（否則是列表）。 */
    val libraryGrid: Boolean = false,
    /** 相簿除了 App 自己的資料夾，還要讀的資料夾（RELATIVE_PATH，結尾有 /，含子資料夾）。 */
    val libraryFolders: Set<String> = LocalLibrary.DEFAULT_FOLDERS,
    /** 播完自動播錄影時間的下一段。 */
    val autoPlayNext: Boolean = false,
    /** 放大時截圖只存畫面上看得到的範圍（關閉時存整張）。 */
    val screenshotVisibleArea: Boolean = false,
    /** 播放 Astro 的原始檔時，標題下顯示影片裡的 GPS（時速、座標）。 */
    val showGps: Boolean = true,
    /** 播放有 GPS 的影片時，直向畫面下半部顯示路線地圖。 */
    val showRouteMap: Boolean = true,
    val exportCodec: ExportCodec = ExportCodec.H264,
    val exportResolution: ExportResolution = ExportResolution.Original,
    val exportQuality: ExportQuality = ExportQuality.Standard,
    /** 剪輯時「去除聲音」預設打開。 */
    val exportMute: Boolean = false,
    /**
     * 這支手機的編碼器實際輸出／要求的影像位元率，從匯出結果學來，預估檔案大小用。
     * 依匯出設定分開記（key 見 [tw.com.recora.ui.editor.ExportOptions.factorKey]）：
     * 實測同一個 H.264 編碼器，1440p 標準多出 24%，1080p 省空間卻多出 90%。
     */
    val exportBitrateFactors: Map<String, Float> = emptyMap(),
    val themeMode: ThemeMode = ThemeMode.System,
    /** Android 12 以上依桌布配色。 */
    val dynamicColor: Boolean = false,
    /** 自動檢查 GitHub Releases 並下載安裝 App 更新。 */
    val appAutoUpdate: Boolean = false,
    /** 自動更新時僅在 Wi-Fi（非計量網路）下檢查與下載。 */
    val appUpdateWifiOnly: Boolean = true,
    /** 上次成功檢查 GitHub Release 的時間戳記（ms）；0 代表尚未檢查。 */
    val lastAppUpdateCheckMs: Long = 0L,
    /** 使用者選擇略過自動提示的版本（例如 "1.0.1"）；空字串代表無。 */
    val skippedAppVersion: String = "",
) {
    val overrideActive: Boolean get() = BuildConfig.DEBUG && hostOverride.isNotBlank()

    /** debug 的位址覆寫對兩種記錄器都有效（假記錄器依 --brand 模擬其中一種）。 */
    fun hostFor(brand: Brand): String = if (overrideActive) hostOverride.trim() else DashcamConfig.defaultHost(brand)

    fun ssidPrefixFor(brand: Brand): String = if (brand == Brand.Astro) astroSsidPrefix else ssidPrefix

    fun passwordFor(brand: Brand): String = if (brand == Brand.Astro) astroWifiPassword else wifiPassword
}

/** enum 存成名稱；讀不到或已不存在的值（例如改版拿掉的選項）退回 [default]。 */
internal inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context, private val scope: CoroutineScope) {
    private val store = context.dataStore

    private object Keys {
        val password = stringPreferencesKey("wifi_password")
        val ssidPrefix = stringPreferencesKey("ssid_prefix")
        val dashcamBrand = stringPreferencesKey("dashcam_brand")
        val astroPassword = stringPreferencesKey("astro_wifi_password")
        val astroSsidPrefix = stringPreferencesKey("astro_ssid_prefix")
        val includeRear = booleanPreferencesKey("include_rear")
        val autoBackupEvents = booleanPreferencesKey("auto_backup_events")
        val downloadSubfolder = stringPreferencesKey("download_subfolder")
        val albumDir = stringPreferencesKey("album_dir")
        val hostOverride = stringPreferencesKey("host_override")
        val startFolder = stringPreferencesKey("start_folder")
        val libraryGrid = booleanPreferencesKey("library_grid")
        val libraryFolders = stringSetPreferencesKey("library_folders")
        val autoPlayNext = booleanPreferencesKey("auto_play_next")
        val screenshotVisibleArea = booleanPreferencesKey("screenshot_visible_area")
        val showGps = booleanPreferencesKey("show_gps")
        val showRouteMap = booleanPreferencesKey("show_route_map")
        val exportCodec = stringPreferencesKey("export_codec")
        val exportResolution = stringPreferencesKey("export_resolution")
        val exportQuality = stringPreferencesKey("export_quality")
        val exportMute = booleanPreferencesKey("export_mute")
        val exportBitrateFactors = stringSetPreferencesKey("export_bitrate_factors_v2")

        /** 改版前只依編碼記的倍數（混了不同解析度、畫質，不準），寫入時清掉。 */
        val legacyBitrateFactors = listOf("h264", "h265", "original").map { floatPreferencesKey("export_bitrate_factor_$it") }

        /** 還沒分來源容器的倍數：當時只匯出過 Mufu 的 TS，讀的時候轉成 TS 的 key，寫入時清掉。 */
        val legacyBitrateFactorsV1 = stringSetPreferencesKey("export_bitrate_factors")

        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color_v2")
        val appAutoUpdate = booleanPreferencesKey("app_auto_update")
        val appUpdateWifiOnly = booleanPreferencesKey("app_update_wifi_only")
        val lastAppUpdateCheckMs = longPreferencesKey("last_app_update_check_ms")
        val skippedAppVersion = stringPreferencesKey("skipped_app_version")
    }

    @Volatile
    var isLoaded: Boolean = false
        private set

    val flow: Flow<AppSettings> = store.data.map(::read).onEach { isLoaded = true }

    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    /** 不等寫入完成的 [update]（畫面上的開關）：在 App 層級的 scope 寫，離開畫面也會寫完。 */
    fun launchUpdate(transform: (AppSettings) -> AppSettings): Job = scope.launch { update(transform) }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        val albumDir = p[Keys.albumDir]?.takeIf(LocalLibrary::isValidAlbumDir) ?: d.albumDir
        return AppSettings(
            wifiPassword = p[Keys.password] ?: d.wifiPassword,
            ssidPrefix = p[Keys.ssidPrefix] ?: d.ssidPrefix,
            dashcamBrand = enumOr(p[Keys.dashcamBrand], d.dashcamBrand).takeIf { it in Brand.dashcams } ?: d.dashcamBrand,
            astroWifiPassword = p[Keys.astroPassword] ?: d.astroWifiPassword,
            astroSsidPrefix = p[Keys.astroSsidPrefix] ?: d.astroSsidPrefix,
            includeRear = p[Keys.includeRear] ?: d.includeRear,
            autoBackupEvents = p[Keys.autoBackupEvents] ?: d.autoBackupEvents,
            downloadSubfolder = enumOr(p[Keys.downloadSubfolder], d.downloadSubfolder),
            albumDir = albumDir,
            hostOverride = p[Keys.hostOverride] ?: d.hostOverride,
            startFolder = enumOr(p[Keys.startFolder], d.startFolder),
            libraryGrid = p[Keys.libraryGrid] ?: d.libraryGrid,
            libraryFolders = p[Keys.libraryFolders]?.let { LocalLibrary.extraFolders(it, albumDir) } ?: d.libraryFolders,
            autoPlayNext = p[Keys.autoPlayNext] ?: d.autoPlayNext,
            screenshotVisibleArea = p[Keys.screenshotVisibleArea] ?: d.screenshotVisibleArea,
            showGps = p[Keys.showGps] ?: d.showGps,
            showRouteMap = p[Keys.showRouteMap] ?: d.showRouteMap,
            exportCodec = enumOr(p[Keys.exportCodec], d.exportCodec),
            exportResolution = enumOr(p[Keys.exportResolution], d.exportResolution),
            exportQuality = enumOr(p[Keys.exportQuality], d.exportQuality),
            exportMute = p[Keys.exportMute] ?: d.exportMute,
            exportBitrateFactors = p[Keys.exportBitrateFactors]?.let(::parseFactors)
                ?: p[Keys.legacyBitrateFactorsV1]?.let { v1 -> migrateFactorsV1(parseFactors(v1)) }.orEmpty(),
            themeMode = enumOr(p[Keys.themeMode], d.themeMode),
            dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
            appAutoUpdate = p[Keys.appAutoUpdate] ?: d.appAutoUpdate,
            appUpdateWifiOnly = p[Keys.appUpdateWifiOnly] ?: d.appUpdateWifiOnly,
            lastAppUpdateCheckMs = p[Keys.lastAppUpdateCheckMs] ?: d.lastAppUpdateCheckMs,
            skippedAppVersion = p[Keys.skippedAppVersion] ?: d.skippedAppVersion,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.password] = s.wifiPassword
        p[Keys.ssidPrefix] = s.ssidPrefix
        p[Keys.dashcamBrand] = s.dashcamBrand.name
        p[Keys.astroPassword] = s.astroWifiPassword
        p[Keys.astroSsidPrefix] = s.astroSsidPrefix
        p[Keys.includeRear] = s.includeRear
        p[Keys.autoBackupEvents] = s.autoBackupEvents
        p[Keys.downloadSubfolder] = s.downloadSubfolder.name
        p[Keys.albumDir] = s.albumDir
        p[Keys.hostOverride] = s.hostOverride
        p[Keys.startFolder] = s.startFolder.name
        p[Keys.libraryGrid] = s.libraryGrid
        p[Keys.libraryFolders] = s.libraryFolders
        p[Keys.autoPlayNext] = s.autoPlayNext
        p[Keys.screenshotVisibleArea] = s.screenshotVisibleArea
        p[Keys.showGps] = s.showGps
        p[Keys.showRouteMap] = s.showRouteMap
        p[Keys.exportCodec] = s.exportCodec.name
        p[Keys.exportResolution] = s.exportResolution.name
        p[Keys.exportQuality] = s.exportQuality.name
        p[Keys.exportMute] = s.exportMute
        p[Keys.exportBitrateFactors] = s.exportBitrateFactors.map { (key, factor) -> "$key=$factor" }.toSet()
        Keys.legacyBitrateFactors.forEach { p.remove(it) }
        p.remove(Keys.legacyBitrateFactorsV1)
        p[Keys.themeMode] = s.themeMode.name
        p[Keys.dynamicColor] = s.dynamicColor
        p[Keys.appAutoUpdate] = s.appAutoUpdate
        p[Keys.appUpdateWifiOnly] = s.appUpdateWifiOnly
        p[Keys.lastAppUpdateCheckMs] = s.lastAppUpdateCheckMs
        p[Keys.skippedAppVersion] = s.skippedAppVersion
    }
}

/** 存成字串集合的倍數（`key=倍數`）讀回來；格式不對的略過。 */
internal fun parseFactors(entries: Set<String>): Map<String, Float> =
    entries.mapNotNull { e ->
        val i = e.lastIndexOf('=')
        val factor = e.substring(i + 1).toFloatOrNull()
        if (i <= 0 || factor == null) null else e.substring(0, i) to factor
    }.toMap()

/** 還沒分來源容器的倍數（`H264|1440|Standard`、`Original`）：當時都是 Mufu 的 TS，補上 `|TS`。 */
internal fun migrateFactorsV1(old: Map<String, Float>): Map<String, Float> = old.mapKeys { (key, _) -> "$key|TS" }
