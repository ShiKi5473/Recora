package tw.com.mufudashcam.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tw.com.mufudashcam.data.DashcamFile
import tw.com.mufudashcam.data.api.DashcamApi
import tw.com.mufudashcam.data.net.DashcamOfflineException
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class TaskStatus { Queued, Running, Done, Skipped, Failed, Cancelled }

data class DownloadTask(
    val file: DashcamFile,
    val status: TaskStatus = TaskStatus.Queued,
    val bytes: Long = 0,
    val total: Long = file.size,
    val speedBps: Long = 0,
    val message: String? = null,
    val resultUri: Uri? = null,
) {
    val id: String get() = file.sdPath
    val isActive: Boolean get() = status == TaskStatus.Queued || status == TaskStatus.Running
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * 下載佇列：一次只下載一個檔案（記錄器是單核心 + 2.4 GHz Wi-Fi，平行下載沒有幫助）。
 * 連線中斷時檔案留在佇列裡等待，重新連上（[resume]）後從中斷處接著下載。
 * 不送 recordoff：實測手機連線期間記錄器本來就停在 Standby，送了也沒有幫助，
 * 萬一 recordon 前就斷線，反而可能讓記錄器離線後不再錄影。
 */
class DownloadRepository(
    private val context: Context,
    private val api: DashcamApi,
    private val sink: MediaStoreSink,
    httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    /** 佇列存檔：App 重開後還在；null = 不保存（測試用）。 */
    queueStore: QueueStore?,
    /** 下載到 App 資料夾底下的哪個子資料夾（依「下載資料夾分類」設定）。 */
    private val subdirOf: (DashcamFile) -> String? = { null },
    private val isConnected: () -> Boolean,
) {
    private val downloader = RangeDownloader(
        httpClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build(),
    )

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var worker: Job? = null

    @Volatile
    private var current: Pair<String, Job>? = null

    /** [hold] 的次數；大於 0 時佇列暫停（例如正在從記錄器串流播放）。 */
    private val holds = MutableStateFlow(0)

    /** 因 [hold] 而中斷的那個檔案：回到等待中，而不是變成「已取消」。 */
    @Volatile
    private var pausingId: String? = null

    /** 佇列目前是否因 [hold] 暫停（通知用：暫停不算下載結束）。 */
    val isHeld: Boolean get() = holds.value > 0

    init {
        if (queueStore != null) {
            // 上次沒下載完的檔案放回佇列，連上記錄器（resume）後從中斷處接著下載
            _tasks.value = queueStore.load().map { DownloadTask(it, message = WAITING_MESSAGE) }
            // 進行中的檔案有增減時存檔（下載進度的更新不會觸發）
            scope.launch(Dispatchers.IO) {
                _tasks.map { list -> list.filter { it.isActive }.map { it.file } }
                    .distinctUntilChanged()
                    .collectLatest { queueStore.save(it) }
            }
        }
    }

    /** @return 實際加入佇列的檔案數 */
    fun enqueue(files: List<DashcamFile>): Int {
        var added = 0
        _tasks.update { list ->
            val activeIds = list.filter { it.isActive }.map { it.id }.toSet()
            val fresh = files.distinctBy { it.sdPath }.filter { it.sdPath !in activeIds }
            val freshIds = fresh.map { it.sdPath }.toSet()
            added = fresh.size
            list.filterNot { it.id in freshIds } + fresh.map { DownloadTask(it) }
        }
        if (added > 0) startWorker()
        return added
    }

    fun retry(id: String) {
        _tasks.update { list -> list.map { if (it.id == id && !it.isActive) DownloadTask(it.file) else it } }
        startWorker()
    }

    fun retryAllFailed() {
        _tasks.update { list ->
            list.map {
                if (it.status == TaskStatus.Failed || it.status == TaskStatus.Cancelled) DownloadTask(it.file) else it
            }
        }
        startWorker()
    }

    fun cancel(id: String) {
        updateTask(id) { if (it.status == TaskStatus.Queued) it.copy(status = TaskStatus.Cancelled) else it }
        current?.takeIf { it.first == id }?.second?.cancel()
    }

    fun cancelAll() {
        _tasks.update { list ->
            list.map { if (it.status == TaskStatus.Queued) it.copy(status = TaskStatus.Cancelled) else it }
        }
        current?.second?.cancel()
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.isActive } }
    }

    /**
     * 暫停佇列直到 [release]。記錄器一次只服務一個連線，串流播放時下載會跟播放器互搶而一起斷線；
     * 正在下載的檔案停在目前位置，之後從中斷處續傳。
     * @return 是否真的有下載被暫停（呼叫端用來提示使用者）
     */
    @Synchronized
    fun hold(): Boolean {
        holds.update { it + 1 }
        current?.let { (id, job) ->
            pausingId = id
            job.cancel()
        }
        return _tasks.value.any { it.isActive }
    }

    fun release() {
        holds.update { (it - 1).coerceAtLeast(0) }
        startWorker()
    }

    /** 連上記錄器時呼叫：接著下載斷線前沒下載完的檔案。 */
    fun resume() = startWorker()

    @Synchronized
    private fun startWorker() {
        if (worker != null) return
        if (holds.value > 0) return
        if (_tasks.value.none { it.status == TaskStatus.Queued }) return
        if (!isConnected()) {
            markWaiting()
            return
        }
        _tasks.update { list -> list.map { if (it.status == TaskStatus.Queued) it.copy(message = null) else it } }
        _active.value = true
        try {
            DownloadService.start(context)
        } catch (e: IllegalStateException) {
            // 背景時不允許啟動前景服務；App 在前景時佇列照常執行
        }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    runQueue()
                    // 與 startWorker 同步檢查：收尾期間新加入的檔案由同一個 worker 接著處理
                    val more = synchronized(this@DownloadRepository) {
                        val queued = holds.value == 0 && isConnected() &&
                            _tasks.value.any { it.status == TaskStatus.Queued }
                        if (!queued) {
                            worker = null
                            _active.value = false
                        }
                        queued
                    }
                    if (!more) break
                }
            } finally {
                val self = coroutineContext.job
                synchronized(this@DownloadRepository) {
                    if (worker === self) {
                        worker = null
                        _active.value = false
                    }
                }
            }
        }
        worker = job
        job.start()
    }

    private suspend fun runQueue() = coroutineScope {
        while (true) {
            val next = _tasks.value.firstOrNull { it.status == TaskStatus.Queued } ?: break
            if (!isConnected()) {
                markWaiting()
                break
            }
            // 與 hold() 同步：暫停之後不再開始下一個檔案
            val job = synchronized(this@DownloadRepository) {
                if (holds.value > 0) null else launch { runTask(next) }.also { current = next.id to it }
            } ?: break
            job.join()
            current = null
            pausingId = null
        }
    }

    private suspend fun runTask(task: DownloadTask) {
        val f = task.file
        updateTask(task.id) { it.copy(status = TaskStatus.Running, message = null, speedBps = 0) }
        try {
            val existing = withContext(Dispatchers.IO) { sink.find(f.name, f.isImage) }
            if (existing != null && !existing.pending && (f.size <= 0 || existing.size == f.size)) {
                updateTask(task.id) {
                    it.copy(
                        status = TaskStatus.Done, bytes = existing.size, total = existing.size,
                        message = "已存在，略過", resultUri = existing.uri,
                    )
                }
                return
            }

            val url = api.resolveUrl(f.sdPath)
            if (url == null) {
                updateTask(task.id) {
                    if (f.guessed) {
                        it.copy(status = TaskStatus.Skipped, message = "記錄器上沒有對應的後鏡頭檔")
                    } else {
                        it.copy(status = TaskStatus.Failed, message = "記錄器上找不到此檔案")
                    }
                }
                return
            }

            val (uri, start) = withContext(Dispatchers.IO) {
                if (existing != null && existing.pending) {
                    existing.uri to existing.size
                } else {
                    if (existing != null) sink.delete(existing.uri) // 本機舊檔大小不符，重新下載
                    sink.create(f.name, f.isImage, subdirOf(f)) to 0L
                }
            }

            val meter = SpeedMeter(start)
            val (bytes, total) = downloader.download(
                url = url,
                startOffset = start,
                expectedSize = f.size,
                openOutput = { offset -> sink.openOutput(uri, offset) },
            ) { b, t ->
                val speed = meter.sample(b) ?: return@download
                updateTask(task.id) { it.copy(bytes = b, total = if (t > 0) t else it.total, speedBps = speed) }
            }
            if (total > 0 && bytes != total) throw IOException("檔案大小不符（$bytes / $total）")
            withContext(Dispatchers.IO) { sink.finish(uri) }
            updateTask(task.id) {
                it.copy(
                    status = TaskStatus.Done, bytes = bytes, total = bytes,
                    speedBps = meter.average(bytes), message = null, resultUri = uri,
                )
            }
        } catch (e: CancellationException) {
            if (pausingId == task.id) {
                pausingId = null
                updateTask(task.id) { it.copy(status = TaskStatus.Queued, speedBps = 0, message = "暫停中：正在播放記錄器上的影片") }
            } else {
                updateTask(task.id) { it.copy(status = TaskStatus.Cancelled, message = "已取消（再次下載會從中斷處續傳）") }
            }
            throw e
        } catch (e: Exception) {
            if (e is DashcamOfflineException || !isConnected()) {
                // 斷線造成的失敗：留在佇列，重新連上後從中斷處繼續
                updateTask(task.id) { it.copy(status = TaskStatus.Queued, speedBps = 0, message = WAITING_MESSAGE) }
            } else {
                updateTask(task.id) { it.copy(status = TaskStatus.Failed, message = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun markWaiting() {
        _tasks.update { list -> list.map { if (it.status == TaskStatus.Queued) it.copy(message = WAITING_MESSAGE) else it } }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** 每 300 ms 取樣一次速度（平滑處理），也用來節流 UI 更新。 */
    private class SpeedMeter(private val startBytes: Long) {
        private val t0 = SystemClock.elapsedRealtime()
        private var lastT = t0
        private var lastBytes = startBytes
        private var speed = 0L

        fun sample(bytes: Long): Long? {
            val now = SystemClock.elapsedRealtime()
            val dt = now - lastT
            if (dt < 300) return null
            val instant = (bytes - lastBytes) * 1000 / dt
            speed = if (speed == 0L) instant else (speed * 7 + instant * 3) / 10
            lastT = now
            lastBytes = bytes
            return speed
        }

        fun average(bytes: Long): Long {
            val dt = SystemClock.elapsedRealtime() - t0
            return if (dt > 0) (bytes - startBytes) * 1000 / dt else 0
        }
    }

    private companion object {
        const val WAITING_MESSAGE = "等待連線：連上記錄器後會自動從中斷處繼續"
    }
}
