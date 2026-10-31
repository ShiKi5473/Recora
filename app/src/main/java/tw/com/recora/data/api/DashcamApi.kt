package tw.com.recora.data.api

import android.net.Network
import kotlinx.coroutines.flow.Flow
import tw.com.recora.data.Brand
import tw.com.recora.data.Camera
import tw.com.recora.data.DashcamFile
import tw.com.recora.data.Folder
import tw.com.recora.data.SettingDef
import okhttp3.OkHttpClient
import tw.com.recora.data.net.RouteGuard
import java.io.IOException
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 記錄器的控制介面，App 其他地方只透過這裡跟記錄器溝通。
 * 實作：[MufuApi]（MUFU V60P，CGI，MUFU_V60P_API.md）、[AstroApi]（Astro LEO3，Novatek，ASTRO_LEO3_API.md）。
 * 用哪一個由 AppContainer 依連線中（或連線卡片選的）記錄器決定，所以不要把實作存在長壽命的變數裡。
 */
interface DashcamApi {
    val brand: Brand

    /** 可能含埠號（debug 覆寫時，例如 127.0.0.1:8080）。 */
    val host: String

    /** 讀檔案列表要先停錄（Astro）：沒在看檔案時不要自動讀。 */
    val listingStopsRecording: Boolean get() = false

    /** [onDisconnecting] 最多等多久，之後照樣解除綁定。 */
    val disconnectTimeoutMs: Long get() = 3_000L

    /** [onDisconnecting] 失敗時要讓使用者知道的事；null = 失敗了也沒關係。 */
    val disconnectFailureWarning: String? get() = null

    data class SetResult(val ok: Boolean, val body: String)

    data class ProbeResult(val ok: Boolean, val detail: String)

    data class StorageInfo(
        /** 記錄器回報的總容量 / 剩餘容量（例如 "117.0G"），讀不到為 null。 */
        val total: String?,
        val remain: String?,
        val totalBytes: Long?,
        val remainBytes: Long?,
    )

    /** 即時畫面的一種畫面（MUFU 前／後鏡頭；Astro 3028 的 0–3）。 */
    data class LiveView(val id: Int, val label: String, val camera: Camera? = null)

    data class LiveSource(val url: String, val forceTcp: Boolean = true)

    /**
     * 「裝置」頁的記錄器設定：[defs] 是可以改的項目（依記錄器回報的值與選項），[values] 是目前的值（key 同 [SettingDef.key]），
     * [readOnly] 是其他唯讀顯示的項目（名稱 → 值）。
     */
    data class SettingsSnapshot(
        val defs: List<SettingDef>,
        val values: Map<String, String>,
        val readOnly: Map<String, String>,
    )

    /** 透過指定的 [network]（null = 目前預設網路，debug 測試模式）確認對方是不是這種記錄器。 */
    suspend fun probe(network: Network?): ProbeResult

    /** 綁定記錄器的網路之後（對時之後）呼叫：保持連線、確認錄影狀態等。 */
    suspend fun onConnected() {}

    /** 使用者按「中斷連線」、解除綁定之前：通知記錄器、讓它回到平常的狀態。呼叫端會限制時間。 */
    suspend fun onDisconnecting() {}

    /** 連線已經結束（網路已解除綁定，不能再送請求）：停掉背景工作、清掉狀態。 */
    fun onDisconnected() {}

    /**
     * App 退到背景（不是設定變更）時呼叫：畫面拿的 [holdFiles] 都已經歸還，之後 App 隨時可能被關掉。
     * Astro 不等閒置計時、馬上恢復錄影。佇列還有這台記錄器的檔案要下載時不會呼叫（下載馬上又要停錄）。
     */
    suspend fun onAppBackground() {}

    suspend fun syncTime(now: ZonedDateTime = ZonedDateTime.now()): SetResult

    /** 讀取資料夾，每讀到一頁就送出目前累積的完整清單。 */
    fun listFolder(folder: Folder): Flow<List<DashcamFile>>

    /** 一次讀到所有資料夾（Astro 的 3015 一次回傳全部）；不支援時回 null，改用 [listFolder] 逐一讀。 */
    suspend fun listAll(): Map<Folder, List<DashcamFile>>? = null

    /** 下載／串流網址（不探測）。 */
    fun httpUrl(sdPath: String): String

    /** 實際可下載的網址；記錄器上沒有這個檔案回 null，網路錯誤丟出 IOException。 */
    suspend fun resolveUrl(sdPath: String): String?

    suspend fun delete(sdPath: String): SetResult

    /**
     * 要用記錄器上的檔案（「檔案」頁開著、串流播放）：Astro 會停錄並切到回放模式，[Lease.close] 後一段時間恢復錄影；
     * MUFU 連線時本來就不錄影，不需要。
     */
    fun holdFiles(reason: String): Lease = Lease.NONE

    /** 在「用檔案」的狀態下執行 [block]（等模式切換完成；見 [holdFiles]）。 */
    suspend fun <T> withFiles(block: suspend () -> T): T = block()

    val liveViews: List<LiveView>

    /** 準備即時串流，回傳要播的 RTSP 網址。 */
    suspend fun startLive(view: LiveView): LiveSource

    /** 播放器開始播之後（MUFU 逼一張 I-frame 加快起播）。 */
    suspend fun afterLiveStarted() {}

    /** 離開即時畫面。 */
    suspend fun stopLive() {}

    /** 錄影狀態（顯示用文字），韌體不支援時回 null。 */
    suspend fun recordingStatus(): String?

    /** 記憶卡狀態（顯示用文字）。 */
    suspend fun cardStatus(): String?

    suspend fun storageInfo(): StorageInfo

    suspend fun readSettings(): SettingsSnapshot

    /** 修改設定並讀回確認：回傳記錄器目前的值（null = 讀不到）。 */
    suspend fun changeSetting(def: SettingDef, value: String): String?

    /** 格式化記憶卡並等到完成，[onProgress] 回報目前狀態；回傳給使用者看的結果。 */
    suspend fun formatCard(onProgress: (String) -> Unit): String
}

/** [DashcamApi.holdFiles] 拿到的使用權；用完 [close]（重複呼叫沒關係）。 */
class Lease(private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }

    companion object {
        val NONE = Lease {}
    }
}

/**
 * 探測用的 client：探測本來就發生在綁定之前，走的是指定的 [network]（null = 目前預設網路，debug 測試模式），
 * 不需要 RouteGuard；逾時給寬一點（記錄器是單核心，邊錄影邊回應）。
 */
internal fun probeClient(client: OkHttpClient, network: Network?): OkHttpClient =
    client.newBuilder()
        .apply { interceptors().removeAll { it is RouteGuard } }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .apply { if (network != null) socketFactory(network.socketFactory) }
        .build()

/** "127.0.0.1:8080" → 127.0.0.1 */
internal fun hostNameOf(host: String): String = host.substringBefore(':')

/** "127.0.0.1:8080" → 8080（沒有埠號為 80） */
internal fun portOf(host: String): Int = host.substringAfter(':', "80").toIntOrNull() ?: 80

/** 控制指令（預覽、離開時的收尾）失敗不影響主要功能。 */
internal suspend fun ignoreIo(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: IOException) {
        // 忽略
    }
}
