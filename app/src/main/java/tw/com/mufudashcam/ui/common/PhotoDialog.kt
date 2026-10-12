package tw.com.mufudashcam.ui.common

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import tw.com.mufudashcam.data.PlayItem

/**
 * 全螢幕看照片（記錄器上的網址或手機上的 content://），左右滑動切換同一清單的其他照片。
 */
@Composable
fun PhotoDialog(photos: List<PlayItem>, startIndex: Int, onDismiss: () -> Unit) {
    if (photos.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(photos.indices)) { photos.size }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val zoom = remember { ZoomState() }
                // 換到別張後，這張回到原本大小
                LaunchedEffect(pager.settledPage) { if (pager.settledPage != page) zoom.reset() }
                AsyncImage(
                    model = photos[page].url,
                    contentDescription = photos[page].title,
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
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart).safeDrawingPadding().padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.4f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Column {
                    Text(photos[pager.currentPage].title, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    if (photos.size > 1) {
                        Text(
                            "${pager.currentPage + 1} / ${photos.size}　左右滑動切換・雙指或點兩下放大",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
