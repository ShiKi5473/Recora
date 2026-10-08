package tw.com.mufudashcam.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tw.com.mufudashcam.data.DashcamFile
import tw.com.mufudashcam.data.DiagLog
import tw.com.mufudashcam.data.SettingsStore
import tw.com.mufudashcam.data.api.DashcamApi
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
 * 佇列開始前停止錄影、結束後一定恢復（API 文件 §3.3）；恢復失敗時保留旗標，下次連上時補送。
 */
class DownloadRepository(
    private val context: Context,
    private val api: DashcamApi,
    private val settings: SettingsStore,
    private val sink: MediaStoreSink,
    httpClient: OkHttpClient,
    private val scope: CoroutineScope,
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

    @Synchronized
    private fun startWorker() {
        if (worker != null) return
        if (_tasks.value.none { it.status == TaskStatus.Queued }) return
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
                        val queued = _tasks.value.any { it.status == TaskStatus.Queued }
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

    private suspend fun runQueue() {
        var paused = false
        try {
            if (settings.current().pauseRecordingOnDownload && isConnected()) {
                ignoreIo { api.streamerStop() }
                settings.update { it.copy(recordPausedByApp = true) }
                paused = true
                ignoreIo { api.recordOff() }
                delay(RECORD_OFF_SETTLE_MS) // 讓記錄器把正在錄的那一段寫完關檔
                ignoreIo { DiagLog.log("下載前停止錄影，目前狀態：${api.recordingStatus() ?: "記錄器沒有回報"}") }
            }
            coroutineScope {
                while (true) {
                    val next = _tasks.value.firstOrNull { it.status == TaskStatus.Queued } ?: break
                    if (!isConnected()) {
                        _tasks.update { list ->
                            list.map {
                                if (it.status == TaskStatus.Queued) {
                                    it.copy(status = TaskStatus.Failed, message = "未連線到記錄器")
                                } else {
                                    it
                                }
                            }
                        }
                        break
                    }
                    val job = launch { runTask(next) }
                    current = next.id to job
                    job.join()
                    current = null
                }
            }
        } finally {
            if (paused) {
                withContext(NonCancellable) {
                    try {
                        api.recordOn()
                        settings.update { it.copy(recordPausedByApp = false) }
                    } catch (e: IOException) {
                        // 保留 recordPausedByApp，下次連上時補送 recordon
                    }
                }
            }
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
                    sink.create(f.name, f.isImage) to 0L
                }
            }

            val meter = SpeedMeter(start)
            val (bytes, total) = downloader.download(
                url = url,
                startOffset = start,
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
            updateTask(task.id) { it.copy(status = TaskStatus.Cancelled, message = "已取消（再次下載會從中斷處續傳）") }
            throw e
        } catch (e: Exception) {
            updateTask(task.id) { it.copy(status = TaskStatus.Failed, message = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private suspend fun ignoreIo(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            // 錄影控制失敗不影響下載
        }
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
        const val RECORD_OFF_SETTLE_MS = 1500L
    }
}
