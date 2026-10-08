package tw.com.mufudashcam.ui.device

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
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.AppSettings
import kotlinx.coroutines.flow.lastOrNull
import tw.com.mufudashcam.data.DiagLog
import tw.com.mufudashcam.data.Folder
import tw.com.mufudashcam.data.api.DashcamApi
import tw.com.mufudashcam.data.SettingDef
import tw.com.mufudashcam.data.net.ConnState
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class DeviceUi(
    /** 記錄器回報的錄影狀態（Recording / Standby…），null = 未讀取或韌體不支援。 */
    val recordStatus: String? = null,
    /** 記憶卡狀態（READY / FORMAT_ING…）。 */
    val cardStatus: String? = null,
    val formatting: Boolean = false,
    val storage: DashcamApi.StorageInfo? = null,
    /** 各資料夾佔用：檔案數與位元組（前 + 後鏡頭）；null = 尚未計算。 */
    val usage: Map<Folder, Pair<Int, Long>>? = null,
    val usageLoading: Boolean = false,
    val properties: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val working: Boolean = false,
)

class DeviceViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(DeviceUi())
    val ui: StateFlow<DeviceUi> = _ui.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            c.connection.state.map { it is ConnState.Connected }.distinctUntilChanged().collect { connected ->
                if (connected) loadInfo() else _ui.update { DeviceUi() }
            }
        }
    }

    /** 讀取記錄器設定。實測 devinfo.fwver 在此韌體不存在，所以兩組都讀、有什麼就顯示什麼。 */
    fun loadInfo() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val devInfo = try {
                    c.api.getProperties("devinfo.*")
                } catch (e: IOException) {
                    emptyMap()
                }
                val menu = c.api.getProperties("Camera.Menu.*")
                _ui.update { it.copy(properties = (devInfo + menu).toSortedMap(), loading = false) }
            } catch (e: IOException) {
                _ui.update { it.copy(loading = false, error = "讀取失敗：${e.message}") }
            }
            refreshRecordStatus()
            refreshCardStatus()
            if (_ui.value.usage == null) refreshUsage()
        }
    }

    /** 記憶卡總容量 / 剩餘，以及一般、緊急、停車、照片各佔多少（加總前後鏡頭檔案大小）。 */
    fun refreshUsage() {
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
            for (folder in Folder.entries) {
                try {
                    val files = c.api.listFolder(folder).lastOrNull().orEmpty()
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
            val status = try {
                c.api.cardStatus() ?: "記錄器沒有回報"
            } catch (e: IOException) {
                "讀取失敗：${e.message}"
            }
            _ui.update { it.copy(cardStatus = status) }
        }
    }

    /**
     * 格式化記憶卡：送出指令後輪詢 Camera.Menu.SD0，直到記錄器回報 READY。
     * 由記錄器自己格式化，所以檔案系統與資料夾結構就是記錄器要的格式。
     */
    fun formatCard() {
        if (c.downloads.active.value) {
            _messages.trySend("下載進行中，請等下載結束再格式化")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(formatting = true, cardStatus = "格式化中…") }
            val message = try {
                val result = c.api.formatCard()
                DiagLog.log("格式化記憶卡：${DiagLog.compact(result.body)}")
                kotlinx.coroutines.delay(2000)
                var last: String? = null
                var sawBusy = false
                val deadline = System.currentTimeMillis() + FORMAT_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    last = try {
                        c.api.cardStatus()
                    } catch (e: IOException) {
                        null // 格式化期間記錄器可能暫時不回應
                    }
                    _ui.update { it.copy(cardStatus = last ?: "等待記錄器回應…") }
                    if (last != null && !last.equals("READY", ignoreCase = true)) sawBusy = true
                    if (last.equals("READY", ignoreCase = true) && (sawBusy || c.api.isCardEmpty())) break
                    kotlinx.coroutines.delay(1000)
                }
                when {
                    last.equals("READY", ignoreCase = true) -> "格式化完成，記憶卡已清空。請到「檔案」頁重新整理"
                    else -> "已送出格式化，但記錄器目前狀態為 ${last ?: "無回應"}，請稍後確認"
                }
            } catch (e: IOException) {
                "格式化失敗：${e.message}"
            }
            _ui.update { it.copy(formatting = false) }
            _messages.trySend(message)
            refreshCardStatus()
        }
    }

    fun refreshRecordStatus() {
        viewModelScope.launch {
            val status = try {
                c.api.recordingStatus() ?: "記錄器沒有回報"
            } catch (e: IOException) {
                "讀取失敗：${e.message}"
            }
            _ui.update { it.copy(recordStatus = status) }
        }
    }

    /** 修改記錄器設定，讀回確認是否真的套用。 */
    fun changeSetting(def: SettingDef, value: String) = command {
        if (def.slowMs > 0) _messages.trySend("${def.title}：記錄器套用中，約需 1 分鐘，期間不會回應…")
        val actual = c.api.changeSetting(def.key, value, if (def.slowMs > 0) def.slowMs else 20_000)
        if (actual != null) {
            _ui.update { it.copy(properties = (it.properties + ("Camera.Menu.${def.key}" to actual)).toSortedMap()) }
        }
        when {
            actual.equals(value, ignoreCase = true) -> "${def.title}：已改為 ${def.labelOf(value)}"
            actual == null -> "${def.title}：已送出，但讀不到目前的值"
            else -> "${def.title}：記錄器沒有套用（目前仍是 ${def.labelOf(actual)}）"
        }
    }

    fun syncTime() = command {
        val now = LocalDateTime.now().withNano(0)
        val result = c.api.syncTime(now)
        val shown = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        if (result.ok) "已把記錄器時間設為 $shown" else "記錄器回應：${result.body.take(120)}"
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { c.settings.update(transform) }
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
        private const val FORMAT_TIMEOUT_MS = 90_000L

        fun factory(c: AppContainer) = viewModelFactory { initializer { DeviceViewModel(c) } }
    }
}
