package tw.com.recora.ui.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import tw.com.recora.ui.theme.recora
import tw.com.recora.ui.theme.tabular
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
import tw.com.recora.AppContainer
import tw.com.recora.data.PlayItem
import tw.com.recora.download.DownloadTask
import tw.com.recora.download.QueueProgress
import tw.com.recora.download.TaskStatus
import tw.com.recora.ui.OnPlay
import tw.com.recora.ui.Formats
import tw.com.recora.ui.common.MessagePane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    container: AppContainer,
    onPlay: OnPlay,
    onEdit: (uri: String, title: String) -> Unit,
) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val repo = container.downloads
    val tasks by repo.tasks.collectAsStateWithLifecycle()
    val anyActive = tasks.any { it.isActive }
    val anyFinished = tasks.any { !it.isActive }
    val anyFailed = tasks.any { it.status == TaskStatus.Failed || it.status == TaskStatus.Cancelled }
    val progress = QueueProgress.of(tasks)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下載") },
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
                    "影片存到 Movies/${settings.albumDir}，照片存到 Pictures/${settings.albumDir}。",
                modifier = Modifier.padding(pad),
            )
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                progress?.let { p ->
                    item(key = "summary") { SummaryCard(p) }
                }
                items(tasks, key = { it.id }) { task ->
                    TaskRow(
                        modifier = Modifier.animateItem(),
                        task = task,
                        onCancel = { repo.cancel(task.id) },
                        onRetry = { repo.retry(task.id) },
                        onPlay = {
                            task.resultUri?.let { uri ->
                                val queue = tasks
                                    .filter { it.status == TaskStatus.Done && !it.file.isImage && it.resultUri != null }
                                    .map { PlayItem(it.resultUri.toString(), it.file.name) }
                                onPlay(uri.toString(), task.file.name, queue)
                            }
                        },
                        onEdit = { task.resultUri?.let { onEdit(it.toString(), task.file.name) } },
                    )
                    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
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
private fun TaskRow(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        leadingContent = { StatusIcon(task) },
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
                Text(statusText, style = MaterialTheme.typography.bodyMedium.tabular())
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


/** 佇列上方的摘要：還剩幾個、多少容量、預估時間。 */
@Composable
private fun SummaryCard(p: QueueProgress) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                progressText(p),
                style = MaterialTheme.typography.titleSmall.tabular(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** 任務狀態：排隊、下載中、完成、失敗，各放在對應色調的圓底裡。 */
@Composable
private fun StatusIcon(task: DownloadTask) {
    val scheme = MaterialTheme.colorScheme
    val (icon, container, content) = when (task.status) {
        TaskStatus.Queued -> Triple(Icons.Filled.Schedule, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        TaskStatus.Running -> Triple(Icons.Filled.Download, scheme.primaryContainer, scheme.onPrimaryContainer)
        TaskStatus.Done, TaskStatus.Skipped ->
            Triple(Icons.Filled.CheckCircle, MaterialTheme.recora.successContainer, MaterialTheme.recora.onSuccessContainer)
        TaskStatus.Failed -> Triple(Icons.Filled.ErrorOutline, scheme.errorContainer, scheme.onErrorContainer)
        TaskStatus.Cancelled -> Triple(Icons.Filled.Close, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
    }
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) }
    }
}
