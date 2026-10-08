package tw.com.mufudashcam.ui.library

import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.Camera
import tw.com.mufudashcam.data.LocalLibrary
import tw.com.mufudashcam.data.LocalMedia
import tw.com.mufudashcam.data.MediaKind
import tw.com.mufudashcam.download.TaskStatus

data class LibraryUi(
    val items: List<LocalMedia> = emptyList(),
    val tab: MediaKind = MediaKind.Original,
    val loading: Boolean = false,
    /** 鏡頭篩選：null = 全部。 */
    val cameraFilter: Camera? = null,
) {
    /** 某一類、套用鏡頭篩選後的清單。 */
    fun of(kind: MediaKind): List<LocalMedia> =
        items.filter { it.kind == kind && (cameraFilter == null || it.camera == cameraFilter) }
}

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(LibraryUi())
    val ui: StateFlow<LibraryUi> = _ui.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** 刪除別的 App 建立的檔案時，要交給系統跳出確認視窗。 */
    private val _deleteRequests = Channel<IntentSender>(Channel.BUFFERED)
    val deleteRequests: Flow<IntentSender> = _deleteRequests.receiveAsFlow()

    init {
        viewModelScope.launch { c.libraryChanged.collect { refresh() } }
        viewModelScope.launch {
            c.downloads.tasks.map { tasks -> tasks.count { it.status == TaskStatus.Done } }
                .distinctUntilChanged()
                .collect { refresh() }
        }
        // 例如剪輯匯出後按「到相簿查看」，要直接顯示「已剪輯」
        viewModelScope.launch {
            c.libraryTabRequest.filterNotNull().collect { kind ->
                _ui.update { it.copy(tab = kind) }
                c.libraryTabRequest.value = null
            }
        }
    }

    fun selectTab(kind: MediaKind) = _ui.update { it.copy(tab = kind) }

    fun setCameraFilter(camera: Camera?) = _ui.update { it.copy(cameraFilter = camera) }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val items = withContext(Dispatchers.IO) {
                runCatching { LocalLibrary.sorted(c.sink.listLibrary()) }.getOrDefault(emptyList())
            }
            _ui.update { it.copy(items = items, loading = false) }
        }
    }

    fun delete(item: LocalMedia) {
        viewModelScope.launch {
            val uri = Uri.parse(item.uri)
            try {
                withContext(Dispatchers.IO) { c.sink.delete(uri) }
                _messages.trySend("已刪除 ${item.name}")
                refresh()
            } catch (e: SecurityException) {
                // 不是本 App 建立的檔案（例如重新安裝前下載的）：請系統確認
                val request = MediaStore.createDeleteRequest(c.app.contentResolver, listOf(uri))
                _deleteRequests.trySend(request.intentSender)
            }
        }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { LibraryViewModel(c) } }
    }
}
