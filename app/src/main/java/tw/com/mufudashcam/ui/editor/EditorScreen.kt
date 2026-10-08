package tw.com.mufudashcam.ui.editor

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.DashcamConfig
import tw.com.mufudashcam.ui.common.captureFrame
import tw.com.mufudashcam.ui.player.dashcamPlayer
import tw.com.mufudashcam.ui.player.playbackErrorMessage
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 剪輯：預覽並選取範圍、去除聲音、截圖，匯出成 H.264 MP4 存到 Movies/MufuDashcam。
 * 只處理手機上的檔案（已下載或從相簿挑選）。
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    container: AppContainer,
    uri: String,
    title: String,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val baseName = remember(title) { title.substringBeforeLast('.').ifBlank { "clip" } }

    val player = remember {
        dashcamPlayer(context, streaming = false).apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    val exporter = remember { ClipExporter(context) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var range by remember { mutableStateOf(0f..0f) }
    var removeAudio by rememberSaveable { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf<Int?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                scope.launch { snackbar.showSnackbar(playbackErrorMessage(error, streaming = false)) }
            }
        }
        player.addListener(listener)
        onDispose {
            exporter.cancel()
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(removeAudio) { player.volume = if (removeAudio) 0f else 1f }
    // 更新目前位置；播放到選取範圍終點時停下
    LaunchedEffect(player) {
        while (true) {
            if (durationMs <= 0 && player.duration > 0) {
                durationMs = player.duration
                range = 0f..durationMs.toFloat()
            }
            positionMs = player.currentPosition
            if (player.isPlaying && durationMs > 0 && positionMs >= range.endInclusive.toLong()) {
                player.pause()
                player.seekTo(range.endInclusive.toLong())
            }
            delay(100)
        }
    }

    val startMs = range.start.roundToLong()
    val endMs = range.endInclusive.roundToLong()
    val exporting = exportProgress != null

    fun screenshot() = captureFrame(container, playerView, player, title, scope) { msg ->
        scope.launch { snackbar.showSnackbar(msg) }
    }

    fun export() {
        player.pause()
        exportProgress = 0
        val output = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val name = "${baseName}_${nameTime(startMs)}-${nameTime(endMs)}${if (removeAudio) "_靜音" else ""}.mp4"
        exporter.start(Uri.parse(uri), startMs, endMs, removeAudio, output, onProgress = { exportProgress = it }) { result ->
            exportProgress = null
            scope.launch {
                var saved = false
                val message = result.fold(
                    onSuccess = { file ->
                        try {
                            withContext(Dispatchers.IO) {
                                container.sink.saveFile(file, name, "video/mp4", isImage = false)
                                file.delete()
                            }
                            saved = true
                            container.libraryChanged.tryEmit(Unit)
                            "已匯出 $name"
                        } catch (e: Exception) {
                            "匯出檔案儲存失敗：${e.message}"
                        }
                    },
                    onFailure = { "匯出失敗：${it.message}" },
                )
                if (saved) {
                    val action = snackbar.showSnackbar(message, actionLabel = "到相簿查看", duration = SnackbarDuration.Long)
                    if (action == SnackbarResult.ActionPerformed) onOpenLibrary()
                } else {
                    snackbar.showSnackbar(message)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("剪輯")
                        Text(title, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            setShowSubtitleButton(false)
                            playerView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("目前 ${clock(positionMs)} / ${clock(durationMs)}", style = MaterialTheme.typography.bodyMedium)
                if (durationMs > 0) {
                    RangeSlider(
                        value = range,
                        onValueChange = { new ->
                            val movedStart = new.start != range.start
                            range = new
                            player.seekTo((if (movedStart) new.start else new.endInclusive).toLong())
                        },
                        valueRange = 0f..durationMs.toFloat(),
                        enabled = !exporting,
                    )
                }
                Text(
                    "起點 ${clock(startMs)}　終點 ${clock(endMs)}　長度 ${clock(endMs - startMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { range = positionMs.coerceAtMost(endMs - MIN_CLIP_MS).coerceAtLeast(0).toFloat()..range.endInclusive },
                        enabled = !exporting && durationMs > 0,
                    ) { Text("設為起點") }
                    OutlinedButton(
                        onClick = { range = range.start..positionMs.coerceAtLeast(startMs + MIN_CLIP_MS).coerceAtMost(durationMs).toFloat() },
                        enabled = !exporting && durationMs > 0,
                    ) { Text("設為終點") }
                    TextButton(onClick = { player.seekTo(startMs); player.play() }, enabled = !exporting) { Text("播放範圍") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("去除聲音")
                        Text(
                            "匯出的 MP4 不含音軌（預覽也會靜音）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = removeAudio, onCheckedChange = { removeAudio = it }, enabled = !exporting)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::screenshot, enabled = !exporting) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  截圖")
                    }
                    Button(onClick = ::export, enabled = !exporting && durationMs > 0 && endMs - startMs >= MIN_CLIP_MS) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  匯出 MP4")
                    }
                }
                exportProgress?.let { p ->
                    LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("轉檔中 $p%", modifier = Modifier.weight(1f))
                        TextButton(onClick = { exporter.cancel(); exportProgress = null }) { Text("取消") }
                    }
                }
                Text(
                    "匯出為 H.264 MP4（相容性最好），存到 Movies/${DashcamConfig.LOCAL_DIR}；截圖存到 Pictures/${DashcamConfig.LOCAL_DIR}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MIN_CLIP_MS = 500L

/** 00:12.3 */
private fun clock(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d.%d", t / 60000, t / 1000 % 60, t / 100 % 10)
}

/** 檔名用：0m12s3 */
private fun nameTime(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%dm%02ds%d", t / 60000, t / 1000 % 60, t / 100 % 10)
}
