package tw.com.mufudashcam

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import tw.com.mufudashcam.data.MediaKind
import tw.com.mufudashcam.data.PlayItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import tw.com.mufudashcam.data.SettingsStore
import tw.com.mufudashcam.data.api.DashcamApi
import tw.com.mufudashcam.data.net.DashcamConnection
import tw.com.mufudashcam.download.DownloadRepository
import tw.com.mufudashcam.download.MediaStoreSink
import java.io.IOException
import java.util.concurrent.TimeUnit

class MufuApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.connection.start()
    }

    /** 相簿縮圖要能解影片的某一格（coil-video）。 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}

/** 手寫的依賴容器，整個 App 共用一份。 */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, appScope)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val api = DashcamApi(http, settings)
    val connection = DashcamConnection(app, api, settings, appScope)
    val sink = MediaStoreSink(app)
    val downloads = DownloadRepository(app, api, settings, sink, http, appScope) { connection.isConnected }

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
        // 上次 App 停止錄影後沒能恢復（當機、被系統終止、斷線）：連上時補送 recordon
        connection.onConnected = {
            // 同官方 App：每次連上都把手機時間寫入記錄器
            try {
                api.syncTime()
            } catch (e: IOException) {
                // 對時失敗不影響其他功能
            }
            if (settings.current().recordPausedByApp && !downloads.active.value) {
                try {
                    api.recordOn()
                    settings.update { it.copy(recordPausedByApp = false) }
                } catch (e: IOException) {
                    // 下次再試
                }
            }
        }
    }
}
