package tw.com.recora.ui.library

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.com.recora.AppContainer
import tw.com.recora.data.Brand
import tw.com.recora.data.Camera
import tw.com.recora.data.LocalLibrary
import tw.com.recora.data.LocalMedia
import tw.com.recora.data.MediaKind
import tw.com.recora.data.SettingsStore
import tw.com.recora.download.DownloadRepository
import tw.com.recora.download.MediaStoreSink
import tw.com.recora.download.TaskStatus
import tw.com.recora.ui.common.DateGroups

data class LibraryUi(
    val items: List<LocalMedia> = emptyList(),
    val tab: MediaKind = MediaKind.Original,
    val loading: Boolean = false,
    /** 記錄器種類篩選：null = 全部。 */
    val brandFilter: Brand? = null,
    /** 鏡頭篩選：null = 全部。 */
    val cameraFilter: Camera? = null,
    /** 多選中的檔案（uri）；非空 = 選取模式。 */
    val selection: Set<String> = emptySet(),
    /** 「整理空間」用：沒下載完、也不在下載佇列裡的暫存檔；null = 還沒讀。 */
    val leftovers: List<MediaStoreSink.Entry>? = null,
) {
    /** 各類套用篩選後的清單：分頁標籤、清單、選取都會用到，每個狀態只算一次（順序同 [items]）。 */
    private val byKind: Map<MediaKind, List<LocalMedia>> by lazy {
        items.filter {
            (brandFilter == null || it.brand == brandFilter) && (cameraFilter == null || it.camera == cameraFilter)
        }.groupBy { it.kind }
    }

    /** 目前分頁、篩選後被選取的檔案。 */
    val selectedItems: List<LocalMedia> get() = of(tab).filter { it.uri in selection }

    /** 某一類、套用記錄器種類與鏡頭篩選後的清單。 */
    fun of(kind: MediaKind): List<LocalMedia> = byKind[kind].orEmpty()
}

class LibraryViewModel(
    private val settings: SettingsStore,
    private val downloads: DownloadRepository,
    private val sink: MediaStoreSink,
    /** 相簿內容變了（匯出、刪除）：收到就重讀；這裡刪了也通知其他頁。 */
    private val libraryChanged: MutableSharedFlow<Unit>,
) : ViewModel() {
    private val _ui = MutableStateFlow(LibraryUi())
    val ui: StateFlow<LibraryUi> = _ui.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** 刪除別的 App 建立的檔案時，要交給系統跳出確認視窗。 */
    private val _deleteRequests = Channel<IntentSender>(Channel.BUFFERED)
    val deleteRequests: Flow<IntentSender> = _deleteRequests.receiveAsFlow()

    private var refreshJob: Job? = null
    private var visible = false
    private var dirty = true

    /**
     * 這次 App 執行已經自動要求過讀取相片和影片的權限：只自動要求一次。
     * 否則選了「部分存取」的人每次切到相簿分頁都會跳出選照片的視窗；之後靠畫面上橫幅的「允許」按鈕。
     */
    var askedPermission = false

    init {
        viewModelScope.launch { libraryChanged.collect { onExternalChange() } }
        // 「相簿資料夾」或「存檔資料夾」改了
        viewModelScope.launch {
            settings.state.distinctUntilChangedBy { it.libraryFolders to it.albumDir }.drop(1).collect { onExternalChange() }
        }
        viewModelScope.launch {
            downloads.tasks.map { tasks -> tasks.count { it.status == TaskStatus.Done } }
                .distinctUntilChanged()
                .drop(1)
                .collect { onExternalChange() }
        }
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (value && dirty) refresh() else if (!value) dirty = true
    }

    private fun onExternalChange() {
        if (visible) refresh(debounceMs = REFRESH_DEBOUNCE_MS) else dirty = true
    }

    fun selectTab(kind: MediaKind) = _ui.update { it.copy(tab = kind, selection = emptySet()) }

    fun setBrandFilter(brand: Brand?) = _ui.update { it.copy(brandFilter = brand, selection = emptySet()) }

    fun setCameraFilter(camera: Camera?) = _ui.update { it.copy(cameraFilter = camera, selection = emptySet()) }

    fun toggle(uri: String) = _ui.update {
        it.copy(selection = if (uri in it.selection) it.selection - uri else it.selection + uri)
    }

    fun selectAll(uris: Collection<String>) = _ui.update { it.copy(selection = it.selection + uris) }

    fun clearSelection() = _ui.update { it.copy(selection = emptySet()) }

    /** 頭尾之間全選（依畫面順序）。 */
    fun selectRange(orderedUris: List<String>) = _ui.update { it.copy(selection = DateGroups.selectRange(orderedUris, it.selection)) }

    /** 重讀相簿。回到畫面、下載完成、相簿內容變了常常連續觸發：只留最後一次，舊的結果不會蓋掉新的。 */
    fun refresh(debounceMs: Long = 0L) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            _ui.update { it.copy(loading = true) }
            try {
                val items = withContext(Dispatchers.IO) {
                    runCatching { LocalLibrary.sorted(sink.listLibrary()) }.getOrDefault(emptyList())
                }
                dirty = false
                // 已經不在相簿裡的檔案（例如被刪掉）從選取中移除
                val present = items.mapTo(HashSet()) { it.uri }
                _ui.update { u -> u.copy(items = items, selection = u.selection.filterTo(HashSet()) { it in present }) }
            } finally {
                if (refreshJob === coroutineContext.job) _ui.update { it.copy(loading = false) }
            }
        }
    }

    /** 讀取沒下載完的暫存檔（下載佇列裡還在等的是續傳的依據，不算）。 */
    fun loadLeftovers() {
        viewModelScope.launch {
            val active = downloads.tasks.value.filter { it.isActive }.mapTo(HashSet()) { it.file.name.uppercase() }
            val pending = withContext(Dispatchers.IO) {
                runCatching { sink.listPending() }.getOrDefault(emptyList())
            }.filter { it.name.uppercase() !in active }
            _ui.update { it.copy(leftovers = pending) }
        }
    }

    /** 清除沒下載完的暫存檔（之後再下載同一個檔案要從頭開始）。 */
    fun clearLeftovers(entries: List<MediaStoreSink.Entry>) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { entries.count { runCatching { sink.delete(it.uri) }.isSuccess } }
            _messages.trySend("已清除 $deleted 個未完成的下載")
            loadLeftovers()
        }
    }

    /**
     * 刪除一或多個檔案。本 App 建立的直接刪；不是本 App 建立的（例如重新安裝前下載的）
     * 集中成一次系統確認視窗，不必一個一個按。
     */
    fun delete(items: List<LocalMedia>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { sink.deleteOrRequest(items.map { Uri.parse(it.uri) }) }
            clearSelection()
            if (result.deleted > 0) {
                _messages.trySend(if (items.size == 1) "已刪除 ${items[0].name}" else "已刪除 ${result.deleted} 個檔案")
                // 相簿自己會收到並重新整理；「檔案」頁的已下載打勾也要跟著更新
                libraryChanged.tryEmit(Unit)
            }
            result.confirm?.let { _deleteRequests.trySend(it) }
        }
    }

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 250L

        fun factory(c: AppContainer) = viewModelFactory {
            initializer { LibraryViewModel(settings = c.settings, downloads = c.downloads, sink = c.sink, libraryChanged = c.libraryChanged) }
        }
    }
}
