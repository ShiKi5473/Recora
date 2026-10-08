package tw.com.mufudashcam.download

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tw.com.mufudashcam.MainActivity
import tw.com.mufudashcam.MufuApp
import tw.com.mufudashcam.ui.Formats

/**
 * 下載用的前景服務（dataSync）。佇列本身在 [DownloadRepository]，這裡只負責讓行程保持在前景、
 * 顯示進度通知，佇列結束就自行停止。
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var finishedAtStart: Set<String> = emptySet()

    private val repo get() = (application as MufuApp).container.downloads
    private val nm get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            repo.cancelAll()
            if (!observing) stopSelf()
            return START_NOT_STICKY
        }
        createChannels()
        ServiceCompat.startForeground(
            this, PROGRESS_ID, buildProgress(repo.tasks.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (!observing) {
            observing = true
            finishedAtStart = repo.tasks.value.filter { !it.isActive }.map { it.id }.toSet()
            scope.launch {
                repo.tasks.collect { tasks ->
                    nm.notify(PROGRESS_ID, buildProgress(tasks))
                    delay(500)
                }
            }
            scope.launch {
                repo.active.first { !it }
                finish()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ 的 dataSync 時間上限到了：取消佇列（會觸發 recordon 收尾）並停止。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        repo.cancelAll()
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        postSummary()
        stopSelf()
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
        if (running == null) {
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
        private const val ACTION_CANCEL = "tw.com.mufudashcam.action.CANCEL_DOWNLOADS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
