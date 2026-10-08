package tw.com.mufudashcam.ui.browser

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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.Camera
import tw.com.mufudashcam.data.Clip
import tw.com.mufudashcam.data.PlayItem
import tw.com.mufudashcam.data.DashcamFile
import tw.com.mufudashcam.data.Folder
import tw.com.mufudashcam.data.net.ConnState
import tw.com.mufudashcam.ui.Formats
import tw.com.mufudashcam.ui.common.ConnectionChip
import tw.com.mufudashcam.ui.common.MessagePane
import tw.com.mufudashcam.ui.common.PhotoDialog
import tw.com.mufudashcam.ui.common.NotConnectedPane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(container: AppContainer, onPlay: (url: String, title: String) -> Unit) {
    val vm: BrowserViewModel = viewModel(factory = BrowserViewModel.factory(container))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<List<Clip>?>(null) }
    var viewingPhoto by remember { mutableStateOf<DashcamFile?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val selectionMode = ui.selection.isNotEmpty()
    val play: (DashcamFile) -> Unit = { file ->
        if (file.isImage) {
            viewingPhoto = file
        } else {
            vm.resolvePlayback(file) { url ->
                // 左右滑動的清單：同資料夾、同鏡頭，順序同畫面；目前這段用剛解析出的網址（可能是已下載的本機檔）
                container.playQueue = ui.clips
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
                    onDownload = { rear -> vm.download(vm.selectedClips(), rear) },
                    onDelete = { pendingDelete = vm.selectedClips() },
                )
            } else {
                TopAppBar(
                    title = { Text("記錄器檔案") },
                    actions = {
                        if (conn is ConnState.Connected) {
                            IconButton(onClick = { vm.load(refresh = true) }, enabled = !ui.loading) {
                                Icon(Icons.Filled.Refresh, contentDescription = "重新整理")
                            }
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
            if (ui.loading || ui.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                conn !is ConnState.Connected -> NotConnectedPane(container)
                ui.error != null && ui.clips.isEmpty() -> MessagePane(Icons.Filled.FolderOff, ui.error!!) {
                    TextButton(onClick = { vm.load() }) { Text("重試") }
                }
                ui.clips.isEmpty() && !ui.loading -> MessagePane(Icons.Filled.FolderOff, "沒有檔案")
                else -> ClipList(
                    ui = ui,
                    selectionMode = selectionMode,
                    includeRearDefault = settings.includeRear,
                    thumbnailUrl = vm::thumbnailUrl,
                    onToggle = vm::toggle,
                    onSelectAll = vm::selectAll,
                    onPlay = play,
                    onDownload = { clip, rear -> vm.download(listOf(clip), rear) },
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
        val photos = ui.clips
            .mapNotNull { clip -> if (photo.camera == Camera.Rear) clip.rear else clip.front }
            .filter { it.isImage && !it.guessed }
        PhotoDialog(
            photos = photos.map { PlayItem(vm.thumbnailUrl(it), it.name) },
            startIndex = photos.indexOfFirst { it.sdPath == photo.sdPath },
            onDismiss = { viewingPhoto = null },
        )
    }
}

/** 下載選項的順序：設定的預設值排第一。 */
private fun downloadOptions(includeRearDefault: Boolean) =
    if (includeRearDefault) listOf(true, false) else listOf(false, true)

private fun downloadLabel(includeRear: Boolean) = if (includeRear) "下載前+後鏡頭" else "只下載前鏡頭"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    includeRearDefault: Boolean,
    onClose: () -> Unit,
    onDownload: (includeRear: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("已選 $count 段") },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "取消選取") }
        },
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Download, contentDescription = "下載") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    downloadOptions(includeRearDefault).forEach { rear ->
                        DropdownMenuItem(text = { Text(downloadLabel(rear)) }, onClick = { menu = false; onDownload(rear) })
                    }
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "刪除") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipList(
    ui: BrowserUi,
    selectionMode: Boolean,
    includeRearDefault: Boolean,
    thumbnailUrl: (DashcamFile) -> String,
    onToggle: (String) -> Unit,
    onSelectAll: (Collection<String>) -> Unit,
    onPlay: (DashcamFile) -> Unit,
    onDownload: (Clip, Boolean) -> Unit,
    onDelete: (Clip) -> Unit,
) {
    val byDate = remember(ui.clips) { ui.clips.groupBy { it.date } }
    LazyColumn(Modifier.fillMaxSize()) {
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
                    onDownload = { rear -> onDownload(clip, rear) },
                    onDelete = { onDelete(clip) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DateHeader(title: String, onSelectAll: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = "全選這一天")
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
    onDownload: (includeRear: Boolean) -> Unit,
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
                clip.primary.isImage -> AsyncImage(
                    model = thumbnailUrl(clip.primary),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                else -> Icon(Icons.Filled.Videocam, contentDescription = null)
            }
        },
        headlineContent = { Text(Formats.time(clip.dateTime)) },
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
                            downloadOptions(includeRearDefault).forEach { rear ->
                                DropdownMenuItem(
                                    text = { Text(downloadLabel(rear)) },
                                    onClick = { menu = false; onDownload(rear) },
                                )
                            }
                        } else {
                            DropdownMenuItem(text = { Text("下載") }, onClick = { menu = false; onDownload(true) })
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
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (downloaded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sizeText = if (file.guessed) "" else " ${Formats.size(file.size)}"
            Text("$label$sizeText", style = MaterialTheme.typography.labelSmall)
            if (downloaded) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "已下載",
                    modifier = Modifier.padding(start = 3.dp).size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DeleteDialog(clips: List<Clip>, onDismiss: () -> Unit, onConfirm: (includeRear: Boolean) -> Unit) {
    var includeRear by remember { mutableStateOf(true) }
    val hasRear = clips.any { it.rear != null && it.front != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("從記錄器刪除 ${clips.size} 段？") },
        text = {
            Column {
                Text("刪除後無法復原（手機上已下載的檔案不受影響）。")
                if (hasRear) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeRear, onCheckedChange = { includeRear = it })
                        Text("一併刪除後鏡頭檔")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(includeRear) }) {
                Text("刪除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
