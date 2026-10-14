package tw.com.dashcam.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tw.com.dashcam.BuildConfig

/** 外觀主題。 */
enum class ThemeMode(val label: String) { System("跟隨系統"), Light("淺色"), Dark("深色") }

/** 剪輯匯出的影像編碼。 */
enum class ExportCodec(val label: String) { H264("H.264"), H265("H.265") }

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
    val exportCodec: ExportCodec = ExportCodec.H264,
    val exportResolution: ExportResolution = ExportResolution.Original,
    val exportQuality: ExportQuality = ExportQuality.Standard,
    /** 剪輯時「去除聲音」預設打開。 */
    val exportMute: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    /** Android 12 以上依桌布配色。 */
    val dynamicColor: Boolean = true,
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

class SettingsStore(context: Context, scope: CoroutineScope) {
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
        val hostOverride = stringPreferencesKey("host_override")
        val startFolder = stringPreferencesKey("start_folder")
        val libraryGrid = booleanPreferencesKey("library_grid")
        val libraryFolders = stringSetPreferencesKey("library_folders")
        val autoPlayNext = booleanPreferencesKey("auto_play_next")
        val screenshotVisibleArea = booleanPreferencesKey("screenshot_visible_area")
        val exportCodec = stringPreferencesKey("export_codec")
        val exportResolution = stringPreferencesKey("export_resolution")
        val exportQuality = stringPreferencesKey("export_quality")
        val exportMute = booleanPreferencesKey("export_mute")
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
    }

    val flow: Flow<AppSettings> = store.data.map(::read)

    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            wifiPassword = p[Keys.password] ?: d.wifiPassword,
            ssidPrefix = p[Keys.ssidPrefix] ?: d.ssidPrefix,
            dashcamBrand = enumOr(p[Keys.dashcamBrand], d.dashcamBrand).takeIf { it in Brand.dashcams } ?: d.dashcamBrand,
            astroWifiPassword = p[Keys.astroPassword] ?: d.astroWifiPassword,
            astroSsidPrefix = p[Keys.astroSsidPrefix] ?: d.astroSsidPrefix,
            includeRear = p[Keys.includeRear] ?: d.includeRear,
            autoBackupEvents = p[Keys.autoBackupEvents] ?: d.autoBackupEvents,
            downloadSubfolder = enumOr(p[Keys.downloadSubfolder], d.downloadSubfolder),
            hostOverride = p[Keys.hostOverride] ?: d.hostOverride,
            startFolder = enumOr(p[Keys.startFolder], d.startFolder),
            libraryGrid = p[Keys.libraryGrid] ?: d.libraryGrid,
            libraryFolders = p[Keys.libraryFolders]?.let(LocalLibrary::extraFolders) ?: d.libraryFolders,
            autoPlayNext = p[Keys.autoPlayNext] ?: d.autoPlayNext,
            screenshotVisibleArea = p[Keys.screenshotVisibleArea] ?: d.screenshotVisibleArea,
            exportCodec = enumOr(p[Keys.exportCodec], d.exportCodec),
            exportResolution = enumOr(p[Keys.exportResolution], d.exportResolution),
            exportQuality = enumOr(p[Keys.exportQuality], d.exportQuality),
            exportMute = p[Keys.exportMute] ?: d.exportMute,
            themeMode = enumOr(p[Keys.themeMode], d.themeMode),
            dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
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
        p[Keys.hostOverride] = s.hostOverride
        p[Keys.startFolder] = s.startFolder.name
        p[Keys.libraryGrid] = s.libraryGrid
        p[Keys.libraryFolders] = s.libraryFolders
        p[Keys.autoPlayNext] = s.autoPlayNext
        p[Keys.screenshotVisibleArea] = s.screenshotVisibleArea
        p[Keys.exportCodec] = s.exportCodec.name
        p[Keys.exportResolution] = s.exportResolution.name
        p[Keys.exportQuality] = s.exportQuality.name
        p[Keys.exportMute] = s.exportMute
        p[Keys.themeMode] = s.themeMode.name
        p[Keys.dynamicColor] = s.dynamicColor
    }
}
