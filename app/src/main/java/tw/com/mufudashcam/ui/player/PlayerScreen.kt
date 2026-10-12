package tw.com.mufudashcam.ui.player

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.R
import tw.com.mufudashcam.data.Camera
import tw.com.mufudashcam.data.FileNames
import tw.com.mufudashcam.data.LocalLibrary
import tw.com.mufudashcam.data.PlayItem
import tw.com.mufudashcam.data.PlayOrder
import tw.com.mufudashcam.ui.Formats
import tw.com.mufudashcam.ui.common.FullscreenEffect
import tw.com.mufudashcam.ui.common.ZoomState
import tw.com.mufudashcam.ui.common.captureFrame
import tw.com.mufudashcam.ui.common.isDeletable
import tw.com.mufudashcam.ui.common.isLocal
import tw.com.mufudashcam.ui.common.rememberMediaDeleter
import tw.com.mufudashcam.ui.common.shareMedia
import tw.com.mufudashcam.ui.common.zoomGestures
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 播放本機檔（content://）或直接從記錄器串流（http://，TS 檔靠 Range 快轉）。
 * TS 內有 H.265 主串流與 H.264 子串流，強制選最高碼率 = 主串流。
 *
 * 播放器一次只放目前這一段（記錄器一次只服務一個連線，不能讓 ExoPlayer 預抓下一段），
 * 切換時由這裡決定實際要播的檔案：另一個鏡頭的同一段、手機上已下載的檔案、或錄影時間的下一段。
 */
@OptIn(UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    container: AppContainer,
    url: String,
    title: String,
    onBack: () -> Unit,
    onEdit: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val toast: (String) -> Unit = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    var error by remember { mutableStateOf<String?>(null) }
    // 播放清單：來源頁面設定的清單（含目前這段），左右滑動切換；不在清單裡就只播這一段
    var queue by remember {
        mutableStateOf(container.playQueue.takeIf { q -> q.any { it.url == url } } ?: listOf(PlayItem(url, title)))
    }
    var index by remember { mutableIntStateOf(queue.indexOfFirst { it.url == url }.coerceAtLeast(0)) }
    /** 正在播的檔案：清單裡的那一段，或換成了另一個鏡頭、手機上的檔案。 */
    var current by remember { mutableStateOf(queue[index]) }
    /** 切到了另一個鏡頭：之後左右切換也盡量播另一個鏡頭。 */
    var otherCamera by remember { mutableStateOf(false) }
    // 從記錄器串流的清單用較小的 TS 搜尋範圍（見 dashcamMediaSourceFactory）
    val streaming = remember { queue.any { it.url.startsWith("http") } }
    val player = remember {
        dashcamPlayer(context, container.http, streaming).apply {
            setMediaItem(mediaItem(current))
            // 每段播完停下；要不要接著播下一段由 onEnded 決定
            pauseAtEndOfMediaItems = true
            prepare()
            playWhenReady = true
        }
    }
    var isPlaying by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var endHandled by remember { mutableStateOf(false) }

    /** 手機上已下載完成的同名影片（content://），沒有回 null。 */
    suspend fun localCopy(name: String): String? = withContext(Dispatchers.IO) {
        runCatching { container.sink.find(name, isImage = false) }.getOrNull()?.takeIf { !it.pending }?.uri?.toString()
    }

    /** 另一個鏡頭的同一段：手機上有就播手機上的，否則從記錄器串流（不先探測，沒有這個檔時播放會失敗並提示）。 */
    suspend fun counterpart(item: PlayItem): PlayItem? {
        val name = FileNames.counterpartName(item.title) ?: return null
        val sdPath = item.sdPath?.let(FileNames::counterpartPath)
        localCopy(name)?.let { return PlayItem(it, name, sdPath) }
        return sdPath?.let { PlayItem(container.api.httpUrl(it), name, it) }
    }

    /** 記錄器上的檔案已經下載到手機的話改播手機上的：不占用記錄器的連線，也能剪輯。 */
    suspend fun preferLocal(item: PlayItem): PlayItem =
        if (isLocal(item)) item else localCopy(item.title)?.let { item.copy(url = it) } ?: item

    fun play(item: PlayItem, positionMs: Long = 0, autoPlay: Boolean = true) {
        current = item
        error = null
        endHandled = false
        player.setMediaItem(mediaItem(item), positionMs)
        player.prepare()
        player.playWhenReady = autoPlay
    }

    /** 播清單裡的第 [target] 段（切到另一鏡頭時盡量播另一鏡頭；手機上有就播手機上的）。 */
    fun playAt(target: Int) {
        index = target
        val base = queue[target]
        loadJob?.cancel()
        loadJob = scope.launch {
            val item = (if (otherCamera) counterpart(base) else null) ?: base
            play(preferLocal(item))
        }
    }

    fun jump(delta: Int) {
        val target = index + delta
        if (target !in queue.indices) {
            toast(if (delta > 0) "已經是最後一段" else "已經是第一段")
            return
        }
        playAt(target)
    }

    /** 換到另一個鏡頭的同一段，停在同一個時間點（暫停中就維持暫停，方便逐格對照前後鏡頭）。 */
    fun switchCamera() {
        val base = queue[index]
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        val toOther = current.title == base.title
        loadJob?.cancel()
        loadJob = scope.launch {
            val item = if (toOther) counterpart(base) else base
            if (item == null) {
                toast("找不到另一個鏡頭的檔案")
                return@launch
            }
            otherCamera = toOther
            play(preferLocal(item), position, autoPlay = wasPlaying)
        }
    }

    /** 播完這一段：設定要自動播下一段的話，接著播錄影時間的下一段。 */
    fun onEnded() {
        if (endHandled || !container.settings.state.value.autoPlayNext) return
        endHandled = true
        PlayOrder.nextInTime(queue, index)?.let(::playAt)
    }

    fun stepFrame(forward: Boolean) {
        player.pause()
        val frame = (1000f / (player.videoFormat?.frameRate?.takeIf { it > 0 } ?: 30f)).roundToLong()
        player.seekTo((player.currentPosition + if (forward) frame else -frame).coerceAtLeast(0))
    }

    fun toggleAutoPlay() {
        val on = !settings.autoPlayNext
        container.appScope.launch { container.settings.update { it.copy(autoPlayNext = on) } }
        toast(if (on) "播完會自動播下一段（依錄影時間）" else "播完停在這一段")
    }

    /** 從記錄器串流中的檔案加入下載佇列（播放時佇列暫停，離開播放畫面後才開始）。 */
    fun downloadCurrent() {
        val file = current.sdPath?.let(container.files::find)
        if (file == null) {
            toast("請回到「檔案」頁下載")
            return
        }
        val added = container.downloads.enqueue(listOf(file))
        toast(if (added > 0) "已加入下載佇列，離開播放畫面後開始下載" else "這個檔案已在下載佇列中")
    }

    // 控制列的上一段／下一段按鈕：與左右滑動相同（播放器裡只有一段，要自己接）
    val jumpState = rememberUpdatedState(::jump)
    val controls = remember(player) {
        object : ForwardingPlayer(player) {
            private fun hasPrevious() = index > 0
            private fun hasNext() = index < queue.lastIndex

            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().apply {
                    if (hasPrevious()) add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    if (hasNext()) addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }.build()

            override fun isCommandAvailable(command: Int): Boolean = when (command) {
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> hasPrevious()
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> hasNext()
                else -> super.isCommandAvailable(command)
            }

            override fun hasPreviousMediaItem() = hasPrevious()
            override fun hasNextMediaItem() = hasNext()
            override fun seekToPreviousMediaItem() = jumpState.value(-1)
            override fun seekToNextMediaItem() = jumpState.value(1)
            override fun seekToNext() = jumpState.value(1)

            override fun seekToPrevious() {
                // 同 ExoPlayer：播了一段時間先回到開頭，否則換到上一段
                if (!hasPrevious() || currentPosition > maxSeekToPreviousPosition) seekTo(0) else jumpState.value(-1)
            }
        }
    }

    // 記錄器一次只服務一個連線：從記錄器串流時先暫停下載佇列，離開或改播手機上的檔案時再繼續
    val streamingNow = current.url.startsWith("http")
    DisposableEffect(streamingNow) {
        if (!streamingNow) return@DisposableEffect onDispose { }
        if (container.downloads.hold()) {
            Toast.makeText(context, "已暫停下載，離開播放畫面後會從中斷處繼續", Toast.LENGTH_SHORT).show()
        }
        onDispose { container.downloads.release() }
    }

    val onEndedState = rememberUpdatedState(::onEnded)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) onEndedState.value()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEndedState.value()
            }

            override fun onPlayerError(e: PlaybackException) {
                val message = playbackErrorMessage(e, streaming = current.url.startsWith("http"))
                val showingOther = current.title != queue.getOrNull(index)?.title
                error = if (showingOther && e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    "記錄器上沒有對應的另一鏡頭檔"
                } else {
                    message
                }
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

    // 實際錄影時間 = 這段的開始時間 + 播放位置
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            delay(500)
        }
    }
    val startedAt = remember(current.title) { LocalLibrary.recordingStart(current.title) }
    val recordedAt = startedAt?.plusNanos(positionMs * 1_000_000)

    // 全螢幕：轉橫向並隱藏狀態列 / 導覽列；離開畫面時還原
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    // 雙指放大影片（例如看車牌）；換到別段時還原
    val zoom = remember { ZoomState() }
    LaunchedEffect(current.url) { zoom.reset() }
    zoom.contentSize = { playerView?.videoSurfaceView?.let { IntSize(it.width, it.height) } ?: IntSize.Zero }

    val screenshot: () -> Unit = {
        val crop = if (settings.screenshotVisibleArea && zoom.isZoomed) zoom.visibleRect() else null
        captureFrame(container, playerView, player, current.title, scope, crop, toast)
    }

    /** 刪掉的那一段從清單移除，接著播下一段（已經是最後一段就播前一段，清單空了就離開）。 */
    fun dropFromQueue() {
        val removed = index
        val rest = queue.filterIndexed { i, _ -> i != removed }
        if (rest.isEmpty()) {
            onBack()
            return
        }
        queue = rest
        playAt(removed.coerceAtMost(rest.lastIndex))
    }

    val deleteItem = rememberMediaDeleter(container, toast) { item, deleted ->
        when {
            // 沒刪成或使用者取消：恢復剛才停掉的播放
            !deleted -> {
                player.prepare()
                player.play()
            }
            item.title == queue.getOrNull(index)?.title -> dropFromQueue()
            // 刪的是另一個鏡頭的檔案：回到清單裡的那一段
            else -> {
                otherCamera = false
                playAt(index)
            }
        }
    }
    var pendingDelete by remember { mutableStateOf<PlayItem?>(null) }

    FullscreenEffect(fullscreen) { fullscreen = false }

    Box(Modifier.fillMaxSize().background(Color.Black).swipeToJump(enabled = { !zoom.isZoomed }, onJump = ::jump)) {
        Box(
            (if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding())
                // Initial：搶在 PlayerView 之前處理，捏合時不會順便切換控制列；下方進度條的拖曳不拿來平移
                .zoomGestures(zoom, PointerEventPass.Initial) { pos, size -> pos.y > size.height * 0.75f },
        ) {
            AndroidView(
                factory = { ctx ->
                    // TextureView 版的 PlayerView 才能縮放影像（見 player_view_texture.xml）
                    (LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                        this.player = controls
                        setShowSubtitleButton(false)
                        // 設了監聽器，控制列才會出現全螢幕按鈕
                        setFullscreenButtonClickListener { fullscreen = it }
                        // 控制列出現時，上方的切換鈕也一起出現
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == View.VISIBLE },
                        )
                        playerView = this
                    }
                },
                update = {
                    it.setFullscreenButtonState(fullscreen)
                    // 只縮放影像本身，控制列維持原樣
                    it.videoSurfaceView?.apply {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.fillMaxWidth().safeDrawingPadding()) {
            if (fullscreen) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    recordedAt?.let {
                        Text(
                            Formats.dateTime(it),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 全螢幕時右上角也保留截圖
                    IconButton(onClick = screenshot, modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "截圖", tint = Color.White)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        recordedAt?.let {
                            Text("錄影時間 ${Formats.dateTime(it)}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                        if (queue.size > 1) {
                            Text(
                                "${index + 1} / ${queue.size}　左右滑動切換・雙指放大",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    IconButton(onClick = screenshot) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "截圖", tint = Color.White)
                    }
                    // 只有手機上的檔案可以剪輯（串流中的記錄器檔案要先下載）
                    if (isLocal(current)) {
                        IconButton(onClick = { onEdit(current.url, current.title) }) {
                            Icon(Icons.Filled.ContentCut, contentDescription = "剪輯", tint = Color.White)
                        }
                    }
                    MoreMenu(
                        canShare = isLocal(current),
                        canDownload = !isLocal(current) && current.sdPath != null,
                        canDelete = isDeletable(current),
                        onShare = { shareMedia(context, listOf(Uri.parse(current.url)), "video/*") },
                        onDownload = ::downloadCurrent,
                        onDelete = { pendingDelete = current },
                    )
                }
            }
            // 控制列出現或暫停時：切換鏡頭、逐格、自動播下一段
            if (controlsVisible || !isPlaying) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (FileNames.counterpartName(current.title) != null) {
                        val toRear = FileNames.cameraOfName(current.title) != Camera.Rear
                        OverlayChip(if (toRear) "切到後鏡頭" else "切到前鏡頭", Icons.Filled.Cameraswitch, onClick = ::switchCamera)
                    }
                    if (!isPlaying) {
                        OverlayChip("上一格", Icons.AutoMirrored.Filled.KeyboardArrowLeft) { stepFrame(forward = false) }
                        OverlayChip("下一格", Icons.AutoMirrored.Filled.KeyboardArrowRight) { stepFrame(forward = true) }
                    }
                    if (queue.size > 1) {
                        OverlayChip(
                            "自動播下一段",
                            Icons.AutoMirrored.Filled.PlaylistPlay,
                            selected = settings.autoPlayNext,
                            onClick = ::toggleAutoPlay,
                        )
                    }
                }
            }
            error?.let {
                Text(
                    it,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
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
    }
}

private fun mediaItem(item: PlayItem): MediaItem = MediaItem.Builder().setUri(item.url).setMediaId(item.url).build()

/** 標題列右邊的「更多」：分享（手機上的檔案）、加入下載（記錄器上的檔案）、刪除。 */
@Composable
private fun MoreMenu(
    canShare: Boolean,
    canDownload: Boolean,
    canDelete: Boolean,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    if (!canShare && !canDownload && !canDelete) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canShare) {
                DropdownMenuItem(
                    text = { Text("分享") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = { open = false; onShare() },
                )
            }
            if (canDownload) {
                DropdownMenuItem(
                    text = { Text("加入下載") },
                    leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    onClick = { open = false; onDownload() },
                )
            }
            if (canDelete) {
                DropdownMenuItem(
                    text = { Text("刪除…", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { open = false; onDelete() },
                )
            }
        }
    }
}

/** 疊在影片上的小按鈕（半透明黑底白字；[selected] 時用主色）。 */
@Composable
private fun OverlayChip(label: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 左右滑動切換影片：在 Initial 階段觀察手勢（不攔截），播放器的點擊、控制列照常運作；
 * 從畫面下方 1/4（進度條所在）開始的拖曳不算，才不會跟拖曳進度條衝突。
 * 放大中（[enabled] 為 false）或用了兩根以上手指（捏合縮放）時不切換。
 */
private fun Modifier.swipeToJump(enabled: () -> Boolean, onJump: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.y > size.height * 0.75f || !enabled()) return@awaitEachGesture
        var last = down.position
        var multiTouch = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) multiTouch = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            last = change.position
            if (!change.pressed) break
        }
        if (multiTouch || !enabled()) return@awaitEachGesture
        val dx = last.x - down.position.x
        val dy = last.y - down.position.y
        if (abs(dx) > 80.dp.toPx() && abs(dx) > 2 * abs(dy)) onJump(if (dx < 0) 1 else -1)
    }
}
