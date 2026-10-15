package tw.com.recora.ui.common

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import tw.com.recora.AppContainer
import tw.com.recora.data.PlayItem
import tw.com.recora.data.api.Lease

/**
 * 全螢幕看照片（記錄器上的網址或手機上的 content://），左右滑動切換同一清單的其他照片。
 * 手機上的照片可以分享、刪除；記錄器上的照片（有 SD 卡路徑）可以下載、刪除。
 */
@Composable
fun PhotoDialog(container: AppContainer, photos: List<PlayItem>, startIndex: Int, onDismiss: () -> Unit) {
    if (photos.isEmpty()) return
    val context = LocalContext.current
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    var items by remember { mutableStateOf(photos) }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(photos.indices)) { items.size }
    // 看記錄器上的照片時維持檔案模式（Astro 要停錄；MUFU 沒有動作）
    val fromDashcam = photos.any { it.sdPath != null }
    DisposableEffect(fromDashcam) {
        val lease = if (fromDashcam) container.api.holdFiles("看照片") else Lease.NONE
        onDispose { lease.close() }
    }
    var pendingDelete by remember { mutableStateOf<PlayItem?>(null) }
    val deleteItem = rememberMediaDeleter(container, toast) { item, deleted ->
        if (deleted) {
            items = items.filterNot { it.url == item.url }
            if (items.isEmpty()) onDismiss()
        }
    }

    fun download(photo: PlayItem) {
        val file = photo.sdPath?.let(container.files::find)
        if (file == null) {
            toast("請回到「檔案」頁下載")
            return
        }
        toast(if (container.downloads.enqueue(listOf(file)) > 0) "已加入下載佇列" else "這張照片已在下載佇列中")
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogLightSystemBarIcons()
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, key = { items[it].url }, modifier = Modifier.fillMaxSize()) { page ->
                val zoom = remember { ZoomState() }
                // 換到別張後，這張回到原本大小
                LaunchedEffect(pager.settledPage) { if (pager.settledPage != page) zoom.reset() }
                AsyncImage(
                    model = items[page].url,
                    contentDescription = items[page].title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .zoomGestures(zoom)
                        .pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom.toggle(it) }) }
                        .graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                        },
                )
            }
            val photo = items.getOrNull(pager.currentPage)
            OverlayTopBar(Modifier.align(Alignment.TopStart)) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        photo?.title.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (items.size > 1) {
                        Text(
                            "${pager.currentPage + 1} / ${items.size}　左右滑動切換・雙指或點兩下放大",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (photo != null) {
                    if (isLocal(photo)) {
                        IconButton(onClick = { shareMedia(context, listOf(Uri.parse(photo.url)), "image/*") }) {
                            Icon(Icons.Filled.Share, contentDescription = "分享", tint = Color.White)
                        }
                    } else if (photo.sdPath != null) {
                        IconButton(onClick = { download(photo) }) {
                            Icon(Icons.Filled.Download, contentDescription = "下載", tint = Color.White)
                        }
                    }
                    if (isDeletable(photo)) {
                        IconButton(onClick = { pendingDelete = photo }) {
                            Icon(Icons.Filled.Delete, contentDescription = "刪除", tint = Color.White)
                        }
                    }
                }
            }
        }
        pendingDelete?.let { item ->
            val local = isLocal(item)
            ConfirmDeleteDialog(
                title = if (local) "從手機相簿刪除？" else "從記錄器刪除？",
                onDismiss = { pendingDelete = null },
                onConfirm = { pendingDelete = null; deleteItem(item) },
            ) {
                val detail = if (local) {
                    "會從手機相簿刪除，無法復原（記錄器上的原始檔不受影響）。"
                } else {
                    "會從記錄器刪除，無法復原（手機上已下載的照片不受影響）。"
                }
                Text("${item.title}\n$detail")
            }
        }
    }
}
