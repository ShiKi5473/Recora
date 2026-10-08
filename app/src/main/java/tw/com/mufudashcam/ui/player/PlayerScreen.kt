package tw.com.mufudashcam.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import tw.com.mufudashcam.data.PlayItem
import kotlin.math.abs
import androidx.compose.material.icons.filled.ContentCut
import android.widget.Toast
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.ui.common.captureFrame
import tw.com.mufudashcam.ui.common.isDeletable
import tw.com.mufudashcam.ui.common.isLocal
import tw.com.mufudashcam.ui.common.rememberMediaDeleter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import tw.com.mufudashcam.ui.common.FullscreenEffect

/**
 * 播放本機檔（content://）或直接從記錄器串流（http://，TS 檔靠 Range 快轉）。
 * TS 內有 H.265 主串流與 H.264 子串流，強制選最高碼率 = 主串流。
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    container: AppContainer,
    url: String,
    title: String,
    onBack: () -> Unit,
    onEdit: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    // 播放清單：來源頁面設定的清單（含目前這段），左右滑動切換；不在清單裡就只播這一段
    var queue by remember {
        mutableStateOf(container.playQueue.takeIf { q -> q.any { it.url == url } } ?: listOf(PlayItem(url, title)))
    }
    var index by remember { mutableIntStateOf(queue.indexOfFirst { it.url == url }.coerceAtLeast(0)) }
    val current = queue[index.coerceIn(queue.indices)]
    // 記錄器的 HTTP 伺服器一次只服務一個連線：串流時播放清單只放目前這段，
    // 不讓 ExoPlayer 在這段還沒播完就同時去預抓下一段（兩條連線＝被記錄器踢掉）
    val streaming = remember { queue.any { it.url.startsWith("http") } }
    val player = remember {
        dashcamPlayer(context, container.http, streaming).apply {
            val items = queue.map { MediaItem.Builder().setUri(it.url).setMediaId(it.url).build() }
            if (streaming) setMediaItem(items[index]) else setMediaItems(items, index, 0)
            // 每段播完停下，不自動跳到下一段
            pauseAtEndOfMediaItems = true
            prepare()
            playWhenReady = true
        }
    }
    fun mediaItem(item: PlayItem): MediaItem =
        MediaItem.Builder().setUri(item.url).setMediaId(item.url).build()

    fun jump(delta: Int) {
        val target = index + delta
        if (target !in queue.indices) {
            Toast.makeText(context, if (delta > 0) "已經是最後一段" else "已經是第一段", Toast.LENGTH_SHORT).show()
            return
        }
        if (streaming) {
            index = target
            error = null
            player.setMediaItem(mediaItem(queue[target]))
            player.prepare()
        } else {
            player.seekTo(target, 0)
        }
        player.play()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 串流時播放器只放目前這段，currentMediaItemIndex 永遠是 0，不能拿來同步清單位置
                if (!streaming) index = player.currentMediaItemIndex
                error = null
            }

            override fun onPlayerError(e: PlaybackException) {
                error = playbackErrorMessage(e, streaming = current.url.startsWith("http"))
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    // 全螢幕：轉橫向並隱藏狀態列 / 導覽列；離開畫面時還原
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    val screenshot: () -> Unit = {
        captureFrame(container, playerView, player, current.title, scope, toast)
    }

    /** 刪掉的那一段從清單移除，接著播下一段（已經是最後一段就播前一段，清單空了就離開）。 */
    fun dropFromQueue(item: PlayItem) {
        val removed = queue.indexOfFirst { it.url == item.url }
        if (removed < 0) return
        val rest = queue.filterIndexed { i, _ -> i != removed }
        if (rest.isEmpty()) {
            onBack()
            return
        }
        val target = removed.coerceAtMost(rest.lastIndex)
        queue = rest
        index = target
        error = null
        if (streaming) {
            player.setMediaItem(mediaItem(rest[target]))
        } else {
            player.removeMediaItem(removed)
            player.seekTo(target, 0)
        }
        player.prepare()
        player.play()
    }

    val deleteItem = rememberMediaDeleter(container, toast) { item, deleted ->
        if (deleted) {
            dropFromQueue(item)
        } else {
            // 沒刪成或使用者取消：恢復剛才停掉的播放
            player.prepare()
            player.play()
        }
    }
    var pendingDelete by remember { mutableStateOf<PlayItem?>(null) }

    FullscreenEffect(fullscreen) { fullscreen = false }

    Box(Modifier.fillMaxSize().background(Color.Black).swipeToJump(::jump)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setShowSubtitleButton(false)
                    // 設了監聽器，控制列才會出現全螢幕按鈕
                    setFullscreenButtonClickListener { fullscreen = it }
                    playerView = this
                }
            },
            update = { it.setFullscreenButtonState(fullscreen) },
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding(),
        )
        if (!fullscreen) {
            Row(
                Modifier.fillMaxWidth().safeDrawingPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(current.title, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    if (queue.size > 1) {
                        Text(
                            "${index + 1} / ${queue.size}　左右滑動切換",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                IconButton(onClick = screenshot) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "截圖", tint = Color.White)
                }
                // 只有手機上的檔案可以剪輯（串流中的記錄器檔案要先下載）
                if (current.url.startsWith("content://")) {
                    IconButton(onClick = { onEdit(current.url, current.title) }) {
                        Icon(Icons.Filled.ContentCut, contentDescription = "剪輯", tint = Color.White)
                    }
                }
                if (isDeletable(current)) {
                    IconButton(onClick = { pendingDelete = current }) {
                        Icon(Icons.Filled.Delete, contentDescription = "刪除", tint = Color.White)
                    }
                }
            }
        }
        if (fullscreen) {
            // 全螢幕時右上角也保留截圖
            IconButton(
                onClick = screenshot,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape),
            ) { Icon(Icons.Filled.CameraAlt, contentDescription = "截圖", tint = Color.White) }
        }
        pendingDelete?.let { item ->
            val local = isLocal(item)
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(if (local) "從手機相簿刪除？" else "從記錄器刪除？") },
                text = {
                    val detail = if (local) {
                        "會從手機相簿刪除，無法復原（記錄器上的原始檔不受影響）。"
                    } else {
                        "會從記錄器刪除，無法復原（手機上已下載的檔案不受影響）。"
                    }
                    Text("${item.title}\n$detail")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            // 記錄器的 HTTP 伺服器一次只服務一個連線：先停播放，del 的 CGI 才連得上。
                            // 本機檔也先停，免得檔案在播放中被刪掉而跳出播放錯誤。
                            player.stop()
                            deleteItem(item)
                        },
                    ) { Text("刪除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            )
        }
        error?.let {
            Text(
                it,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 64.dp)
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 左右滑動切換影片：在 Initial 階段觀察手勢（不攔截），播放器的點擊、控制列照常運作；
 * 從畫面下方 1/4（進度條所在）開始的拖曳不算，才不會跟拖曳進度條衝突。
 */
private fun Modifier.swipeToJump(onJump: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.y > size.height * 0.75f) return@awaitEachGesture
        var last = down.position
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            last = change.position
            if (!change.pressed) break
        }
        val dx = last.x - down.position.x
        val dy = last.y - down.position.y
        if (abs(dx) > 80.dp.toPx() && abs(dx) > 2 * abs(dy)) onJump(if (dx < 0) 1 else -1)
    }
}
