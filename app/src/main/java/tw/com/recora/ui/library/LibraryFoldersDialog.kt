package tw.com.recora.ui.library

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.com.recora.AppContainer
import tw.com.recora.data.DashcamConfig
import tw.com.recora.data.LocalLibrary
import tw.com.recora.download.MediaStoreSink

/**
 * 相簿要讀哪些資料夾：列出手機上有影片或照片的資料夾（含檔案數）來勾選，按「完成」才存進「App 設定」。
 * App 自己的資料夾一定會讀，不列出來；已勾選但現在沒有檔案的資料夾也列出來，才能取消。
 * 「相簿」右上選單與「App 設定」共用。
 */
@Composable
fun LibraryFoldersDialog(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { container.settings.state.value.libraryFolders }
    var selected by remember { mutableStateOf(initial) }
    val found by produceState<List<MediaStoreSink.MediaFolder>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { container.sink.listMediaFolders() }.getOrDefault(emptyList()) }
    }
    val canReadAll = remember {
        listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
            .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("相簿資料夾") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "一定會讀 Movies/${DashcamConfig.LOCAL_DIR} 與 Pictures/${DashcamConfig.LOCAL_DIR}（下載與匯出都存在那裡，改版前的 ${DashcamConfig.LEGACY_LOCAL_DIRS.joinToString("、")} 也一樣）。" +
                        "另外勾選要一起顯示的資料夾（含子資料夾），檔案依檔名分到未剪輯／已剪輯／照片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!canReadAll) {
                    Text(
                        "未允許存取相片和影片：只列得出本 App 建立的檔案（到「相簿」按「允許」）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val list = found
                if (list == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    val rows = remember(list) {
                        val counts = list.filter { !LocalLibrary.isAppFolder(it.path) }.associate { it.path to it.count }
                        (counts.keys + initial).distinct()
                            .sortedWith(
                                compareByDescending<String> { it in initial }
                                    .thenByDescending { counts[it] ?: 0 }
                                    .thenBy { it },
                            )
                            .map { it to (counts[it] ?: 0) }
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(rows, key = { it.first }) { (path, count) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { toggle(path) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = path in selected, onCheckedChange = { toggle(path) })
                                Text(
                                    path.trimEnd('/'),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "$count 個",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val chosen = selected
                // 用 App 的 scope 寫入：關掉對話框馬上離開這頁也會存到
                container.appScope.launch { container.settings.update { it.copy(libraryFolders = chosen) } }
                onDismiss()
            }) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 設定頁顯示用：勾選的資料夾，沒有就是「只有 App 的資料夾」。 */
fun libraryFoldersSummary(folders: Collection<String>): String =
    if (folders.isEmpty()) "只有 App 的資料夾" else folders.sorted().joinToString("、") { it.trimEnd('/') }
