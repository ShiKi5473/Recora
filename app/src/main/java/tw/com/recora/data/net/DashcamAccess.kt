package tw.com.recora.data.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import tw.com.recora.data.api.DashcamApi
import tw.com.recora.data.api.Lease

/**
 * 記錄器的使用權。MUFU 的 GoAhead 一次只能好好服務一個連線（MUFU_V60P_API.md【實測】），
 * 所以用到記錄器的地方都經過這裡，規則只寫在這一處：
 *
 * - 下載的優先序最低：指令（讀列表、刪除、改設定、讀照片…）、串流播放、即時畫面要用時，先暫停下載佇列，
 *   而且等正在傳的檔案真的停下來（連線切斷）才開始；那個檔案停在目前位置，之後從中斷處續傳。
 * - 指令一次只送一個（[command] 排隊）。最後一個指令結束後等 [commandIdleMs] 才恢復下載：
 *   連續幾個指令（例如「裝置」頁一次讀好幾項）不會讓下載一直斷斷續續。
 * - 串流播放、即時畫面用 [acquire] 拿長時間的使用權，用完 close 才恢復下載。
 *
 * 一般程式不用直接呼叫 [command]：`AppContainer.api` 拿到的介面已經包好了（[tw.com.recora.data.api.GuardedDashcamApi]），
 * Coil 讀記錄器上的照片走 [interceptor]。
 * Astro 的模式（停錄、回放）仍由 [DashcamApi.holdFiles]／[DashcamApi.withFiles] 管理；
 * Astro 的 keep-alive 照官方 App 每 5 秒直接送，不經過這裡。
 */
class DashcamAccess(
    private val downloads: Downloads,
    /** 目前的記錄器（未包裝的實作），[acquire] 切換 Astro 模式用。 */
    private val api: () -> DashcamApi,
    private val scope: CoroutineScope,
    private val commandIdleMs: Long = COMMAND_IDLE_MS,
) {
    /** 下載佇列需要提供的操作（實作：DownloadRepository）。 */
    interface Downloads {
        /** 暫停佇列並中斷正在下載的檔案；回傳是否真的有下載被暫停。 */
        fun hold(reason: String): Boolean

        fun release()

        /** 等被中斷的檔案真的停下來。 */
        suspend fun awaitPaused()
    }

    /** [acquire] 拿到的使用權：[pausedDownloads] = 真的有下載因此暫停（提示使用者用）。 */
    class Grant internal constructor(val pausedDownloads: Boolean, release: () -> Unit) : AutoCloseable {
        private val lease = Lease(release)

        override fun close() = lease.close()
    }

    private val commands = Mutex()

    // 以下用 synchronized(this) 保護
    private var commandUsers = 0
    private var commandHold = false
    private var releaseJob: Job? = null

    /**
     * 長時間使用（串流播放、即時畫面）：暫停下載並等它停下來才回傳；[files] = 也要 Astro 的檔案模式（等切換完成）。
     * 用完 close 回傳的 [Grant]。
     */
    suspend fun acquire(reason: String, files: Boolean = false): Grant {
        val paused = downloads.hold(reason)
        var filesLease = Lease.NONE
        val grant = Grant(paused) {
            filesLease.close()
            downloads.release()
        }
        try {
            downloads.awaitPaused()
            if (files) filesLease = api().awaitFiles(reason)
        } catch (e: Throwable) {
            grant.close()
            throw e
        }
        return grant
    }

    /** 送一個（或一組連續的）指令：排隊、暫停下載，執行 [block]。 */
    suspend fun <T> command(block: suspend () -> T): T {
        beginCommand()
        try {
            return commands.withLock {
                downloads.awaitPaused()
                block()
            }
        } finally {
            endCommand()
        }
    }

    /**
     * 給 OkHttp 用（Coil 從記錄器讀照片）：整個請求連同讀完內容算一個指令。
     * 內容先讀進記憶體再交出去（照片不大），使用權不會因為呼叫端忘了關 body 而一直佔著。
     */
    val interceptor = Interceptor { chain ->
        beginCommand()
        try {
            runBlocking {
                commands.withLock {
                    downloads.awaitPaused()
                    val response = chain.proceed(chain.request())
                    val body = response.body ?: return@withLock response
                    val bytes = body.use { it.bytes() }
                    response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
                }
            }
        } finally {
            endCommand()
        }
    }

    private fun beginCommand() {
        synchronized(this) {
            commandUsers++
            releaseJob?.cancel()
            releaseJob = null
            if (!commandHold) {
                commandHold = true
                downloads.hold(COMMAND_REASON)
            }
        }
    }

    private fun endCommand() {
        synchronized(this) {
            commandUsers--
            if (commandUsers > 0) return
            releaseJob = scope.launch {
                delay(commandIdleMs)
                synchronized(this@DashcamAccess) {
                    if (commandUsers == 0 && commandHold) {
                        commandHold = false
                        downloads.release()
                    }
                }
            }
        }
    }

    companion object {
        /** 最後一個指令結束後多久恢復下載。 */
        const val COMMAND_IDLE_MS = 1_500L

        const val COMMAND_REASON = "正在讀取或設定記錄器，稍後自動繼續"
    }
}
