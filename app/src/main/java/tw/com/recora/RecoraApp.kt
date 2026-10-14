package tw.com.recora

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import tw.com.recora.data.Brand
import tw.com.recora.data.DashcamFiles
import tw.com.recora.data.DiagLog
import tw.com.recora.data.MediaKind
import tw.com.recora.data.PlayItem
import tw.com.recora.data.SettingsStore
import tw.com.recora.data.api.AstroApi
import tw.com.recora.data.api.DashcamApi
import tw.com.recora.data.api.MufuApi
import tw.com.recora.data.net.DashcamConnection
import tw.com.recora.data.net.RouteGuard
import tw.com.recora.download.DownloadFolders
import tw.com.recora.download.DownloadRepository
import tw.com.recora.download.EventBackup
import tw.com.recora.download.MediaStoreSink
import tw.com.recora.download.QueueStore
import tw.com.recora.ui.common.MediaThumbFetcher
import tw.com.recora.ui.editor.ClipExporter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class RecoraApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 車上連著記錄器時沒辦法接電腦：紀錄寫進 Android/data/tw.com.recora/files/diag/，回來再 adb pull
        DiagLog.init(
            getExternalFilesDir("diag"),
            "App 啟動 ${BuildConfig.VERSION_NAME}（${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}）",
        )
        container = AppContainer(this)
        container.connection.start()
        // 上次匯出到一半 App 被關掉時留下的暫存檔
        container.appScope.launch(Dispatchers.IO) { ClipExporter.cleanStale(this@RecoraApp) }
    }

    /**
     * 相簿縮圖用系統的縮圖快取（[MediaThumbFetcher]），其他影片能解出某一格（coil-video）；
     * 記錄器上的照片走共用的 client（受 RouteGuard 保護）。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { container.http }
            .components {
                add(MediaThumbFetcher.Factory())
                add(MediaThumbFetcher.Key())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
}

/** 手寫的依賴容器，整個 App 共用一份。 */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, appScope)

    /** 所有連記錄器的 HTTP 請求（CGI、下載、串流播放、照片）共用；沒綁定記錄器網路時一律擋下。 */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(RouteGuard { connection.canReachDashcam })
        .build()

    private val astro = AstroApi(http, settings, appScope)
    private val apis: Map<Brand, DashcamApi> = mapOf(Brand.Mufu to MufuApi(http, settings), Brand.Astro to astro)
    private fun apiOf(brand: Brand): DashcamApi = apis[brand] ?: apis.getValue(Brand.Mufu)
    val connection = DashcamConnection(app, apis, settings, appScope)

    /**
     * 目前的記錄器介面：連著的那一種，否則連線卡片選的。每次使用時才取，
     * 不要存在長壽命的變數裡（換品牌後會拿到舊的實作）。
     */
    val api: DashcamApi get() = apiOf(connection.currentBrand)

    val files = DashcamFiles({ api }, connection, appScope).also { astro.onRecordingResumed = it::invalidateAll }
    val sink = MediaStoreSink(app, { settings.state.value.libraryFolders }, { settings.state.value.albumDir })
    val downloads = DownloadRepository(
        app, ::apiOf, sink, http, appScope,
        queueStore = QueueStore(File(app.filesDir, "download_queue.tsv")),
        subdirOf = { file -> DownloadFolders.subdir(file, settings.state.value.downloadSubfolder) },
    ) {
        // 兩個條件都要：斷線的瞬間（狀態還沒更新但已解除綁定）不會一直重跑被 RouteGuard 擋下的檔案
        if (connection.isConnected && connection.canReachDashcam) connection.boundBrand else null
    }
    val eventBackup = EventBackup(files, sink, downloads, settings)

    /** 相簿內容變了（匯出、刪除）時通知相簿分頁重新整理。 */
    val libraryChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** 記錄器上的檔案變了（在播放器刪除）時通知「檔案」分頁重新整理。 */
    val dashcamChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** 播放器的清單：開啟播放時由來源頁面設定，播放器左右滑動切換（不在清單裡就只播單一檔案）。 */
    @Volatile
    var playQueue: List<PlayItem> = emptyList()

    /** 下一次打開相簿分頁時要顯示哪一類（例如匯出後跳到「已剪輯」）。 */
    val libraryTabRequest = MutableStateFlow<MediaKind?>(null)

    init {
        connection.onConnected = { dashcam ->
            // 同官方 App：每次連上都把手機時間寫入記錄器
            try {
                val sync = dashcam.syncTime()
                DiagLog.log("對時：${if (sync.ok) "成功" else "失敗"}（${DiagLog.compact(sync.body, 80)}）")
            } catch (e: IOException) {
                // 對時失敗不影響其他功能
            }
            // Astro：保持連線、確認記錄器有在錄影
            try {
                dashcam.onConnected()
            } catch (e: IOException) {
                DiagLog.log("連線後初始化失敗：${e.message}")
            }
            // App 設定：連上時自動備份還沒下載的緊急錄影（先讀完列表再開始下載，不跟下載搶記錄器的連線）
            if (settings.current().autoBackupEvents) {
                try {
                    val added = eventBackup.run()
                    if (added > 0) DiagLog.log("自動備份緊急錄影：加入 $added 個檔案")
                } catch (e: IOException) {
                    DiagLog.log("自動備份緊急錄影失敗：${e.message}")
                }
            }
            // 斷線前（或 App 被關掉前）沒下載完的檔案接著下載
            downloads.resume()
        }
    }
}
