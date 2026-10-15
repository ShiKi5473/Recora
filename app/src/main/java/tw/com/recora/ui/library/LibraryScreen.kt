package tw.com.recora.ui.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import tw.com.recora.ui.common.MediaBadge
import tw.com.recora.ui.common.SelectionTopBar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import tw.com.recora.AppContainer
import tw.com.recora.data.Brand
import tw.com.recora.data.Camera
import tw.com.recora.data.LocalLibrary
import tw.com.recora.data.LocalMedia
import tw.com.recora.data.MediaKind
import tw.com.recora.data.PlayItem
import tw.com.recora.download.MediaStoreSink
import tw.com.recora.ui.Formats
import tw.com.recora.ui.common.DateGroups
import tw.com.recora.ui.common.DateJumpButton
import tw.com.recora.ui.common.MediaThumb
import tw.com.recora.ui.common.MessagePane
import tw.com.recora.ui.common.PhotoDialog
import tw.com.recora.ui.common.shareMedia
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val EDIT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")

/**
 * 相簿：列 Movies/Recora、Pictures/Recora（相容舊版的 Dashcam、MufuDashcam）與「相簿資料夾」勾選的資料夾（預設含 Astro App 的 Movies/astroFamily），分成未剪輯（依拍攝時間）、
 * 已剪輯（依剪輯時間）、照片，最新的在最上面並依日期分組；可切換列表／格狀。
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
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var viewingPhoto by remember { mutableStateOf<LocalMedia?>(null) }
    var pendingDelete by remember { mutableStateOf<List<LocalMedia>?>(null) }
    var showCleanup by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var confirmClearLeftovers by remember { mutableStateOf(false) }
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
        // 相簿會收到並重新整理，「檔案」頁的已下載打勾也跟著更新
        container.libraryChanged.tryEmit(Unit)
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
    val byDate = remember(items) { items.groupBy { it.time.toLocalDate() } }

    fun setGrid(on: Boolean) {
        container.appScope.launch { container.settings.update { it.copy(libraryGrid = on) } }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selectionMode) {
                val selected = ui.selectedItems
                SelectionTopBar(
                    title = "已選 ${selected.size} 個 · ${Formats.size(selected.sumOf { it.sizeBytes })}",
                    onClose = vm::clearSelection,
                ) {
                    // 選了頭尾兩個後，把中間的全部選起來
                    if (ui.selection.size >= 2) {
                        TextButton(onClick = { vm.selectRange(items.map { it.uri }) }) { Text("選取範圍") }
                    }
                    IconButton(onClick = { vm.selectAll(items.map { it.uri }) }) {
                        Icon(Icons.Filled.SelectAll, contentDescription = "全選")
                    }
                    IconButton(onClick = { share(context, ui.selectedItems) }) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
                    }
                    IconButton(onClick = { pendingDelete = ui.selectedItems }) {
                        Icon(Icons.Filled.Delete, contentDescription = "刪除")
                    }
                }
            } else {
                TopAppBar(
                    title = { Text("相簿") },
                    actions = {
                        DateJumpButton(byDate.map { (date, group) -> Formats.date(date) to group.size }) { group ->
                            val index = DateGroups.headerIndex(byDate.values.map { it.size }, group)
                            scope.launch {
                                if (settings.libraryGrid) gridState.animateScrollToItem(index) else listState.animateScrollToItem(index)
                            }
                        }
                        IconButton(onClick = { setGrid(!settings.libraryGrid) }) {
                            if (settings.libraryGrid) {
                                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "列表顯示")
                            } else {
                                Icon(Icons.Filled.GridView, contentDescription = "格狀顯示")
                            }
                        }
                        IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "重新整理") }
                        Box {
                            var menu by remember { mutableStateOf(false) }
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("相簿資料夾…") },
                                    leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        showFolders = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("整理空間…") },
                                    leadingIcon = { Icon(Icons.Filled.CleaningServices, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        vm.loadLeftovers()
                                        showCleanup = true
                                    },
                                )
                            }
                        }
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
            // 記錄器種類、鏡頭篩選（左右滑動切換也只在篩選後的清單裡）
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (listOf(null) + Brand.entries).forEach { brand ->
                    FilterChip(
                        selected = ui.brandFilter == brand,
                        onClick = { vm.setBrandFilter(brand) },
                        label = { Text(brand?.label ?: "全部") },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
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
                        MediaKind.Original -> "到「檔案」頁選取影片下載，會存到 Movies/${settings.albumDir}"
                        MediaKind.Edited -> "在「未剪輯」選一段影片按「剪輯」，匯出的 MP4 會出現在這裡"
                        MediaKind.Photo -> "記錄器的照片與剪輯時的截圖會存到 Pictures/${settings.albumDir}"
                    },
                )
            } else {
                val onOpen: (LocalMedia) -> Unit = { item ->
                    when {
                        selectionMode -> vm.toggle(item.uri)
                        item.kind == MediaKind.Photo -> viewingPhoto = item
                        else -> {
                            container.playQueue = items.map { PlayItem(it.uri, it.name) }
                            onPlay(item.uri, item.name)
                        }
                    }
                }
                if (settings.libraryGrid) {
                    MediaGrid(
                        byDate = byDate,
                        state = gridState,
                        selection = ui.selection,
                        onOpen = onOpen,
                        onToggle = { vm.toggle(it.uri) },
                        onSelectAll = { group -> vm.selectAll(group.map { it.uri }) },
                    )
                } else {
                    MediaList(
                        byDate = byDate,
                        state = listState,
                        selection = ui.selection,
                        onOpen = onOpen,
                        onToggle = { vm.toggle(it.uri) },
                        onSelectAll = { group -> vm.selectAll(group.map { it.uri }) },
                        onEdit = { onEdit(it.uri, it.name) },
                        onDelete = { pendingDelete = listOf(it) },
                    )
                }
            }
        }
    }

    viewingPhoto?.let { photo ->
        val photos = ui.of(MediaKind.Photo)
        PhotoDialog(
            container = container,
            photos = photos.map { PlayItem(it.uri, it.name) },
            startIndex = photos.indexOfFirst { it.uri == photo.uri },
            onDismiss = { viewingPhoto = null },
        )
    }
    if (showFolders) LibraryFoldersDialog(container, onDismiss = { showFolders = false })
    if (showCleanup) {
        CleanupDialog(
            items = ui.items,
            leftovers = ui.leftovers,
            onDelete = { targets ->
                showCleanup = false
                pendingDelete = targets
            },
            onClearLeftovers = {
                showCleanup = false
                confirmClearLeftovers = true
            },
            onDismiss = { showCleanup = false },
        )
    }
    if (confirmClearLeftovers) {
        val leftovers = ui.leftovers.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmClearLeftovers = false },
            title = { Text("清除 ${leftovers.size} 個未完成的下載？") },
            text = { Text("之後再下載同一個檔案，要從頭開始下載。") },
            confirmButton = {
                TextButton(onClick = { confirmClearLeftovers = false; vm.clearLeftovers(leftovers) }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClearLeftovers = false }) { Text("取消") } },
        )
    }
    pendingDelete?.let { targets ->
        ConfirmDeleteDialog(
            title = if (targets.size == 1) "刪除這個檔案？" else "刪除 ${targets.size} 個檔案？",
            onDismiss = { pendingDelete = null },
            onConfirm = { pendingDelete = null; vm.delete(targets) },
        ) {
            val what = if (targets.size == 1) targets[0].name + "\n" else ""
            Text("${what}會從手機相簿刪除，無法復原（記錄器上的原始檔不受影響）。")
        }
    }
}

/**
 * 整理空間：各類佔用多少，以及三種可以清掉的檔案（已剪輯過的原始檔、幾天前錄的原始檔、沒下載完的暫存檔）。
 * 選了之後交給呼叫端確認再刪。
 */
@Composable
private fun CleanupDialog(
    items: List<LocalMedia>,
    leftovers: List<MediaStoreSink.Entry>?,
    onDelete: (List<LocalMedia>) -> Unit,
    onClearLeftovers: () -> Unit,
    onDismiss: () -> Unit,
) {
    var days by remember { mutableIntStateOf(30) }
    val edited = remember(items) { LocalLibrary.editedOriginals(items) }
    val old = remember(items, days) { LocalLibrary.originalsRecordedBefore(items, LocalDateTime.now().minusDays(days.toLong())) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("整理空間") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaKind.entries.forEach { kind ->
                    val list = items.filter { it.kind == kind }
                    Text("${kind.label}：${list.size} 個 · ${Formats.size(list.sumOf { it.sizeBytes })}")
                }
                HorizontalDivider()
                CleanupRow("已剪輯過的原始檔", "匯出剪輯後，原始檔通常就用不到了", edited.size, edited.sumOf { it.sizeBytes }) {
                    onDelete(edited)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30, 90).forEach { d ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d 天") })
                    }
                }
                CleanupRow("$days 天前錄的原始檔", "只算檔名看得出是記錄器錄的", old.size, old.sumOf { it.sizeBytes }) { onDelete(old) }
                CleanupRow(
                    "未完成的下載暫存檔",
                    "取消或中斷的下載留下的檔案（下載佇列裡還在等的不算）",
                    leftovers?.size ?: 0,
                    leftovers.orEmpty().sumOf { it.size },
                    loading = leftovers == null,
                    onClick = onClearLeftovers,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}

@Composable
private fun CleanupRow(title: String, hint: String?, count: Int, bytes: Long, loading: Boolean = false, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                when {
                    loading -> "計算中…"
                    count == 0 -> "沒有"
                    else -> "$count 個 · ${Formats.size(bytes)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onClick, enabled = count > 0) { Text("刪除") }
    }
}

/** 用系統分享選單一次分享多個檔案。 */
private fun share(context: Context, items: List<LocalMedia>) {
    val type = when {
        items.all { it.kind == MediaKind.Photo } -> "image/*"
        items.none { it.kind == MediaKind.Photo } -> "video/*"
        else -> "*/*"
    }
    shareMedia(context, items.map { Uri.parse(it.uri) }, type)
}

/** 縮圖：用系統的縮圖快取（見 [MediaThumb]）。 */
private fun thumbnail(item: LocalMedia) = MediaThumb(item.uri, isVideo = item.kind != MediaKind.Photo)

/** 拍攝時間（剪輯檔是剪輯時間）。 */
private fun timeLabel(item: LocalMedia): String = when (item.kind) {
    MediaKind.Edited -> "剪輯 ${item.time.format(EDIT_TIME)}"
    else -> "拍攝 ${Formats.time(item.time)}"
}

@Composable
private fun DateHeader(date: LocalDate?, count: Int, onSelectAll: () -> Unit) =
    tw.com.recora.ui.common.DateHeader("${Formats.date(date)} · $count 個", onSelectAll)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaList(
    byDate: Map<LocalDate, List<LocalMedia>>,
    state: LazyListState,
    selection: Set<String>,
    onOpen: (LocalMedia) -> Unit,
    onToggle: (LocalMedia) -> Unit,
    onSelectAll: (List<LocalMedia>) -> Unit,
    onEdit: (LocalMedia) -> Unit,
    onDelete: (LocalMedia) -> Unit,
) {
    val selectionMode = selection.isNotEmpty()
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        byDate.forEach { (date, group) ->
            stickyHeader(key = "h-$date") { DateHeader(date, group.size) { onSelectAll(group) } }
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

/** 格狀：縮圖較大，日期標題佔一整列；長按多選、點擊開啟同列表。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    byDate: Map<LocalDate, List<LocalMedia>>,
    state: LazyGridState,
    selection: Set<String>,
    onOpen: (LocalMedia) -> Unit,
    onToggle: (LocalMedia) -> Unit,
    onSelectAll: (List<LocalMedia>) -> Unit,
) {
    val selectionMode = selection.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        state = state,
        contentPadding = PaddingValues(bottom = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        byDate.forEach { (date, group) ->
            item(key = "h-$date", span = { GridItemSpan(maxLineSpan) }) {
                DateHeader(date, group.size) { onSelectAll(group) }
            }
            items(group.size, key = { group[it].uri }) { i ->
                val item = group[i]
                MediaCell(
                    item = item,
                    selectionMode = selectionMode,
                    selected = item.uri in selection,
                    onOpen = { onOpen(item) },
                    onLongClick = { onToggle(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    item: LocalMedia,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(Modifier.padding(2.dp).combinedClickable(onClick = onOpen, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier,
                ),
        ) {
            AsyncImage(
                model = thumbnail(item),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            item.camera?.let { cam -> CameraBadge(cam, Modifier.align(Alignment.TopEnd).padding(4.dp)) }
            if (item.durationMs > 0) {
                Text(
                    Formats.duration(item.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (selectionMode) SelectionMark(selected, Modifier.align(Alignment.TopStart))
        }
        Text(
            if (item.kind == MediaKind.Edited) timeLabel(item) else Formats.time(item.time),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        )
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
                    model = thumbnail(item),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 88.dp, height = 50.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                // 選取模式：縮圖左上角打勾，仍看得到畫面方便挑選
                if (selectionMode) SelectionMark(selected, Modifier.align(Alignment.TopStart))
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.camera?.let { cam -> CameraBadge(cam, Modifier.padding(end = 6.dp)) }
                Text(item.name, maxLines = 1)
            }
        },
        supportingContent = {
            Column {
                val details = buildList {
                    add(timeLabel(item))
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

/** 選取模式時縮圖左上角的勾，仍看得到畫面方便挑選。 */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier) {
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = if (selected) "已選取" else "未選取",
        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
        modifier = modifier.padding(3.dp).size(20.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape),
    )
}

/** 「前」／「後」小標籤：前鏡頭用主色、後鏡頭用第三色，一眼就能分辨。 */
@Composable
private fun CameraBadge(camera: Camera, modifier: Modifier = Modifier) {
    val front = camera == Camera.Front
    val scheme = MaterialTheme.colorScheme
    MediaBadge(
        label = if (front) "前" else "後",
        modifier = modifier,
        container = if (front) scheme.primaryContainer else scheme.tertiaryContainer,
        content = if (front) scheme.onPrimaryContainer else scheme.onTertiaryContainer,
    )
}
