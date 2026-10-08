package tw.com.mufudashcam.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.Clip
import tw.com.mufudashcam.data.Clips
import tw.com.mufudashcam.data.DashcamFile
import tw.com.mufudashcam.data.Folder
import tw.com.mufudashcam.data.net.ConnState
import tw.com.mufudashcam.download.TaskStatus
import java.io.IOException

data class BrowserUi(
    val folder: Folder = Folder.Normal,
    val clips: List<Clip> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
    /** 已下載到手機的檔案：大寫檔名 → 大小。 */
    val downloaded: Map<String, Long> = emptyMap(),
    val working: Boolean = false,
) {
    fun isDownloaded(file: DashcamFile): Boolean {
        val local = downloaded[file.name.uppercase()] ?: return false
        return file.size <= 0 || local == file.size
    }
}

class BrowserViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(BrowserUi())
    val ui: StateFlow<BrowserUi> = _ui.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            c.connection.state.map { it is ConnState.Connected }.distinctUntilChanged().collect { connected ->
                if (connected) {
                    load()
                } else {
                    loadJob?.cancel()
                    _ui.update { it.copy(clips = emptyList(), selection = emptySet(), loading = false, error = null) }
                }
            }
        }
        viewModelScope.launch {
            c.downloads.tasks
                .map { tasks -> tasks.count { it.status == TaskStatus.Done } }
                .distinctUntilChanged()
                .collect { refreshDownloaded() }
        }
        // 在播放器刪掉記錄器上的檔案、或格式化記憶卡後，列表要跟著更新（快取已由對方更新）
        viewModelScope.launch {
            c.dashcamChanged.collect { if (c.connection.isConnected) load() }
        }
    }

    fun selectFolder(folder: Folder) {
        if (folder == _ui.value.folder) return
        _ui.update { it.copy(folder = folder, clips = emptyList(), selection = emptySet(), error = null) }
        if (c.connection.isConnected) load()
    }

    /** 讀取目前的資料夾；[refresh] = 不用快取，重新向記錄器讀取。 */
    fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        val folder = _ui.value.folder
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                c.files.list(folder, refresh).collect { files ->
                    _ui.update { it.copy(clips = Clips.group(files)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = "讀取列表失敗\n${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                if (loadJob === coroutineContext.job) _ui.update { it.copy(loading = false) }
            }
        }
    }

    fun toggle(key: String) = _ui.update {
        it.copy(selection = if (key in it.selection) it.selection - key else it.selection + key)
    }

    fun selectAll(keys: Collection<String>) = _ui.update { it.copy(selection = it.selection + keys) }

    fun clearSelection() = _ui.update { it.copy(selection = emptySet()) }

    fun selectedClips(): List<Clip> = _ui.value.let { u -> u.clips.filter { it.key in u.selection } }

    fun download(clips: List<Clip>, includeRear: Boolean) {
        val files = clips.flatMap { clip ->
            listOfNotNull(clip.front, if (includeRear) clip.rear else null).ifEmpty { listOfNotNull(clip.rear) }
        }
        val added = c.downloads.enqueue(files)
        clearSelection()
        _messages.trySend(if (added > 0) "已加入 $added 個檔案到下載佇列" else "這些檔案已在下載佇列中")
    }

    fun delete(clips: List<Clip>, includeRear: Boolean) {
        viewModelScope.launch {
            _ui.update { it.copy(working = true) }
            var failed = 0
            val deleted = ArrayList<String>()
            for (clip in clips) {
                var clipOk = true
                for (f in listOfNotNull(clip.front, if (includeRear) clip.rear else null)) {
                    // 推算出來的後鏡頭檔不一定存在，刪除失敗不算錯
                    val ok = try {
                        c.api.delete(f.sdPath).ok
                    } catch (e: IOException) {
                        false
                    }
                    if (ok) deleted += f.sdPath
                    if (!ok && !f.guessed) clipOk = false
                }
                if (!clipOk) failed++
            }
            _ui.update { it.copy(working = false, selection = emptySet()) }
            val done = clips.size - failed
            _messages.trySend(if (failed == 0) "已刪除 $done 段" else "已刪除 $done 段，$failed 段失敗")
            c.files.forget(deleted)
            load()
        }
    }

    /** 取得播放來源：已下載就播手機上的檔案，否則直接從記錄器串流。 */
    fun resolvePlayback(file: DashcamFile, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { runCatching { c.sink.find(file.name, file.isImage) }.getOrNull() }
            if (local != null && !local.pending) {
                onReady(local.uri.toString())
                return@launch
            }
            try {
                val url = c.api.resolveUrl(file.sdPath)
                if (url == null) _messages.trySend("記錄器上找不到 ${file.name}") else onReady(url)
            } catch (e: IOException) {
                _messages.trySend("連線失敗：${e.message}")
            }
        }
    }

    fun thumbnailUrl(file: DashcamFile): String = c.api.httpUrl(file.sdPath)

    private fun refreshDownloaded() {
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) { runCatching { c.sink.downloaded() }.getOrDefault(emptyMap()) }
            _ui.update { it.copy(downloaded = map) }
        }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { BrowserViewModel(c) } }
    }
}
