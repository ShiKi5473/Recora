package tw.com.recora.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.com.recora.AppContainer
import kotlinx.coroutines.flow.lastOrNull
import tw.com.recora.data.Folder
import tw.com.recora.data.api.DashcamApi
import tw.com.recora.data.SettingDef
import tw.com.recora.data.net.ConnState
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class DeviceUi(
    /** 記錄器回報的錄影狀態（MUFU：Recording / Standby…；Astro：錄影中…），null = 未讀取。 */
    val recordStatus: String? = null,
    /** 記憶卡狀態（MUFU：READY / FORMAT_ING…；Astro：正常）。 */
    val cardStatus: String? = null,
    val formatting: Boolean = false,
    val storage: DashcamApi.StorageInfo? = null,
    /** 各資料夾佔用：檔案數與位元組（前 + 後鏡頭）；null = 尚未計算。 */
    val usage: Map<Folder, Pair<Int, Long>>? = null,
    val usageLoading: Boolean = false,
    /** 可以改的設定、目前的值（key 同 [SettingDef.key]），以及其他唯讀顯示的項目。 */
    val defs: List<SettingDef> = emptyList(),
    val values: Map<String, String> = emptyMap(),
    val readOnly: Map<String, String> = emptyMap(),
    val settingsLoaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val working: Boolean = false,
)

class DeviceViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(DeviceUi())
    val ui: StateFlow<DeviceUi> = _ui.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** 「裝置」頁現在看得到（回到前景、停在這個分頁）。 */
    private var visible = false

    /** 這次連線已經讀過記錄器的設定與狀態；斷線時重設。 */
    private var loadedThisConnection = false

    init {
        viewModelScope.launch {
            c.connection.state.map { it is ConnState.Connected }.distinctUntilChanged().collect { connected ->
                if (connected) {
                    // 沒在看這一頁就先不讀，等 setVisible(true) 再補讀
                    if (visible && !loadedThisConnection) loadInfo()
                } else {
                    loadedThisConnection = false
                    _ui.update { DeviceUi() }
                }
            }
        }
    }

    /**
     * 「裝置」頁出現或離開；出現時（已連線、這次連線還沒讀過）補讀。
     * 這個 ViewModel 切換分頁後仍留著，不能一連上就在背景讀：MUFU 一次只服務一個連線，會跟「檔案」頁的列表、下載搶。
     */
    fun setVisible(value: Boolean) {
        visible = value
        if (value && c.connection.isConnected && !loadedThisConnection) loadInfo()
    }

    /** 讀取記錄器設定（可改的項目與選項依記錄器回報的為準）。 */
    fun loadInfo() {
        loadedThisConnection = true
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val snapshot = c.api.readSettings()
                _ui.update {
                    it.copy(
                        defs = snapshot.defs, values = snapshot.values, readOnly = snapshot.readOnly,
                        settingsLoaded = true, loading = false,
                    )
                }
            } catch (e: IOException) {
                _ui.update { it.copy(loading = false, error = "讀取失敗：${e.message}") }
            }
            // 記錄器一次只服務一個連線：狀態、容量依序讀，不同時送
            val record = readRecordStatus()
            _ui.update { it.copy(recordStatus = record) }
            val card = readCardStatus()
            _ui.update { it.copy(cardStatus = card) }
            if (_ui.value.usage == null) refreshUsage()
        }
    }

    /**
     * 記憶卡總容量 / 剩餘，以及一般、緊急、停車、照片各佔多少（加總前後鏡頭檔案大小）。
     * 列表用與「檔案」頁共用的快取；[refresh] = 重新向記錄器讀取。
     */
    fun refreshUsage(refresh: Boolean = false) {
        if (_ui.value.usageLoading) return
        viewModelScope.launch {
            _ui.update { it.copy(usageLoading = true) }
            val storage = try {
                c.api.storageInfo()
            } catch (e: IOException) {
                null
            }
            _ui.update { it.copy(storage = storage) }
            val usage = LinkedHashMap<Folder, Pair<Int, Long>>()
            // Astro 讀列表要先停錄：自動讀取只用「檔案」頁已經讀過的，按「重新計算容量」才向記錄器讀
            val cacheOnly = c.api.listingStopsRecording && !refresh
            for (folder in Folder.entries) {
                try {
                    val files = if (cacheOnly) {
                        c.files.cached(folder) ?: continue
                    } else {
                        c.files.list(folder, refresh).lastOrNull().orEmpty()
                    }
                    usage[folder] = files.size to files.sumOf { it.size.coerceAtLeast(0) }
                    _ui.update { it.copy(usage = LinkedHashMap(usage)) }
                } catch (e: IOException) {
                    // 單一資料夾失敗不影響其他
                }
            }
            _ui.update { it.copy(usageLoading = false, usage = usage) }
        }
    }

    fun refreshCardStatus() {
        viewModelScope.launch {
            val status = readCardStatus()
            _ui.update { it.copy(cardStatus = status) }
        }
    }

    private suspend fun readCardStatus(): String =
        try {
            c.api.cardStatus() ?: "記錄器沒有回報"
        } catch (e: IOException) {
            "讀取失敗：${e.message}"
        }

    /**
     * 格式化記憶卡：由記錄器自己格式化（檔案系統與資料夾結構就是記錄器要的格式），
     * 等待與確認完成的流程在各記錄器的實作裡，這裡只顯示進度。
     */
    fun formatCard() {
        if (c.downloads.active.value) {
            _messages.trySend("下載進行中，請等下載結束再格式化")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(formatting = true, cardStatus = "格式化中…") }
            val message = try {
                c.api.formatCard { progress -> _ui.update { it.copy(cardStatus = progress) } }
            } catch (e: IOException) {
                "格式化失敗：${e.message}"
            }
            _ui.update { it.copy(formatting = false) }
            _messages.trySend(message)
            // 不管成功與否，列表都可能已經變了
            c.files.invalidateAll()
            c.dashcamChanged.tryEmit(Unit)
            val card = readCardStatus()
            _ui.update { it.copy(cardStatus = card) }
            refreshUsage()
        }
    }

    fun refreshRecordStatus() {
        viewModelScope.launch {
            val status = readRecordStatus()
            _ui.update { it.copy(recordStatus = status) }
        }
    }

    private suspend fun readRecordStatus(): String =
        try {
            c.api.recordingStatus() ?: "記錄器沒有回報"
        } catch (e: IOException) {
            "讀取失敗：${e.message}"
        }

    /** 修改記錄器設定，讀回確認是否真的套用。 */
    fun changeSetting(def: SettingDef, value: String) = command {
        if (def.slowMs > 0) _messages.trySend("${def.title}：記錄器套用中，約需 1 分鐘，期間不會回應…")
        val actual = c.api.changeSetting(def, value)
        if (actual != null) {
            _ui.update { it.copy(values = it.values + (def.key to actual)) }
        }
        when {
            actual.equals(value, ignoreCase = true) -> "${def.title}：已改為 ${def.labelOf(value)}"
            def.deferred -> "${def.title}：已送出（${def.labelOf(value)}），記錄器會在 Wi-Fi 斷線後才變更"
            actual == null -> "${def.title}：已送出，但讀不到目前的值"
            else -> "${def.title}：記錄器沒有套用（目前仍是 ${def.labelOf(actual)}）"
        }
    }

    fun syncTime() = command {
        val now = ZonedDateTime.now(ZoneId.systemDefault()).withNano(0)
        val result = c.api.syncTime(now)
        val shown = now.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        if (result.ok) "已把記錄器時間設為 $shown" else "記錄器回應：${result.body.take(120)}"
    }

    fun applyHostOverride(host: String) {
        viewModelScope.launch {
            c.settings.update { it.copy(hostOverride = host.trim()) }
            c.connection.disconnect()
            if (host.isNotBlank()) c.connection.connect()
        }
    }

    private fun command(block: suspend () -> String) {
        viewModelScope.launch {
            _ui.update { it.copy(working = true) }
            val message = try {
                block()
            } catch (e: IOException) {
                "失敗：${e.message}"
            }
            _ui.update { it.copy(working = false) }
            _messages.trySend(message)
        }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { DeviceViewModel(c) } }
    }
}
