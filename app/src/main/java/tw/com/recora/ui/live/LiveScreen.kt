package tw.com.recora.ui.live

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.ui.Alignment
import tw.com.recora.ui.common.FullscreenEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.com.recora.AppContainer
import tw.com.recora.data.Camera
import tw.com.recora.data.api.ignoreIo
import tw.com.recora.data.net.ConnState
import tw.com.recora.ui.common.ConnectionChip
import tw.com.recora.ui.common.MessagePane
import tw.com.recora.ui.common.NotConnectedPane
import tw.com.recora.ui.theme.Scrim
import tw.com.recora.ui.theme.recora
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import java.io.IOException

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(container: AppContainer) {
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val downloading by container.downloads.active.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val canPlay = conn is ConnState.Connected && !downloading
    val full = fullscreen && canPlay
    Scaffold(
        topBar = {
            if (!full) TopAppBar(title = { Text("即時畫面") }, actions = { ConnectionChip(container) })
        },
        contentWindowInsets = if (full) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
    ) { pad ->
        val modifier = Modifier.padding(pad)
        when {
            conn !is ConnState.Connected -> NotConnectedPane(container, modifier)
            downloading -> MessagePane(
                icon = Icons.Filled.Download,
                title = "下載進行中",
                text = "即時畫面和下載會互搶記錄器的頻寬，下載完成後再來看。",
                modifier = modifier,
            )
            else -> LivePlayer(container, modifier, full) { fullscreen = it }
        }
    }
}

/**
 * RTSP 即時預覽：串流的準備流程（MUFU 的 streamer／setcamid、Astro 的 2015／3028）都在 [DashcamApi.startLive]，
 * 這裡只播它回傳的網址。走 TCP 在車內 Wi-Fi 較穩。
 * 退到背景時停掉串流（否則記錄器收到的車內聲音會一直從手機喇叭播出），回到前景再重新開始；
 * 播放中斷時最多自動重連 [MAX_AUTO_RETRIES] 次。
 * 不要把 `container.api` 存在變數裡：換記錄器後才不會拿到舊的實作。
 */
@OptIn(UnstableApi::class)
@Composable
private fun LivePlayer(
    container: AppContainer,
    modifier: Modifier,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var viewId by rememberSaveable { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("連線中…") }
    var generation by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf("") }
    // 播放中斷後已自動重連的次數與等待中的重連；成功播放、手動重連、切換畫面、回到前景時次數歸零
    var retries by remember { mutableIntStateOf(0) }
    var retryJob by remember { mutableStateOf<Job?>(null) }
    // 退到背景時串流已停掉：回到前景要重新開始，在那之前也不能自動重連
    var stoppedInBackground by remember { mutableStateOf(false) }
    val player = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(LIVE_MIN_BUFFER_MS, LIVE_MAX_BUFFER_MS, LIVE_BUFFER_FOR_PLAYBACK_MS, LIVE_BUFFER_AFTER_REBUFFER_MS)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build()
    }
    val views = container.api.liveViews
    val view = views.firstOrNull { it.id == viewId } ?: views.first()

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
                status = when (state) {
                    Player.STATE_BUFFERING -> "緩衝中…"
                    Player.STATE_READY -> "播放中"
                    Player.STATE_ENDED -> "串流結束"
                    else -> status
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                // 退到背景時串流是我們自己停掉的，不重連（回到前景會重新開始）
                if (stoppedInBackground) return
                if (retries < MAX_AUTO_RETRIES) {
                    retries++
                    status = "連線中斷，重新連線中（第 $retries 次）…"
                    retryJob = scope.launch {
                        delay(AUTO_RETRY_DELAY_MS)
                        generation++
                    }
                } else {
                    status = "無法播放：${e.errorCodeName}"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            container.appScope.launch { ignoreIo { container.api.stopLive() } }
        }
    }

    // 退到背景：停掉串流並通知記錄器（MUFU 送 streamer=stop）；回到前景再重新開始
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                stoppedInBackground = true
                retryJob?.cancel()
                player.stop()
                container.appScope.launch { ignoreIo { container.api.stopLive() } }
            } else if (event == Lifecycle.Event.ON_START && stoppedInBackground) {
                // 註冊 observer 時會馬上收到一次 ON_START，沒停過就不用重新開始
                stoppedInBackground = false
                retries = 0
                generation++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 切換畫面：先停掉串流 → 重新準備 → 改播記錄器回的網址
    LaunchedEffect(generation, view.id) {
        // 任何一種重新開始（手動重連、切換畫面、回到前景）都不該再被等待中的自動重連重複觸發
        retryJob?.cancel()
        status = "連線中…"
        player.stop()
        val source = try {
            container.api.startLive(view)
        } catch (e: IOException) {
            status = "無法開始串流：${e.message}"
            return@LaunchedEffect
        }
        // 準備串流的途中退到背景：不要開始播，回到前景時會重新開始
        if (stoppedInBackground) return@LaunchedEffect
        url = source.url
        val media = RtspMediaSource.Factory()
            .setForceUseRtpTcp(source.forceTcp)
            .setTimeoutMs(8_000)
            .createMediaSource(MediaItem.fromUri(source.url))
        player.setMediaSource(media)
        player.prepare()
        player.playWhenReady = true
        delay(500)
        ignoreIo { container.api.afterLiveStarted() }
    }

    FullscreenEffect(fullscreen) { onFullscreen(false) }
    val selectView: (Int) -> Unit = { id ->
        viewId = id
        retries = 0
    }
    val next = views[(views.indexOf(view) + 1) % views.size]

    if (fullscreen) {
        Box(modifier.fillMaxSize().background(Color.Black)) {
            LiveVideo(player, Modifier.fillMaxSize())
            // 全螢幕時只留切換與離開按鈕
            Row(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (views.size > 1) {
                    FilledTonalButton(onClick = { selectView(next.id) }) { Text("切到${next.label}") }
                }
                FilledTonalIconButton(onClick = { onFullscreen(false) }) {
                    Icon(Icons.Filled.FullscreenExit, contentDescription = "離開全螢幕")
                }
            }
        }
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(
            Modifier.padding(16.dp).fillMaxWidth().aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.large).background(Color.Black),
        ) {
            LiveVideo(player, Modifier.fillMaxSize())
            val playing = status == "播放中"
            val loading = status.startsWith("連線中") || status.startsWith("緩衝中")
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            if (playing) LiveBadge(Modifier.align(Alignment.TopStart).padding(12.dp))
            FilledTonalIconButton(
                onClick = { onFullscreen(true) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.Fullscreen, contentDescription = "全螢幕")
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (views.size > 1) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    views.forEachIndexed { i, v ->
                        SegmentedButton(
                            selected = v.id == view.id,
                            onClick = { selectView(v.id) },
                            shape = SegmentedButtonDefaults.itemShape(i, views.size),
                        ) { Text(v.label) }
                    }
                }
            }
            OutlinedButton(onClick = { retries = 0; generation++ }) { Text("重新連線") }
            Text(
                url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 影像左上角的「● LIVE」：紅點緩慢脈動，呼應 logo 的錄影點。 */
@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "live")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "liveDot",
    )
    Row(
        modifier.background(Scrim.light, MaterialTheme.shapes.extraSmall).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(alpha).background(MaterialTheme.recora.recordDot, CircleShape))
        Text(
            "LIVE",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LiveVideo(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
            }
        },
        modifier = modifier,
    )
}

// 即時畫面的緩衝量：ExoPlayer 預設要先緩衝 2.5 秒才開始播（卡頓後要 5 秒才恢復），畫面會一直落後至少這麼多，
// 所以縮成 0.5 秒就起播、最多預讀 3 秒。代價是緩衝少，Wi-Fi 不穩時比較容易卡——需要在車上實測再調整數值。
private const val LIVE_MIN_BUFFER_MS = 1_000
private const val LIVE_MAX_BUFFER_MS = 3_000
private const val LIVE_BUFFER_FOR_PLAYBACK_MS = 500
private const val LIVE_BUFFER_AFTER_REBUFFER_MS = 1_000

/** 播放中斷後自動重連的次數上限，與每次重連前的等待時間。 */
private const val MAX_AUTO_RETRIES = 2
private const val AUTO_RETRY_DELAY_MS = 2_000L
