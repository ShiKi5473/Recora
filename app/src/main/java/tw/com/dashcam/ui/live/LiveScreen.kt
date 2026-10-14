package tw.com.dashcam.ui.live

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
import tw.com.dashcam.ui.common.FullscreenEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.com.dashcam.AppContainer
import tw.com.dashcam.data.Camera
import tw.com.dashcam.data.api.ignoreIo
import tw.com.dashcam.data.net.ConnState
import tw.com.dashcam.ui.common.ConnectionChip
import tw.com.dashcam.ui.common.MessagePane
import tw.com.dashcam.ui.common.NotConnectedPane
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
    var viewId by rememberSaveable { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("連線中…") }
    var generation by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf("") }
    val player = remember { ExoPlayer.Builder(context).build() }
    val views = container.api.liveViews
    val view = views.firstOrNull { it.id == viewId } ?: views.first()

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                status = when (state) {
                    Player.STATE_BUFFERING -> "緩衝中…"
                    Player.STATE_READY -> "播放中"
                    Player.STATE_ENDED -> "串流結束"
                    else -> status
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                status = "無法播放：${e.errorCodeName}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            container.appScope.launch { ignoreIo { container.api.stopLive() } }
        }
    }

    // 切換畫面：先停掉串流 → 重新準備 → 改播記錄器回的網址
    LaunchedEffect(generation, view.id) {
        status = "連線中…"
        player.stop()
        val source = try {
            container.api.startLive(view)
        } catch (e: IOException) {
            status = "無法開始串流：${e.message}"
            return@LaunchedEffect
        }
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
    val selectView: (Int) -> Unit = { id -> viewId = id }
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

    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            LiveVideo(player, Modifier.fillMaxSize())
            FilledTonalIconButton(
                onClick = { onFullscreen(true) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.Fullscreen, contentDescription = "全螢幕")
            }
        }
        Column(Modifier.padding(16.dp)) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                views.forEach { v ->
                    FilterChip(
                        selected = v.id == view.id,
                        onClick = { selectView(v.id) },
                        label = { Text(v.label) },
                    )
                }
            }
            OutlinedButton(onClick = { generation++ }, modifier = Modifier.padding(top = 12.dp)) {
                Text("重新連線")
            }
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
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
