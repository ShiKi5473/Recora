package tw.com.mufudashcam.ui.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.exoplayer.util.EventLogger
import tw.com.mufudashcam.BuildConfig
import tw.com.mufudashcam.data.DiagLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 播放記錄器錄下的 TS 檔用的播放器設定（手機上的檔案與直接串流都用這個）。
 *
 * **為什麼需要特別設定**：記錄器的 TS 每秒只寫一個 PCR（實測 1440P30 約 11.8 Mbps，
 * 等於每 ~1.5 MB 才有一個），而 ExoPlayer 的 TsExtractor 預設只在檔頭與檔尾各掃
 * [TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES]（112,800 bytes = 600 個封包）找 PCR。
 * 檔尾那一段幾乎掃不到 PCR → 算不出長度 → 整段變成「不可 seek」（進度條沒有總長度），
 * 這時一拖進度條，ExoPlayer 只能把 TsExtractor 從檔案開頭重跑，H265Reader 送出的樣本位移
 * 會和 SampleQueue 裡已經收下的重疊，在 SampleQueue.commitSample 擲出 IllegalArgumentException，
 * 被 Loader 包成 IOException 後由 ExoPlayerImplInternal 統一回報成 ERROR_CODE_IO_UNSPECIFIED(2000)。
 * 也就是說那個 IO 錯誤其實跟連線無關，是「這個檔案不能 seek」造成的。
 *
 * 所以把搜尋範圍放大到足以涵蓋一個 PCR 間隔，TsExtractor 才算得出長度並建立
 * TsBinarySearchSeeker（用 PCR 二分搜尋真正的 seek），進度條就正常、也不會再踩到上面那個例外。
 * 串流時用較小的範圍：每次 seek 的二分搜尋都要從記錄器重新讀資料，範圍越大越慢。
 *
 * 另外記錄器的 GoAhead 伺服器很弱（單核心、一次只服務一個連線），所以串流時逾時放寬、
 * 載入失敗多重試幾次（指數退避），並把預讀量壓小。
 *
 * HTTP 走 App 共用的 [client]（含 RouteGuard），斷線後不會把請求送到行動網路上。
 */
@UnstableApi
fun dashcamPlayer(context: Context, client: OkHttpClient, streaming: Boolean): ExoPlayer {
    val http = OkHttpDataSource.Factory(
        client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build(),
    )
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorTimestampSearchBytes(if (streaming) TS_SEARCH_BYTES_STREAM else TS_SEARCH_BYTES_LOCAL)
    val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors)
        // 被踢掉就重新連線續讀（間隔 0、1、2…秒），而不是馬上把錯誤丟給使用者
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(MAX_LOAD_RETRIES))
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_AFTER_REBUFFER_MS)
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(sources)
        .setLoadControl(loadControl)
        .build()
        .apply {
            // TS 內有 H.265 主串流與 H.264 子串流，強制選最高碼率 = 主串流
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setForceHighestSupportedBitrate(true)
                .build()
            // debug build 把每次載入失敗、重試都印到 logcat（tag EventLogger），接電腦時查原因用
            if (BuildConfig.DEBUG) addAnalyticsListener(EventLogger())
        }
}

/**
 * 把播放錯誤翻成看得懂的原因，同時把錯誤碼和底層例外寫進診斷紀錄（「裝置」頁看得到）。
 * [streaming] = 正在從記錄器串流（而不是播手機上的檔案）。
 */
fun playbackErrorMessage(e: PlaybackException, streaming: Boolean): String {
    val cause = e.cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "無"
    DiagLog.log("播放失敗 ${e.errorCodeName}(${e.errorCode})，來源=${if (streaming) "記錄器" else "手機"}，底層：$cause")
    val reason = when (e.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "檔案格式無法解析"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "手機不支援此影片編碼"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "連不到記錄器"
        // 2000 是 ExoPlayer 對「載入時丟出 IOException」的統稱：TS 檔無法 seek 時拖進度條會踩到
        // （見 [dashcamPlayer]），串流時也可能是記錄器把連線切掉
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            if (e.cause is Loader.UnexpectedLoaderException) {
                "這段影片的索引有問題，無法跳到這個位置"
            } else if (streaming) {
                "記錄器中斷了連線，請稍後重試（下載或即時畫面進行中時特別容易發生）"
            } else {
                "讀取手機上的檔案失敗"
            }
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "記錄器拒絕了這個檔案的請求"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "找不到檔案"
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "記錄器不支援從這個位置讀取"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "沒有讀取權限"
        else -> e.errorCodeName
    }
    return "無法播放：$reason"
}

private const val CONNECT_TIMEOUT_MS = 8_000L
private const val READ_TIMEOUT_MS = 20_000L
private const val MAX_LOAD_RETRIES = 6
private const val MIN_BUFFER_MS = 15_000
private const val MAX_BUFFER_MS = 30_000
private const val BUFFER_FOR_PLAYBACK_MS = 2_500
private const val BUFFER_AFTER_REBUFFER_MS = 5_000
private const val TARGET_BUFFER_BYTES = 8 * 1024 * 1024

/** PCR 間隔實測約 1.5 MB；手機上的檔案讀起來快，留大一點的餘裕（更高碼率也掃得到）。 */
private const val TS_SEARCH_BYTES_LOCAL = 8 * 1024 * 1024

/** 串流時每次 seek 的二分搜尋都要重新連線讀資料，只留剛好夠用的餘裕。 */
private const val TS_SEARCH_BYTES_STREAM = 2 * 1024 * 1024
