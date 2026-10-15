package tw.com.recora.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import tw.com.recora.ui.common.ConfirmDeleteDialog
import tw.com.recora.ui.common.DateHeader
import tw.com.recora.ui.common.MediaBadge
import tw.com.recora.ui.common.SelectionTopBar
import tw.com.recora.ui.common.Thumbnail
import tw.com.recora.ui.theme.tabular
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import tw.com.recora.AppContainer
import tw.com.recora.data.Camera
import tw.com.recora.data.Clip
import tw.com.recora.data.PlayItem
import tw.com.recora.data.DashcamFile
import tw.com.recora.data.DownloadChoice
import tw.com.recora.data.Folder
import tw.com.recora.data.api.Lease
import tw.com.recora.data.net.ConnState
import tw.com.recora.ui.Formats
import tw.com.recora.ui.common.ConnectionChip
import tw.com.recora.ui.common.DateGroups
import tw.com.recora.ui.common.DateJumpButton
import tw.com.recora.ui.common.MessagePane
import tw.com.recora.ui.common.PhotoDialog
import tw.com.recora.ui.common.NotConnectedPane
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(container: AppContainer, onPlay: (url: String, title: String) -> Unit) {
    val vm: BrowserViewModel = viewModel(factory = BrowserViewModel.factory(container))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var pendingDelete by remember { mutableStateOf<List<Clip>?>(null) }
    var viewingPhoto by remember { mutableStateOf<DashcamFile?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // 回到這個分頁時重讀手機上的檔案（例如在別的 App 刪了已下載的影片），打勾才不會過期
    LifecycleResumeEffect(vm) {
        vm.refreshDownloaded()
        onPauseOrDispose { }
    }
    // Astro：「檔案」頁可見時維持停錄＋檔案模式，離開約 10 秒後自動恢復錄影（MUFU 沒有動作）
    val connectedNow = conn is ConnState.Connected
    LifecycleResumeEffect(connectedNow) {
        val lease = if (connectedNow) container.api.holdFiles("檔案頁") else Lease.NONE
        vm.setVisible(true)
        onPauseOrDispose {
            vm.setVisible(false)
            lease.close()
        }
    }

    // 畫面上的片段（套用「未下載」篩選），依日期分組
    val clips = remember(ui.clips, ui.onlyMissing, ui.downloaded, settings.includeRear) { ui.visibleClips(settings.includeRear) }
    val byDate = remember(clips) { clips.groupBy { it.date } }
    val selectionMode = ui.selection.isNotEmpty()
    val play: (DashcamFile) -> Unit = { file ->
        if (file.isImage) {
            viewingPhoto = file
        } else {
            vm.resolvePlayback(file) { url ->
                // 左右滑動的清單：同資料夾、同鏡頭，順序同畫面；目前這段用剛解析出的網址（可能是已下載的本機檔）
                container.playQueue = clips
                    .mapNotNull { clip -> if (file.camera == Camera.Rear) clip.rear else clip.front }
                    .filter { !it.isImage }
                    .map { f -> PlayItem(if (f.sdPath == file.sdPath) url else vm.thumbnailUrl(f), f.name, f.sdPath) }
                onPlay(url, file.name)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selectionMode) {
                SelectionBar(
                    count = ui.selection.size,
                    includeRearDefault = settings.includeRear,
                    onClose = vm::clearSelection,
                    onSelectRange = { vm.selectRange(clips.map { it.key }) },
                    onDownload = { choice -> vm.download(vm.selectedClips(), choice) },
                    onDelete = { pendingDelete = vm.selectedClips() },
                )
            } else {
                TopAppBar(
                    title = { Text("記錄器檔案") },
                    actions = {
                        if (conn is ConnState.Connected) {
                            DateJumpButton(byDate.map { (date, group) -> Formats.date(date) to group.size }) { group ->
                                val index = DateGroups.headerIndex(byDate.values.map { it.size }, group)
                                scope.launch { listState.animateScrollToItem(index) }
                            }
                            IconButton(onClick = { vm.load(refresh = true) }, enabled = !ui.loading) {
                                Icon(Icons.Filled.Refresh, contentDescription = "重新整理")
                            }
                            BrowserMenu(
                                onSelectMissing = { vm.selectMissing(clips, settings.includeRear) },
                                onSelectBackedUp = { vm.selectBackedUp(clips) },
                                onBackupEvents = vm::backupEvents,
                            )
                        }
                        ConnectionChip(container)
                    },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = ui.folder.ordinal) {
                Folder.entries.forEach { folder ->
                    Tab(
                        selected = ui.folder == folder,
                        onClick = { vm.selectFolder(folder) },
                        text = { Text(folder.label) },
                    )
                }
            }
            if (conn is ConnState.Connected) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = !ui.onlyMissing, onClick = { vm.setOnlyMissing(false) }, label = { Text("全部") })
                    FilterChip(selected = ui.onlyMissing, onClick = { vm.setOnlyMissing(true) }, label = { Text("未下載") })
                }
            }
            if (ui.loading || ui.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                conn !is ConnState.Connected -> NotConnectedPane(container)
                ui.error != null && ui.clips.isEmpty() -> MessagePane(Icons.Filled.FolderOff, ui.error!!) {
                    TextButton(onClick = { vm.load() }) { Text("重試") }
                }
                ui.clips.isEmpty() && !ui.loading -> MessagePane(Icons.Filled.FolderOff, "沒有檔案")
                clips.isEmpty() && !ui.loading -> MessagePane(
                    icon = Icons.Filled.CheckCircle,
                    title = "這裡的檔案都下載過了",
                    text = "切回「全部」可以看到所有檔案",
                )
                else -> ClipList(
                    byDate = byDate,
                    listState = listState,
                    ui = ui,
                    selectionMode = selectionMode,
                    includeRearDefault = settings.includeRear,
                    thumbnailUrl = vm::thumbnailUrl,
                    onToggle = vm::toggle,
                    onSelectAll = vm::selectAll,
                    onPlay = play,
                    onDownload = { clip, choice -> vm.download(listOf(clip), choice) },
                    onDelete = { clip -> pendingDelete = listOf(clip) },
                )
            }
        }
    }

    pendingDelete?.let { clips ->
        DeleteDialog(
            clips = clips,
            onDismiss = { pendingDelete = null },
            onConfirm = { includeRear ->
                pendingDelete = null
                vm.delete(clips, includeRear)
            },
        )
    }

    viewingPhoto?.let { photo ->
        // 同資料夾、同鏡頭的照片，順序同畫面
        val photos = clips
            .mapNotNull { clip -> if (photo.camera == Camera.Rear) clip.rear else clip.front }
            .filter { it.isImage && !it.guessed }
        PhotoDialog(
            container = container,
            photos = photos.map { PlayItem(vm.thumbnailUrl(it), it.name, it.sdPath) },
            startIndex = photos.indexOfFirst { it.sdPath == photo.sdPath },
            onDismiss = { viewingPhoto = null },
        )
    }
}

/** 「檔案」頁右上的選單：快速選取、備份緊急錄影。 */
@Composable
private fun BrowserMenu(onSelectMissing: () -> Unit, onSelectBackedUp: () -> Unit, onBackupEvents: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("選取未下載的") }, onClick = { open = false; onSelectMissing() })
            DropdownMenuItem(
                text = { Text("選取已下載的（可從記錄器刪除）") },
                onClick = { open = false; onSelectBackedUp() },
            )
            DropdownMenuItem(
                text = { Text("備份所有未下載的緊急錄影") },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                onClick = { open = false; onBackupEvents() },
            )
        }
    }
}

/** 下載選項的順序：「預設下載後鏡頭」設定決定排第一的是哪個。 */
private fun downloadOptions(includeRearDefault: Boolean) =
    if (includeRearDefault) {
        listOf(DownloadChoice.Both, DownloadChoice.FrontOnly, DownloadChoice.RearOnly)
    } else {
        listOf(DownloadChoice.FrontOnly, DownloadChoice.Both, DownloadChoice.RearOnly)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    includeRearDefault: Boolean,
    onClose: () -> Unit,
    onSelectRange: () -> Unit,
    onDownload: (DownloadChoice) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    SelectionTopBar(title = "已選 $count 段", onClose = onClose) {
        // 選了頭尾兩段後，把中間的全部選起來
        if (count >= 2) TextButton(onClick = onSelectRange) { Text("選取範圍") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Download, contentDescription = "下載") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                downloadOptions(includeRearDefault).forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.label) }, onClick = { menu = false; onDownload(choice) })
                }
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "刪除") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipList(
    byDate: Map<LocalDate?, List<Clip>>,
    listState: LazyListState,
    ui: BrowserUi,
    selectionMode: Boolean,
    includeRearDefault: Boolean,
    thumbnailUrl: (DashcamFile) -> String,
    onToggle: (String) -> Unit,
    onSelectAll: (Collection<String>) -> Unit,
    onPlay: (DashcamFile) -> Unit,
    onDownload: (Clip, DownloadChoice) -> Unit,
    onDelete: (Clip) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        byDate.forEach { (date, clips) ->
            stickyHeader(key = "h-$date") {
                DateHeader(
                    title = "${Formats.date(date)} · ${clips.size} ${if (ui.folder == Folder.Photo) "張" else "段"}",
                    onSelectAll = { onSelectAll(clips.map { it.key }) },
                )
            }
            items(clips, key = { it.key }) { clip ->
                ClipRow(
                    clip = clip,
                    ui = ui,
                    selectionMode = selectionMode,
                    selected = clip.key in ui.selection,
                    thumbnailUrl = thumbnailUrl,
                    includeRearDefault = includeRearDefault,
                    onClick = { if (selectionMode) onToggle(clip.key) else onPlay(clip.primary) },
                    onLongClick = { onToggle(clip.key) },
                    onPlay = onPlay,
                    onDownload = { choice -> onDownload(clip, choice) },
                    onDelete = { onDelete(clip) },
                )
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipRow(
    clip: Clip,
    ui: BrowserUi,
    selectionMode: Boolean,
    selected: Boolean,
    thumbnailUrl: (DashcamFile) -> String,
    includeRearDefault: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: (DashcamFile) -> Unit,
    onDownload: (DownloadChoice) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            when {
                selectionMode -> Checkbox(checked = selected, onCheckedChange = { onLongClick() })
                clip.primary.isImage -> Thumbnail(
                    model = thumbnailUrl(clip.primary),
                    placeholder = Icons.Filled.Image,
                    modifier = Modifier.size(width = 96.dp, height = 54.dp),
                )
                else -> Thumbnail(
                    model = null,
                    placeholder = Icons.Filled.Videocam,
                    modifier = Modifier.size(width = 96.dp, height = 54.dp),
                )
            }
        },
        headlineContent = { Text(Formats.time(clip.dateTime), style = MaterialTheme.typography.titleMedium.tabular()) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                clip.front?.let { FileBadge("前", it, ui.isDownloaded(it)) }
                clip.rear?.let { FileBadge("後", it, ui.isDownloaded(it)) }
            }
        },
        trailingContent = {
            if (!selectionMode) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        clip.front?.let { f ->
                            DropdownMenuItem(text = { Text("播放前鏡頭") }, onClick = { menu = false; onPlay(f) })
                        }
                        clip.rear?.let { r ->
                            DropdownMenuItem(text = { Text("播放後鏡頭") }, onClick = { menu = false; onPlay(r) })
                        }
                        if (clip.front != null && clip.rear != null) {
                            downloadOptions(includeRearDefault).forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice.label) },
                                    onClick = { menu = false; onDownload(choice) },
                                )
                            }
                        } else {
                            DropdownMenuItem(text = { Text("下載") }, onClick = { menu = false; onDownload(DownloadChoice.Both) })
                        }
                        DropdownMenuItem(
                            text = { Text("從記錄器刪除…", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun FileBadge(label: String, file: DashcamFile, downloaded: Boolean) {
    val scheme = MaterialTheme.colorScheme
    MediaBadge(
        label = label + if (file.guessed) "" else " ${Formats.size(file.size)}",
        container = if (downloaded) scheme.primaryContainer else scheme.surfaceVariant,
        content = if (downloaded) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        downloaded = downloaded,
    )
}

@Composable
private fun DeleteDialog(clips: List<Clip>, onDismiss: () -> Unit, onConfirm: (includeRear: Boolean) -> Unit) {
    var includeRear by remember { mutableStateOf(true) }
    val hasRear = clips.any { it.rear != null && it.front != null }
    ConfirmDeleteDialog(
        title = "從記錄器刪除 ${clips.size} 段？",
        onDismiss = onDismiss,
        onConfirm = { onConfirm(includeRear) },
    ) {
        Column {
            Text("刪除後無法復原（手機上已下載的檔案不受影響）。")
            if (hasRear) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeRear, onCheckedChange = { includeRear = it })
                    Text("一併刪除後鏡頭檔")
                }
            }
        }
    }
}
