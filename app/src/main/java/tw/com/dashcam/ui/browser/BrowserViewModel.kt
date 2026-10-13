package tw.com.dashcam.ui.browser

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
import tw.com.dashcam.AppContainer
import tw.com.dashcam.data.Clip
import tw.com.dashcam.data.Clips
import tw.com.dashcam.data.DashcamFile
import tw.com.dashcam.data.DownloadChoice
import tw.com.dashcam.data.Folder
import tw.com.dashcam.data.net.ConnState
import tw.com.dashcam.download.TaskStatus
import tw.com.dashcam.ui.common.DateGroups
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
    /** 只顯示還有檔案沒下載的片段。 */
    val onlyMissing: Boolean = false,
) {
    fun isDownloaded(file: DashcamFile): Boolean = Clips.isDownloaded(file, downloaded)

    /** 畫面上顯示的片段（套用「未下載」篩選；[includeRear] = 預設下載後鏡頭，沒下載後鏡頭也算未下載）。 */
    fun visibleClips(includeRear: Boolean): List<Clip> =
        if (onlyMissing) clips.filter { Clips.isMissing(it, includeRear, downloaded) } else clips
}

class BrowserViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(BrowserUi())
    val ui: StateFlow<BrowserUi> = _ui.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var loadJob: Job? = null
    private var downloadedJob: Job? = null

    /** 使用者自己換過分頁：之後讀到設定也不改回「開啟時顯示的資料夾」。 */
    private var folderPicked = false

    init {
        viewModelScope.launch {
            // 開啟時顯示的資料夾（App 設定）；讀到設定後才開始讀列表
            val start = c.settings.current().startFolder
            if (!folderPicked) _ui.update { it.copy(folder = start) }
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
        // 手機上的檔案被刪掉（相簿、播放器）：打勾與「選取已下載的」要跟著更新
        viewModelScope.launch { c.libraryChanged.collect { refreshDownloaded() } }
        // 在播放器刪掉記錄器上的檔案、或格式化記憶卡後，列表要跟著更新（快取已由對方更新）
        viewModelScope.launch {
            c.dashcamChanged.collect { if (c.connection.isConnected) load() }
        }
    }

    fun selectFolder(folder: Folder) {
        folderPicked = true
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

    /** 頭尾之間全選（依畫面順序）。 */
    fun selectRange(orderedKeys: List<String>) = _ui.update { it.copy(selection = DateGroups.selectRange(orderedKeys, it.selection)) }

    fun setOnlyMissing(on: Boolean) = _ui.update { it.copy(onlyMissing = on, selection = emptySet()) }

    /** 選取畫面上還有檔案沒下載的片段（先重讀手機上的檔案）。 */
    fun selectMissing(visible: List<Clip>, includeRear: Boolean) {
        viewModelScope.launch {
            val downloaded = loadDownloaded()
            val keys = visible.filter { Clips.isMissing(it, includeRear, downloaded) }.map { it.key }
            _ui.update { it.copy(selection = keys.toSet()) }
            if (keys.isEmpty()) _messages.trySend("這裡的檔案都下載過了")
        }
    }

    /**
     * 選取畫面上已經全部下載到手機的片段，接著可以從記錄器刪除、釋放記憶卡空間。
     * 先重讀手機上的檔案：剛在相簿或別的 App 刪掉的，不能被當成已備份。
     */
    fun selectBackedUp(visible: List<Clip>) {
        viewModelScope.launch {
            val downloaded = loadDownloaded()
            val keys = visible.filter { Clips.isBackedUp(it, downloaded) }.map { it.key }
            _ui.update { it.copy(selection = keys.toSet()) }
            _messages.trySend(if (keys.isEmpty()) "沒有已經完整下載的片段" else "已選取 ${keys.size} 段已下載到手機的片段，可以從記錄器刪除")
        }
    }

    /** 把還沒下載的緊急錄影全部加入下載佇列。 */
    fun backupEvents() {
        viewModelScope.launch {
            _ui.update { it.copy(working = true) }
            val message = try {
                val added = c.eventBackup.run()
                if (added > 0) "已加入 $added 個緊急錄影檔案到下載佇列" else "緊急錄影都已經下載過了"
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                "讀取緊急錄影失敗：${e.message}"
            } finally {
                _ui.update { it.copy(working = false) }
            }
            _messages.trySend(message)
        }
    }

    fun selectedClips(): List<Clip> = _ui.value.let { u -> u.clips.filter { it.key in u.selection } }

    fun download(clips: List<Clip>, choice: DownloadChoice) {
        val files = clips.flatMap { Clips.filesFor(it, choice) }
        val added = c.downloads.enqueue(files)
        clearSelection()
        _messages.trySend(
            when {
                added > 0 -> "已加入 $added 個檔案到下載佇列"
                files.isEmpty() -> "選取的片段沒有${if (choice == DownloadChoice.RearOnly) "後鏡頭" else ""}檔案"
                else -> "這些檔案已在下載佇列中"
            },
        )
    }

    fun delete(clips: List<Clip>, includeRear: Boolean) {
        viewModelScope.launch {
            _ui.update { it.copy(working = true) }
            var failed = 0
            val deleted = ArrayList<String>()
            for (clip in clips) {
                var clipOk = true
                for (f in Clips.filesToDelete(clip, includeRear)) {
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

    /** 重讀手機上已下載的檔案（打勾、「未下載」篩選、「選取已下載的」都靠它）；連續觸發時只留最後一次。 */
    fun refreshDownloaded() {
        downloadedJob?.cancel()
        downloadedJob = viewModelScope.launch { loadDownloaded() }
    }

    private suspend fun loadDownloaded(): Map<String, Long> {
        val map = withContext(Dispatchers.IO) { runCatching { c.sink.downloaded() }.getOrDefault(emptyMap()) }
        _ui.update { it.copy(downloaded = map) }
        return map
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { BrowserViewModel(c) } }
    }
}
