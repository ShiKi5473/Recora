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
import tw.com.mufudashcam.data.DashcamFiles
import tw.com.mufudashcam.data.SettingsStore
import tw.com.mufudashcam.data.api.DashcamApi
import tw.com.mufudashcam.data.net.DashcamConnection
import tw.com.mufudashcam.data.net.RouteGuard
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

    /** 相簿縮圖要能解影片的某一格（coil-video）；記錄器上的照片走共用的 client（受 RouteGuard 保護）。 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { container.http }
            .components { add(VideoFrameDecoder.Factory()) }
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

    val api = DashcamApi(http, settings)
    val connection = DashcamConnection(app, api, settings, appScope)
    val files = DashcamFiles(api, connection, appScope)
    val sink = MediaStoreSink(app)
    // 兩個條件都要：斷線的瞬間（狀態還沒更新但已解除綁定）不會一直重跑被 RouteGuard 擋下的檔案
    val downloads = DownloadRepository(app, api, sink, http, appScope) {
        connection.isConnected && connection.canReachDashcam
    }

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
        connection.onConnected = {
            // 同官方 App：每次連上都把手機時間寫入記錄器
            try {
                api.syncTime()
            } catch (e: IOException) {
                // 對時失敗不影響其他功能
            }
            // 斷線前沒下載完的檔案接著下載
            downloads.resume()
        }
    }
}
