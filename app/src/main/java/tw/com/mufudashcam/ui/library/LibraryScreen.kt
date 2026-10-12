package tw.com.mufudashcam.ui.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.Camera
import tw.com.mufudashcam.data.DashcamConfig
import tw.com.mufudashcam.data.LocalMedia
import tw.com.mufudashcam.data.MediaKind
import tw.com.mufudashcam.data.PlayItem
import tw.com.mufudashcam.ui.Formats
import tw.com.mufudashcam.ui.common.MessagePane
import tw.com.mufudashcam.ui.common.PhotoDialog
import java.time.format.DateTimeFormatter

private val EDIT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")

/**
 * 相簿：只列 Movies/MufuDashcam 與 Pictures/MufuDashcam，分成未剪輯（依拍攝時間）、
 * 已剪輯（依剪輯時間）、照片，最新的在最上面並依日期分組。
 * 長按進入多選，可整批刪除或分享。
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onPlay: (uri: String, title: String) -> Unit,
    onEdit: (uri: String, title: String) -> Unit,
) {
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var viewingPhoto by remember { mutableStateOf<LocalMedia?>(null) }
    var pendingDelete by remember { mutableStateOf<List<LocalMedia>?>(null) }
    val selectionMode = ui.selection.isNotEmpty()

    // 讀取權限：沒有的話只看得到本 App 建立的檔案
    fun fullAccess() = listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var hasFullAccess by remember { mutableStateOf(fullAccess()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasFullAccess = fullAccess()
        vm.refresh()
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.refresh()
    }
    LaunchedEffect(Unit) {
        if (!hasFullAccess) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES))
        }
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) {
        vm.deleteRequests.collect { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    // 回到這個分頁時重新整理（例如在別的 App 刪了檔案）
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    BackHandler(enabled = selectionMode, onBack = vm::clearSelection)

    val items = ui.of(ui.tab)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("已選 ${ui.selection.size} 個") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, contentDescription = "取消選取") }
                    },
                    actions = {
                        IconButton(onClick = { vm.selectAll(items.map { it.uri }) }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "全選")
                        }
                        IconButton(onClick = { share(context, ui.selectedItems) }) {
                            Icon(Icons.Filled.Share, contentDescription = "分享")
                        }
                        IconButton(onClick = { pendingDelete = ui.selectedItems }) {
                            Icon(Icons.Filled.Delete, contentDescription = "刪除")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("相簿") },
                    actions = {
                        IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "重新整理") }
                    },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = ui.tab.ordinal) {
                MediaKind.entries.forEach { kind ->
                    Tab(
                        selected = ui.tab == kind,
                        onClick = { vm.selectTab(kind) },
                        text = { Text("${kind.label}（${ui.of(kind).size}）") },
                    )
                }
            }
            // 鏡頭篩選（左右滑動切換也只在篩選後的清單裡）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(null to "全部", Camera.Front to "前鏡頭", Camera.Rear to "後鏡頭").forEach { (cam, label) ->
                    FilterChip(
                        selected = ui.cameraFilter == cam,
                        onClick = { vm.setCameraFilter(cam) },
                        label = { Text(label) },
                    )
                }
            }
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!hasFullAccess) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp)) {
                        Text(
                            "未允許存取相片和影片：只顯示本 App 建立的檔案",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        )
                        TextButton(onClick = {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES),
                            )
                        }) { Text("允許") }
                    }
                }
            }
            if (items.isEmpty() && !ui.loading) {
                MessagePane(
                    icon = Icons.Filled.PhotoLibrary,
                    title = when (ui.tab) {
                        MediaKind.Original -> "還沒有下載的影片"
                        MediaKind.Edited -> "還沒有剪輯過的影片"
                        MediaKind.Photo -> "沒有照片"
                    },
                    text = when (ui.tab) {
                        MediaKind.Original -> "到「檔案」頁選取影片下載，會存到 Movies/${DashcamConfig.LOCAL_DIR}"
                        MediaKind.Edited -> "在「未剪輯」選一段影片按「剪輯」，匯出的 MP4 會出現在這裡"
                        MediaKind.Photo -> "記錄器的照片與剪輯時的截圖會存到 Pictures/${DashcamConfig.LOCAL_DIR}"
                    },
                )
            } else {
                MediaList(
                    items = items,
                    selection = ui.selection,
                    onOpen = { item ->
                        when {
                            selectionMode -> vm.toggle(item.uri)
                            item.kind == MediaKind.Photo -> viewingPhoto = item
                            else -> {
                                container.playQueue = items.map { PlayItem(it.uri, it.name) }
                                onPlay(item.uri, item.name)
                            }
                        }
                    },
                    onToggle = { vm.toggle(it.uri) },
                    onSelectAll = { group -> vm.selectAll(group.map { it.uri }) },
                    onEdit = { onEdit(it.uri, it.name) },
                    onDelete = { pendingDelete = listOf(it) },
                )
            }
        }
    }

    viewingPhoto?.let { photo ->
        val photos = ui.of(MediaKind.Photo)
        PhotoDialog(
            photos = photos.map { PlayItem(it.uri, it.name) },
            startIndex = photos.indexOfFirst { it.uri == photo.uri },
            onDismiss = { viewingPhoto = null },
        )
    }
    pendingDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (targets.size == 1) "刪除這個檔案？" else "刪除 ${targets.size} 個檔案？") },
            text = {
                val what = if (targets.size == 1) targets[0].name + "\n" else ""
                Text("${what}會從手機相簿刪除，無法復原（記錄器上的原始檔不受影響）。")
            },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; vm.delete(targets) }) {
                    Text("刪除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

/** 用系統分享選單一次分享多個檔案。 */
private fun share(context: Context, items: List<LocalMedia>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { Uri.parse(it.uri) })
    val type = when {
        items.all { it.kind == MediaKind.Photo } -> "image/*"
        items.none { it.kind == MediaKind.Photo } -> "video/*"
        else -> "*/*"
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "分享 ${items.size} 個檔案"))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaList(
    items: List<LocalMedia>,
    selection: Set<String>,
    onOpen: (LocalMedia) -> Unit,
    onToggle: (LocalMedia) -> Unit,
    onSelectAll: (List<LocalMedia>) -> Unit,
    onEdit: (LocalMedia) -> Unit,
    onDelete: (LocalMedia) -> Unit,
) {
    val selectionMode = selection.isNotEmpty()
    val byDate = remember(items) { items.groupBy { it.time.toLocalDate() } }
    LazyColumn(Modifier.fillMaxSize()) {
        byDate.forEach { (date, group) ->
            stickyHeader(key = "h-$date") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${Formats.date(date)} · ${group.size} 個",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onSelectAll(group) }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "全選這一天")
                        }
                    }
                }
            }
            items(group, key = { it.uri }) { item ->
                MediaRow(
                    item = item,
                    selectionMode = selectionMode,
                    selected = item.uri in selection,
                    onOpen = { onOpen(item) },
                    onLongClick = { onToggle(item) },
                    onEdit = { onEdit(item) },
                    onDelete = { onDelete(item) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    item: LocalMedia,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongClick),
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            Box {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.uri)
                        .apply { if (item.kind != MediaKind.Photo) videoFrameMillis(1000) }
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 88.dp, height = 50.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                // 選取模式：縮圖左上角打勾，仍看得到畫面方便挑選
                if (selectionMode) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = if (selected) "已選取" else "未選取",
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.TopStart).padding(3.dp).size(20.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                    )
                }
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.camera?.let { cam -> CameraBadge(cam) }
                Text(item.name, maxLines = 1)
            }
        },
        supportingContent = {
            Column {
                val time = when (item.kind) {
                    MediaKind.Edited -> "剪輯 ${item.time.format(EDIT_TIME)}"
                    else -> "拍攝 ${Formats.time(item.time)}"
                }
                val details = buildList {
                    add(time)
                    if (item.durationMs > 0) add(Formats.duration(item.durationMs))
                    add(Formats.size(item.sizeBytes))
                }
                Text(details.joinToString(" · "))
                if (item.kind == MediaKind.Edited && item.source != null) {
                    val range = item.rangeMs?.let {
                        " · ${Formats.duration(it.first, tenths = true)}–${Formats.duration(it.last, tenths = true)}"
                    }.orEmpty()
                    Text(
                        "來源 ${item.source}$range${if (item.muted) " · 靜音" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            if (!selectionMode) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (item.kind != MediaKind.Photo) {
                            DropdownMenuItem(
                                text = { Text(if (item.kind == MediaKind.Edited) "再次剪輯" else "剪輯") },
                                leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                                onClick = { menu = false; onEdit() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("選取") },
                            onClick = { menu = false; onLongClick() },
                        )
                        DropdownMenuItem(
                            text = { Text("刪除…", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        },
    )
}

/** 「前」／「後」小標籤：前鏡頭用主色、後鏡頭用第三色，一眼就能分辨。 */
@Composable
private fun CameraBadge(camera: Camera) {
    val front = camera == Camera.Front
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (front) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.padding(end = 6.dp),
    ) {
        Text(
            if (front) "前" else "後",
            style = MaterialTheme.typography.labelSmall,
            color = if (front) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
