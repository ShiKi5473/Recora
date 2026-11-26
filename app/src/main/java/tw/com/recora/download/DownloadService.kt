package tw.com.recora.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tw.com.recora.MainActivity
import tw.com.recora.RecoraApp
import tw.com.recora.ui.Formats

/**
 * 下載用的前景服務（dataSync）。佇列本身在 [DownloadRepository]，這裡只負責讓行程保持在前景、
 * 顯示進度通知，佇列結束就自行停止。
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var finishedAtStart: Set<String> = emptySet()

    /** 更新進度通知的協程：收尾時先停掉，移除前景通知之後才不會又被 notify 回來、留下常駐通知。 */
    private var progressJob: Job? = null

    /** 最近一次 [onStartCommand] 的 startId：收尾時用來確認之後沒有新的啟動要求。 */
    private var lastStartId = 0

    private val repo get() = (application as RecoraApp).container.downloads
    private val nm get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            repo.cancelAll()
            if (!observing) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        createChannels()
        val notification = buildProgress(repo.tasks.value)
        try {
            ServiceCompat.startForeground(
                this, PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: SecurityException) {
            ServiceCompat.startForeground(
                this, PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
        if (!observing) {
            observing = true
            finishedAtStart = repo.tasks.value.filter { !it.isActive }.map { it.id }.toSet()
            progressJob = scope.launch {
                repo.tasks.collect { tasks ->
                    nm.notify(PROGRESS_ID, buildProgress(tasks))
                    delay(500)
                }
            }
            awaitFinish()
        }
        return START_NOT_STICKY
    }

    /**
     * Android 14/15+ 前景服務時間上限到了：暫停下載並馬上停止服務，保留佇列與已下載進度以便下次自動續傳。
     */
    override fun onTimeout(startId: Int) {
        repo.pauseForTimeout()
        stop(force = true)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        repo.pauseForTimeout()
        stop(force = true)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 等佇列結束（[DownloadRepository.active] 變 false）後收尾。 */
    private fun awaitFinish() {
        scope.launch {
            repo.active.first { !it }
            finish()
        }
    }

    private fun finish() {
        if (!observing) return // onTimeout 已經停止
        // 收尾前又開始下載（例如佇列剛結束又加入檔案）：繼續當前景服務，等這批也結束
        if (repo.active.value) {
            awaitFinish()
            return
        }
        stop(force = false)
    }

    /**
     * 移除進度通知、顯示摘要並停止服務。一般用 stopSelfResult：收尾時剛好有新的啟動要求（還沒輪到 onStartCommand），
     * 服務被停掉的話系統會判定 startForegroundService() 之後沒有呼叫 startForeground() 而讓 App 閃退；
     * 這時服務不停，那次的 onStartCommand 因為 [observing] = false 會重新開始觀察（摘要從那時起重新算）。
     * [force] = true（[onTimeout]）一律停止。
     */
    private fun stop(force: Boolean) {
        progressJob?.cancel()
        progressJob = null
        observing = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        postSummary()
        if (force) stopSelf() else stopSelfResult(lastStartId)
    }

    private fun buildProgress(tasks: List<DownloadTask>): Notification {
        val running = tasks.firstOrNull { it.status == TaskStatus.Running }
        val queued = tasks.count { it.status == TaskStatus.Queued }
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, "全部取消", cancelIntent())
        if (running == null && repo.isHeld) {
            // 串流播放、即時畫面、送指令時暫停（服務留著），恢復後從中斷處繼續
            builder.setContentTitle("下載暫停中").setContentText(if (queued > 0) "還有 $queued 個，稍後自動繼續" else null)
        } else if (running == null) {
            builder.setContentTitle("準備下載…").setProgress(0, 0, true)
        } else {
            val text = buildString {
                append(Formats.progress(running.bytes, running.total))
                if (running.speedBps > 0) append(" · ").append(Formats.speed(running.speedBps))
                if (queued > 0) append(" · 還有 $queued 個")
            }
            builder.setContentTitle(running.file.name).setContentText(text)
            val fraction = running.fraction
            if (fraction != null) builder.setProgress(100, (fraction * 100).toInt(), false) else builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun postSummary() {
        val waiting = repo.tasks.value.count { it.isActive }
        if (waiting > 0) {
            // 串流播放中的暫停不用通知；斷線則提醒使用者還有檔案沒下載完
            if (!repo.isHeld) postNotice("下載中斷", "與記錄器的連線中斷，重新連上後會自動繼續（還有 $waiting 個）")
            return
        }
        val batch = repo.tasks.value.filter { it.id !in finishedAtStart && !it.isActive }
        if (batch.isEmpty()) return
        val ok = batch.count { it.status == TaskStatus.Done }
        val failed = batch.count { it.status == TaskStatus.Failed }
        val text = buildString {
            append("完成 $ok 個")
            if (failed > 0) append("，失敗 $failed 個")
            val cancelled = batch.count { it.status == TaskStatus.Cancelled }
            if (cancelled > 0) append("，取消 $cancelled 個")
        }
        postNotice("下載結束", text)
    }

    private fun postNotice(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(SUMMARY_ID, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "下載進度", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "下載完成", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val CHANNEL_PROGRESS = "download_progress"
        private const val CHANNEL_DONE = "download_done"
        private const val PROGRESS_ID = 1
        private const val SUMMARY_ID = 2
        private const val ACTION_CANCEL = "tw.com.recora.action.CANCEL_DOWNLOADS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
