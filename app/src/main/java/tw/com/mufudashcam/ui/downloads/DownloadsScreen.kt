package tw.com.mufudashcam.ui.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.DashcamConfig
import tw.com.mufudashcam.data.PlayItem
import tw.com.mufudashcam.download.DownloadTask
import tw.com.mufudashcam.download.QueueProgress
import tw.com.mufudashcam.download.TaskStatus
import tw.com.mufudashcam.ui.Formats
import tw.com.mufudashcam.ui.common.MessagePane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    container: AppContainer,
    onPlay: (url: String, title: String) -> Unit,
    onEdit: (uri: String, title: String) -> Unit,
) {
    val repo = container.downloads
    val tasks by repo.tasks.collectAsStateWithLifecycle()
    val anyActive = tasks.any { it.isActive }
    val anyFinished = tasks.any { !it.isActive }
    val anyFailed = tasks.any { it.status == TaskStatus.Failed || it.status == TaskStatus.Cancelled }
    val progress = QueueProgress.of(tasks)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("下載")
                        progress?.let { Text(progressText(it), style = MaterialTheme.typography.bodySmall) }
                    }
                },
                actions = {
                    if (anyFailed && !anyActive) TextButton(onClick = repo::retryAllFailed) { Text("重試失敗") }
                    if (anyActive) TextButton(onClick = repo::cancelAll) { Text("全部取消") }
                    if (anyFinished) TextButton(onClick = repo::clearFinished) { Text("清除已結束") }
                },
            )
        },
    ) { pad ->
        if (tasks.isEmpty()) {
            MessagePane(
                icon = Icons.Filled.Download,
                title = "沒有下載項目",
                text = "在「檔案」頁長按選取影片後下載。\n" +
                    "影片存到 Movies/${DashcamConfig.LOCAL_DIR}，照片存到 Pictures/${DashcamConfig.LOCAL_DIR}。",
                modifier = Modifier.padding(pad),
            )
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onCancel = { repo.cancel(task.id) },
                        onRetry = { repo.retry(task.id) },
                        onPlay = {
                            task.resultUri?.let { uri ->
                                container.playQueue = tasks
                                    .filter { it.status == TaskStatus.Done && !it.file.isImage && it.resultUri != null }
                                    .map { PlayItem(it.resultUri.toString(), it.file.name) }
                                onPlay(uri.toString(), task.file.name)
                            }
                        },
                        onEdit = { task.resultUri?.let { onEdit(it.toString(), task.file.name) } },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 標題下的整體進度：剩餘 3 個 · 1.20 GB · 約 8 分鐘（沒在下載時不估時間）。 */
private fun progressText(p: QueueProgress): String = buildString {
    append("剩餘 ${p.files} 個")
    if (p.allSizesKnown || p.bytes > 0) {
        append(" · ")
        if (!p.allSizesKnown) append("至少 ")
        append(Formats.size(p.bytes))
    }
    p.etaSeconds?.let { append(" · ").append(Formats.eta(it)) }
}

@Composable
private fun TaskRow(task: DownloadTask, onCancel: () -> Unit, onRetry: () -> Unit, onPlay: () -> Unit, onEdit: () -> Unit) {
    ListItem(
        headlineContent = { Text(task.file.name) },
        supportingContent = {
            Column {
                val statusText = when (task.status) {
                    TaskStatus.Queued -> "等待中"
                    TaskStatus.Running -> buildString {
                        append(Formats.progress(task.bytes, task.total))
                        if (task.speedBps > 0) append(" · ").append(Formats.speed(task.speedBps))
                    }
                    TaskStatus.Done -> "完成 · ${Formats.size(task.bytes)}" +
                        (if (task.speedBps > 0) " · 平均 ${Formats.speed(task.speedBps)}" else "")
                    TaskStatus.Skipped -> "略過"
                    TaskStatus.Failed -> "失敗"
                    TaskStatus.Cancelled -> "已取消"
                }
                Text(statusText)
                task.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.status == TaskStatus.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (task.status == TaskStatus.Running) {
                    Spacer(Modifier.height(6.dp))
                    val fraction = task.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        },
        trailingContent = {
            when (task.status) {
                TaskStatus.Queued, TaskStatus.Running -> IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "取消")
                }
                TaskStatus.Failed, TaskStatus.Cancelled -> IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重試")
                }
                TaskStatus.Done -> if (!task.file.isImage && task.resultUri != null) {
                    Row {
                        IconButton(onClick = onEdit) { Icon(Icons.Filled.ContentCut, contentDescription = "剪輯") }
                        IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "播放") }
                    }
                }
                TaskStatus.Skipped -> Unit
            }
        },
    )
}

