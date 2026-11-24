package tw.com.recora.ui.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.media3.exoplayer.SeekParameters
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
import tw.com.recora.AppContainer
import tw.com.recora.R
import tw.com.recora.data.Camera
import tw.com.recora.data.FileNames
import tw.com.recora.data.LocalLibrary
import tw.com.recora.data.PlayItem
import tw.com.recora.data.PlayOrder
import tw.com.recora.data.PlaySources
import tw.com.recora.data.PlayTargets
import tw.com.recora.data.gps.GpsPoint
import tw.com.recora.data.gps.GpsTrack
import tw.com.recora.data.gps.LocalGps
import tw.com.recora.data.net.ConnState
import tw.com.recora.ui.Formats
import tw.com.recora.ui.common.FullscreenEffect
import tw.com.recora.ui.common.LightSystemBarIcons
import tw.com.recora.ui.common.ConfirmDeleteDialog
import tw.com.recora.ui.common.OverlayChip
import tw.com.recora.ui.common.OverlayTopBar
import tw.com.recora.ui.theme.Scrim
import tw.com.recora.ui.common.ZoomState
import tw.com.recora.ui.common.captureFrame
import tw.com.recora.ui.common.rememberDashcamGrant
import tw.com.recora.ui.common.rememberMediaDeleter
import tw.com.recora.ui.common.shareMedia
import tw.com.recora.ui.common.zoomGestures
import java.util.Locale
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
    // 清單與目前位置跟著返回堆疊保存（見 PlayerSession）：去剪輯再回來仍停在剛才那一段
    val session = viewModel {
        // 播放清單：來源頁面設定的清單（含目前這段），左右滑動切換；不在清單裡就只播這一段
        val q = container.playQueue.takeIf { q -> q.any { it.url == url } } ?: listOf(PlayItem(url, title))
        PlayerSession(q, q.indexOfFirst { it.url == url }.coerceAtLeast(0))
    }
    var queue by session::queue
    var index by session::index
    var current by session::current
    var otherCamera by session::otherCamera
    // 從記錄器串流的清單用較小的 TS 搜尋範圍（見 dashcamMediaSourceFactory）
    val streaming = remember { queue.any { it.isStreaming } }
    // 記錄器一次只服務一個連線：從記錄器串流時先取得使用權（暫停下載佇列、Astro 停錄並切到回放模式），
    // 拿到之後才開始載入；進背景、離開或改播手機上的檔案時歸還，下載從中斷處繼續
    val dashcamReady = rememberDashcamGrant(
        container,
        reason = "正在播放記錄器上的影片",
        enabled = current.isStreaming,
        files = true,
    ) { toast("已暫停下載，離開播放畫面後會從中斷處繼續") }
    val player = remember {
        dashcamPlayer(context, container.http, streaming).apply {
            setMediaItem(mediaItem(current), session.resumePositionMs)
            // 每段播完停下；要不要接著播下一段由 onEnded 決定
            pauseAtEndOfMediaItems = true
            // 記錄器上的檔案等拿到使用權才載入（見下方 LaunchedEffect(dashcamReady.value)）
            if (!current.isStreaming) prepare()
            playWhenReady = session.resumePlaying
        }
    }
    var isPlaying by remember { mutableStateOf(session.resumePlaying) }
    var controlsVisible by remember { mutableStateOf(true) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(false) }
    /** 底部控制列頂端的 Y（沒顯示時是最大值）：左右滑動、縮放平移的手勢不從這裡以下開始。 */
    var controlsTopPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var lastScrubSeekAt by remember { mutableLongStateOf(0L) }
    /** 每次操作控制列加一，重新計算自動隱藏的時間。 */
    var interactions by remember { mutableIntStateOf(0) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var endHandled by remember { mutableStateOf(false) }

    // 換段、換鏡頭時實際要播哪個檔案由 PlayTargets 決定；這裡只提供去哪裡找
    val sources = remember(container) {
        object : PlaySources {
            /** 手機上已下載完成的同名影片（content://，含 Astro App 的資料夾）。 */
            override suspend fun localCopy(name: String): String? = withContext(Dispatchers.IO) {
                runCatching { container.sink.findVideo(name) }.getOrNull()?.toString()
            }

            override fun streamUrl(sdPath: String): String = container.api.httpUrl(sdPath)
        }
    }

    fun play(item: PlayItem, positionMs: Long = 0, autoPlay: Boolean = true) {
        current = item
        error = null
        endHandled = false
        // 記錄器上的檔案要等拿到使用權才載入：先停掉，免得換了網址就馬上開始讀
        val wait = item.isStreaming && !dashcamReady.value
        if (wait) player.stop()
        player.setMediaItem(mediaItem(item), positionMs)
        if (!wait) player.prepare()
        player.playWhenReady = autoPlay
    }

    /** 播清單裡的第 [target] 段（切到另一鏡頭時盡量播另一鏡頭；手機上有就播手機上的）。 */
    fun playAt(target: Int) {
        index = target
        val base = queue[target]
        loadJob?.cancel()
        loadJob = scope.launch { play(PlayTargets.resolve(base, otherCamera, sources)) }
    }

    /** 換段：[delta] > 0 播錄影時間的下一段（較新），< 0 播上一段（較舊）。清單是新到舊排列，所以不是 index ± 1。 */
    fun jump(delta: Int) {
        val target = if (delta > 0) PlayOrder.nextInTime(queue, index) else PlayOrder.previousInTime(queue, index)
        if (target == null) {
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
            val item = if (toOther) PlayTargets.counterpart(base, sources) else base
            if (item == null) {
                toast("找不到另一個鏡頭的檔案")
                return@launch
            }
            otherCamera = toOther
            play(PlayTargets.preferLocal(item, sources), position, autoPlay = wasPlaying)
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
        container.settings.launchUpdate { it.copy(autoPlayNext = on) }
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

    // 錄影時間的上一段 / 下一段（控制列每 200ms 重組，這裡只在換段或清單變動時重算）
    val previousIndex = remember(queue, index) { PlayOrder.previousInTime(queue, index) }
    val nextIndex = remember(queue, index) { PlayOrder.nextInTime(queue, index) }

    /** 上一段：播了一段時間先回到開頭，否則換到上一段（同 ExoPlayer 內建控制列的行為）。 */
    fun previousOrRestart() {
        if (previousIndex == null || player.currentPosition > RESTART_THRESHOLD_MS) player.seekTo(0) else jump(-1)
    }

    // 拿到記錄器的使用權才開始載入串流；歸還時（App 進背景）停止載入、把連線讓給下載，回來再從同一個位置接著載入
    LaunchedEffect(dashcamReady.value) {
        if (!current.isStreaming) return@LaunchedEffect
        if (dashcamReady.value) {
            if (player.playbackState == Player.STATE_IDLE) {
                error = null
                player.prepare()
            }
        } else {
            player.stop()
        }
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
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) onEndedState.value()
            }

            override fun onPlayerError(e: PlaybackException) {
                val message = playbackErrorMessage(e, streaming = current.isStreaming)
                val showingOther = current.title != queue.getOrNull(index)?.title
                error = if (showingOther && e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    "記錄器上沒有對應的另一鏡頭檔"
                } else {
                    message
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                // 串流中：暫停時 ExoPlayer 仍會預讀、佔住記錄器的連線（Astro 切回錄影模式後也讀不到），
                // stop 才會關掉；清單與位置都留著。不會觸發 onPlayerError，也不是播完（STATE_IDLE 不是 ENDED）
                if (current.isStreaming) player.stop()
            } else if (event == Lifecycle.Event.ON_START && player.playbackState == Player.STATE_IDLE &&
                !current.isStreaming
            ) {
                // 回到 App：重新載入、維持暫停（註冊時立刻收到的 ON_START 播放器還不是 IDLE，不會走到這裡）
                error = null
                player.prepare()
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // 去剪輯等畫面再回來時從這裡繼續（見 PlayerSession）
            session.resumePositionMs = player.currentPosition
            session.resumePlaying = player.playWhenReady
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    // 實際錄影時間 = 這段的開始時間 + 播放位置
    var positionMs by remember { mutableLongStateOf(0L) }
    val showControls = controlsVisible || !isPlaying
    LaunchedEffect(player, showControls) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            // 控制列顯示時更新密一點，進度條才順
            delay(if (showControls) 200 else 500)
        }
    }
    // 播放中、控制列顯示 3 秒沒有操作就自動收起；暫停時一直顯示
    LaunchedEffect(controlsVisible, isPlaying, interactions) {
        if (controlsVisible && isPlaying) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }
    val startedAt = remember(current.title) { LocalLibrary.recordingStart(current.title) }
    val recordedAt = startedAt?.plusNanos(positionMs * 1_000_000)

    // 影片裡的 GPS（Astro 的原始檔才有）：換段時從手機上的檔案讀一次；串流中的記錄器檔案不讀。
    // 讀完才換掉（讀檔只要幾 ms）：先清成 null 的話，換段時路線地圖會被拆掉再重建
    val gps by produceState<GpsTrack?>(null, current.url) {
        val item = current
        value = if (item.isLocal) LocalGps.read(context, item.url) else null
    }
    val hasGpsFix = remember(gps) { gps?.points?.any { it?.fix == true } == true }

    /** 播放位置那一秒的 GPS（見 [GpsTrack.pointForPosition]）。 */
    fun gpsAt(ms: Long): GpsPoint? = gps?.pointForPosition(ms)
    val gpsText = if (settings.showGps && gps != null) gpsLabel(gpsAt(positionMs)) else null

    /** 用地圖 App 開啟目前這一秒的位置（外部 App 不受本 App 綁在記錄器網路上的影響）。 */
    fun openMap() {
        val p = gpsAt(player.currentPosition)?.takeIf { it.fix }
        if (p == null) {
            toast("這個時間點沒有定位")
            return
        }
        val at = String.format(Locale.US, "%.6f,%.6f", p.latitude, p.longitude)
        // Uri.encode 不編碼括號，但括號是 q=…(標籤) 的分隔
        val label = Uri.encode(recordedAt?.let(Formats::dateTime) ?: current.title).replace("(", "%28").replace(")", "%29")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$at?q=$at($label)")))
        } catch (e: ActivityNotFoundException) {
            toast("找不到可以開啟地圖的 App")
        }
    }

    /** 整段的 GPS 軌跡寫成 GPX 分享出去（見 [writeGpxForShare]）。 */
    fun shareGpx() {
        val track = gps ?: return
        val name = current.title.substringBeforeLast('.')
        scope.launch {
            val uri = writeGpxForShare(context, name, track)
            if (uri == null) toast("無法產生 GPS 軌跡") else shareMedia(context, listOf(uri), "application/gpx+xml")
        }
    }

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
        val (rest, next) = PlayOrder.remove(queue, index) ?: run {
            onBack()
            return
        }
        queue = rest
        // 畫面重建（從剪輯頁回來）時清單由 playQueue 重建，刪掉的那一段也要拿掉
        container.playQueue = rest
        playAt(next)
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

    // 路線地圖：直向、非全螢幕、這段有已定位的 GPS 時放在畫面下半部
    val connState by container.connection.state.collectAsStateWithLifecycle()
    val dashcamBound = (connState as? ConnState.Connected)?.viaOverride == false
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val canShowMap = hasGpsFix && !fullscreen && portrait
    val mapShown = canShowMap && settings.showRouteMap
    // 地圖在下面時，影片區不留底部系統列的空間（改由地圖留）
    val videoInsets = if (mapShown) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) else WindowInsets.safeDrawing

    fun toggleMap() {
        val on = !settings.showRouteMap
        container.settings.launchUpdate { it.copy(showRouteMap = on) }
    }

    FullscreenEffect(fullscreen) { fullscreen = false }
    LightSystemBarIcons()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().weight(1f).swipeToJump(enabled = { !zoom.isZoomed }, excluded = { p -> p.y > controlsTopPx }, onJump = ::jump)) {
            Box(
                (if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().windowInsetsPadding(videoInsets))
                    // Initial：搶在其他手勢之前處理，捏合時不會順便切換控制列；控制列（進度條）上的拖曳不拿來平移
                    .zoomGestures(zoom, PointerEventPass.Initial) { pos, _ -> pos.y > controlsTopPx }
                    // 點一下影片顯示或收起控制列
                    .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) },
            ) {
                AndroidView(
                    factory = { ctx ->
                        // TextureView 版的 PlayerView 才能縮放影像（見 player_view_texture.xml）
                        (LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                            this.player = player
                            // 控制列改由 Compose 畫（PlayerControls），這裡只負責影像
                            useController = false
                            playerView = this
                        }
                    },
                    update = {
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
            Column(Modifier.fillMaxWidth().windowInsetsPadding(videoInsets)) {
                AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
                if (fullscreen) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        recordedAt?.let {
                            Text(
                                Formats.dateTime(it),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.background(Scrim.light, MaterialTheme.shapes.extraSmall)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        gpsText?.let {
                            Text(
                                it,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp).background(Scrim.light, MaterialTheme.shapes.extraSmall)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // 全螢幕時右上角也保留截圖
                        IconButton(onClick = screenshot, modifier = Modifier.background(Scrim.light, CircleShape)) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = "截圖", tint = Color.White)
                        }
                    }
                } else {
                    OverlayTopBar {
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
                            // 窄螢幕放不下時跟「錄影時間」一樣換行，不截掉座標
                            gpsText?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
                            if (queue.size > 1) {
                                Text(
                                    "${index + 1} / ${queue.size}　滑動切換・雙指放大",
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = screenshot) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = "截圖", tint = Color.White)
                        }
                        // 只有手機上的檔案可以剪輯（串流中的記錄器檔案要先下載）
                        if (current.isLocal) {
                            IconButton(
                                onClick = {
                                    // 位置與播放狀態會在離開時記進 PlayerSession，從剪輯頁回來時停在同一段的同一個時間點
                                    onEdit(current.url, current.title)
                                },
                            ) {
                                Icon(Icons.Filled.ContentCut, contentDescription = "剪輯", tint = Color.White)
                            }
                        }
                        MoreMenu(
                            canShare = current.isLocal,
                            canDownload = !current.isLocal && current.sdPath != null,
                            canDelete = current.isDeletable,
                            hasGps = hasGpsFix,
                            onShare = { shareMedia(context, listOf(Uri.parse(current.url)), "video/*") },
                            onDownload = ::downloadCurrent,
                            onDelete = { pendingDelete = current },
                            onOpenMap = ::openMap,
                            onShareGpx = ::shareGpx,
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
                            .background(Scrim.strong, MaterialTheme.shapes.medium).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            // 寫全名：外層有 Column（下半部的地圖），不然會被解析成 ColumnScope 版本而編譯失敗
            androidx.compose.animation.AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                PlayerControls(
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    hasPrevious = previousIndex != null,
                    hasNext = nextIndex != null,
                    switchCameraLabel = if (FileNames.counterpartName(current.title) != null) {
                        if (FileNames.cameraOfName(current.title) != Camera.Rear) "切到後鏡頭" else "切到前鏡頭"
                    } else {
                        null
                    },
                    showAutoPlay = queue.size > 1,
                    autoPlayNext = settings.autoPlayNext,
                    mapShown = if (canShowMap) settings.showRouteMap else null,
                    fullscreen = fullscreen,
                    onPlayPause = {
                        interactions++
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            player.play()
                        }
                    },
                    onPrevious = { interactions++; previousOrRestart() },
                    onNext = { interactions++; jump(1) },
                    onSeekPreview = { ms ->
                        interactions++
                        // 拖曳中只做節流的關鍵格 seek（串流的 TS 精準 seek 很慢），放開再精準 seek
                        val now = SystemClock.uptimeMillis()
                        if (now - lastScrubSeekAt >= SCRUB_SEEK_INTERVAL_MS) {
                            lastScrubSeekAt = now
                            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                            player.seekTo(ms)
                        }
                    },
                    onSeekFinished = { ms ->
                        player.setSeekParameters(SeekParameters.EXACT)
                        player.seekTo(ms)
                        positionMs = ms
                    },
                    onStepFrame = { forward -> interactions++; stepFrame(forward) },
                    onSwitchCamera = { interactions++; switchCamera() },
                    onToggleAutoPlay = { interactions++; toggleAutoPlay() },
                    onToggleMap = { interactions++; toggleMap() },
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onTopChanged = { controlsTopPx = it },
                    modifier = Modifier.windowInsetsPadding(videoInsets),
                )
            }
            if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            pendingDelete?.let { item ->
                val local = item.isLocal
                ConfirmDeleteDialog(
                    title = if (local) "從手機相簿刪除？" else "從記錄器刪除？",
                    onDismiss = { pendingDelete = null },
                    onConfirm = {
                        pendingDelete = null
                        // 記錄器的 HTTP 伺服器一次只服務一個連線：先停播放，del 的 CGI 才連得上。
                        // 本機檔也先停，免得檔案在播放中被刪掉而跳出播放錯誤。
                        player.stop()
                        deleteItem(item)
                    },
                ) {
                    val detail = if (local) {
                        "會從手機相簿刪除，無法復原（記錄器上的原始檔不受影響）。"
                    } else {
                        "會從記錄器刪除，無法復原（手機上已下載的檔案不受影響）。"
                    }
                    Text("${item.title}\n$detail")
                }
            }
        }
        val track = gps
        if (mapShown && track != null) {
            RouteMap(
                track = track,
                current = gpsAt(positionMs),
                dashcamBound = dashcamBound,
                onSeek = { ms ->
                    interactions++
                    player.seekTo(ms)
                    positionMs = ms
                },
                modifier = Modifier.fillMaxWidth().weight(0.8f)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            )
        }
    }
}

/** 播了超過這麼久，「上一段」先回到開頭。 */
private const val RESTART_THRESHOLD_MS = 3_000L

/** 播放中沒操作多久後收起控制列。 */
private const val CONTROLS_HIDE_MS = 3_000L

/** 拖曳進度時 seek 的最短間隔。 */
private const val SCRUB_SEEK_INTERVAL_MS = 200L

private fun mediaItem(item: PlayItem): MediaItem = MediaItem.Builder().setUri(item.url).setMediaId(item.url).build()

/** 標題下的 GPS：時速與座標；那一秒沒有定位時說明。 */
private fun gpsLabel(p: GpsPoint?): String =
    if (p == null || !p.fix) "GPS 未定位" else String.format(Locale.US, "%.0f km/h  %.5f, %.5f", p.speedKmh, p.latitude, p.longitude)

/** 標題列右邊的「更多」：分享（手機上的檔案）、加入下載（記錄器上的檔案）、GPS（開地圖、分享軌跡）、刪除。 */
@Composable
private fun MoreMenu(
    canShare: Boolean,
    canDownload: Boolean,
    canDelete: Boolean,
    hasGps: Boolean,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onOpenMap: () -> Unit,
    onShareGpx: () -> Unit,
) {
    if (!canShare && !canDownload && !canDelete && !hasGps) return
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
            if (hasGps) {
                DropdownMenuItem(
                    text = { Text("在地圖開啟目前位置") },
                    leadingIcon = { Icon(Icons.Filled.Map, contentDescription = null) },
                    onClick = { open = false; onOpenMap() },
                )
                DropdownMenuItem(
                    text = { Text("分享 GPS 軌跡（GPX）") },
                    leadingIcon = { Icon(Icons.Filled.Timeline, contentDescription = null) },
                    onClick = { open = false; onShareGpx() },
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

/**
 * 左右滑動切換影片：由右往左滑傳 +1（錄影時間的下一段，較新），由左往右滑傳 -1（較舊）。
 * 在 Initial 階段觀察手勢（不攔截），播放器的點擊、控制列照常運作；
 * 從畫面下方 1/4（進度條所在）開始的拖曳不算，才不會跟拖曳進度條衝突。
 * 放大中（[enabled] 為 false）或用了兩根以上手指（捏合縮放）時不切換。
 */
private fun Modifier.swipeToJump(
    enabled: () -> Boolean,
    excluded: (Offset) -> Boolean,
    onJump: (Int) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (excluded(down.position) || !enabled()) return@awaitEachGesture
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
