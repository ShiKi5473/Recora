package tw.com.dashcam.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.com.dashcam.data.DiagLog
import java.io.IOException

/**
 * Astro 的停錄租約。手機連著時 Astro 會繼續錄影，但列檔案、下載、串流播放、刪除要先停錄並切到回放模式
 * （官方 App 的檔案頁也是：2001&par=0 → 3001&par=2 → … → 3001 → 2001&par=1）。
 *
 * 有人在用檔案（[hold]／[withFiles]）時維持回放模式；最後一個用完 [idleMs] 後切回錄影模式，
 * 是我們停的錄影就重新開始錄影。10 秒內又有人要用就不切換，避免離開檔案頁又馬上回來時來回停錄、錄出一堆短片段。
 * 改設定、格式化用 [withRecordingStopped]：停錄但留在錄影模式（同官方 App）。
 *
 * 狀態只在這裡記：[mode] 是我們以為記錄器目前所在的模式（剛連上不知道），[stoppedByUs] = 錄影是我們停的。
 */
class AstroSession(
    private val scope: CoroutineScope,
    private val commands: Commands,
    private val idleMs: Long = IDLE_MS,
) {
    /** 實際送給記錄器的指令；失敗丟出 IOException。 */
    interface Commands {
        suspend fun isRecording(): Boolean
        suspend fun stopRecording()
        suspend fun startRecording()
        suspend fun enterPlayback()
        suspend fun enterMovie()
    }

    enum class Mode { Unknown, Movie, Files }

    private val mutex = Mutex()

    @Volatile
    var mode = Mode.Unknown
        private set

    @Volatile
    var stoppedByUs = false
        private set

    private var holders = 0 // 用 synchronized(this) 保護
    private var idleJob: Job? = null

    val inUse: Boolean get() = synchronized(this) { holders > 0 }

    /** 不暫停的版本（Compose 的 DisposableEffect 用）：馬上在背景切換模式，[Lease.close] 歸還。 */
    fun hold(reason: String): Lease {
        acquire()
        scope.launch {
            try {
                ensureFiles()
            } catch (e: IOException) {
                DiagLog.log("切換到檔案模式失敗（$reason）：${e.message}")
            }
        }
        return Lease(::release)
    }

    /** 在檔案模式下執行 [block]：等停錄、切到回放模式完成才開始。 */
    suspend fun <T> withFiles(block: suspend () -> T): T {
        acquire()
        try {
            ensureFiles()
            return block()
        } finally {
            release()
        }
    }

    /**
     * 停錄、留在錄影模式下執行 [block]（改設定、格式化）。有人在用檔案（例如下載中）時丟出 IOException：
     * 改設定要切回錄影模式，會打斷下載。完成後照常 [idleMs] 後恢復錄影，連續改幾項不會一直重新開始錄影。
     */
    suspend fun <T> withRecordingStopped(block: suspend () -> T): T {
        acquire()
        try {
            mutex.withLock {
                if (synchronized(this) { holders > 1 }) throw IOException("記錄器的檔案正在使用中（下載或播放），請稍後再試")
                if (mode != Mode.Movie) {
                    commands.enterMovie()
                    mode = Mode.Movie
                }
                if (commands.isRecording()) {
                    commands.stopRecording()
                    stoppedByUs = true
                }
            }
            return block()
        } finally {
            release()
        }
    }

    /**
     * 不等計時、馬上恢復錄影模式與錄影（即時畫面要錄影模式；中斷連線前）。
     * [force] = false 時有人在用檔案就不動。
     */
    suspend fun restoreNow(force: Boolean = false) {
        synchronized(this) {
            if (!force && holders > 0) return
            idleJob?.cancel()
            idleJob = null
        }
        mutex.withLock { restoreLocked() }
    }

    /**
     * 剛連上（安全網，記錄器不能因為手機連過就停著不錄）：記錄器沒在錄影時——可能是「檔案」頁已經搶先停錄，
     * 也可能是上次停錄中途斷線留下的——一律記成由我們負責恢復錄影；沒有人要用檔案就馬上恢復。
     *
     * 不在這裡清狀態（斷線時 [reset] 過了）：狀態是連線後馬上發出的，「檔案」頁可能比這裡早停錄，
     * 清掉 [stoppedByUs] 就不會再開始錄影。
     */
    suspend fun onConnected() {
        mutex.withLock {
            if (commands.isRecording()) {
                if (mode == Mode.Unknown) mode = Mode.Movie
                return
            }
            stoppedByUs = true
            if (synchronized(this) { holders > 0 }) return
            DiagLog.log("記錄器沒有在錄影：切回錄影模式並開始錄影")
            restoreLocked()
        }
    }

    /** 連線結束：狀態重來（下次連上時記錄器的模式未知）。 */
    fun reset() {
        synchronized(this) {
            idleJob?.cancel()
            idleJob = null
        }
        mode = Mode.Unknown
        stoppedByUs = false
    }

    private fun acquire() {
        synchronized(this) {
            holders++
            idleJob?.cancel()
            idleJob = null
        }
    }

    private fun release() {
        synchronized(this) {
            holders = (holders - 1).coerceAtLeast(0)
            if (holders > 0) return
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(idleMs)
                try {
                    mutex.withLock {
                        if (synchronized(this@AstroSession) { holders == 0 }) restoreLocked()
                    }
                } catch (e: IOException) {
                    DiagLog.log("恢復錄影失敗：${e.message}")
                }
            }
        }
    }

    private suspend fun ensureFiles() = mutex.withLock {
        if (mode == Mode.Files) return@withLock
        if (commands.isRecording()) {
            commands.stopRecording()
            stoppedByUs = true
        }
        commands.enterPlayback()
        mode = Mode.Files
    }

    /** 呼叫端持有 [mutex]。 */
    private suspend fun restoreLocked() {
        if (mode != Mode.Movie) {
            commands.enterMovie()
            mode = Mode.Movie
        }
        if (stoppedByUs) {
            commands.startRecording()
            stoppedByUs = false
        }
    }

    companion object {
        /** 最後一個使用結束後多久恢復錄影。 */
        const val IDLE_MS = 10_000L
    }
}
