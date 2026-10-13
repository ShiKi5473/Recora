package tw.com.mufudashcam.data.api

import android.net.Network
import kotlinx.coroutines.flow.Flow
import tw.com.mufudashcam.data.Brand
import tw.com.mufudashcam.data.Camera
import tw.com.mufudashcam.data.DashcamFile
import tw.com.mufudashcam.data.Folder
import tw.com.mufudashcam.data.SettingDef
import java.time.ZonedDateTime
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
