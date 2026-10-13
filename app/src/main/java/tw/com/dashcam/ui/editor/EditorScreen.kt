package tw.com.dashcam.ui.editor

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.transformer.ExportException
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.com.dashcam.AppContainer
import tw.com.dashcam.data.Camera
import tw.com.dashcam.data.DashcamConfig
import tw.com.dashcam.data.EditPlan
import tw.com.dashcam.data.ExportCodec
import tw.com.dashcam.data.FileNames
import tw.com.dashcam.data.LocalLibrary
import tw.com.dashcam.data.Segment
import tw.com.dashcam.ui.Formats
import tw.com.dashcam.ui.common.captureFrame
import tw.com.dashcam.ui.player.dashcamPlayer
import tw.com.dashcam.ui.player.playbackErrorMessage
import tw.com.dashcam.ui.player.probeDurationMs
import tw.com.dashcam.ui.settings.ExportSettingsFields
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 剪輯：預覽並選取範圍、去除聲音、截圖，匯出成 MP4 存到 Movies/Dashcam
 * （編碼、解析度、畫質依「App 設定」）。只處理手機上的檔案（已下載或從相簿挑選）。
 * 事件跨到前後一段影片時，可以接上錄影時間緊接的片段一起剪；
 * 另一個鏡頭的同一段也已下載時，可以用同一個時間範圍一起匯出。
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val settings by container.settings.state.collectAsStateWithLifecycle()

    // 時間軸上的各段（依錄影時間接起來）；第一段的長度由預覽的播放器讀出
    var segments by remember { mutableStateOf(listOf(Segment(uri, title, 0L))) }
    val totalMs = segments.sumOf { it.durationMs }
    val player = remember {
        dashcamPlayer(context, container.http, streaming = false).apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    val exporter = remember { ClipExporter(context, container.http) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var range by remember { mutableStateOf(0f..0f) }
    var removeAudio by rememberSaveable { mutableStateOf(container.settings.state.value.exportMute) }
    var alsoOtherCamera by rememberSaveable { mutableStateOf(false) }
    // 每一段的另一個鏡頭檔都已下載時才有（同一時間範圍一起匯出）
    var counterpartUris by remember { mutableStateOf<List<String>?>(null) }
    var showExportOptions by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf<Int?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var addingSegment by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                scope.launch { snackbar.showSnackbar(playbackErrorMessage(error, streaming = false)) }
            }
        }
        player.addListener(listener)
        onDispose {
            // 匯出中的協程隨這個畫面的 scope 一起取消，暫存檔由 ClipExporter 刪除
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(removeAudio) { player.volume = if (removeAudio) 0f else 1f }

    /** 組合時間軸上的目前位置 = 這一段的起點 + 段內位置。 */
    fun combinedPosition(): Long =
        EditPlan.offsets(segments.map { it.durationMs }).getOrElse(player.currentMediaItemIndex) { 0L } + player.currentPosition

    fun seekCombined(ms: Long) {
        val (index, local) = EditPlan.locate(segments.map { it.durationMs }, ms)
        player.seekTo(index, local)
    }

    // 更新目前位置；播放到選取範圍終點時停下
    LaunchedEffect(player) {
        while (true) {
            if (segments.size == 1 && segments[0].durationMs <= 0 && player.duration > 0) {
                segments = listOf(segments[0].copy(durationMs = player.duration))
                range = 0f..player.duration.toFloat()
            }
            positionMs = combinedPosition()
            if (player.isPlaying && segments.sumOf { it.durationMs } > 0 && positionMs >= range.endInclusive.toLong()) {
                player.pause()
                seekCombined(range.endInclusive.toLong())
            }
            delay(100)
        }
    }
    LaunchedEffect(segments.map { it.title }) {
        val found = withContext(Dispatchers.IO) {
            segments.map { s ->
                FileNames.counterpartName(s.title)?.let { name ->
                    runCatching { container.sink.findVideo(name) }.getOrNull()?.toString()
                }
            }
        }
        counterpartUris = if (found.all { it != null }) found.filterNotNull() else null
    }

    val startMs = range.start.roundToLong()
    val endMs = range.endInclusive.roundToLong()
    val exporting = exportProgress != null
    // 跨段剪輯只用在記錄器的原始檔（看得出錄影時間）
    val canJoin = FileNames.parse(segments.first().title)?.dateTime != null

    // 匯出中離開會取消匯出，先確認
    BackHandler(enabled = exporting) { confirmLeave = true }

    fun screenshot() {
        val current = segments.getOrNull(player.currentMediaItemIndex)?.title ?: title
        captureFrame(container, playerView, player, current, scope) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    }

    /** 微調：暫停後把播放位置往前／後移，再用「設為起點／終點」。 */
    fun step(deltaMs: Long) {
        player.pause()
        val target = (combinedPosition() + deltaMs).coerceIn(0, segments.sumOf { it.durationMs })
        seekCombined(target)
        positionMs = target
    }

    /** 一格的長度；讀不到影格率時以 30 fps 計算。 */
    fun frameMs(): Long = (1000f / (player.videoFormat?.frameRate?.takeIf { it > 0 } ?: 30f)).roundToLong()

    // 拖曳範圍時的預覽：TS 每次 seek 都要二分搜尋讀檔，拖曳中限制頻率、只跳到關鍵幀，放開時再精確定位
    val scrub = remember { object { var start = true; var lastSeekAt = 0L } }

    /** 接上錄影時間緊接的前一段或後一段（手機上已下載的原始檔），回傳要顯示的訊息。 */
    suspend fun joinAdjacent(forward: Boolean): String {
        val edge = if (forward) segments.last() else segments.first()
        val which = if (forward) "後一段" else "前一段"
        val library = withContext(Dispatchers.IO) { runCatching { container.sink.listLibrary() }.getOrDefault(emptyList()) }
        val next = LocalLibrary.adjacent(library, edge.title, forward) ?: return "手機上沒有$which，要先從記錄器下載"
        val duration = probeDurationMs(context, container.http, next.uri) ?: return "讀不出 ${next.name} 的長度"
        val edgeStart = FileNames.parse(edge.title)?.dateTime
        val nextStart = FileNames.parse(next.name)?.dateTime
        val contiguous = edgeStart != null && nextStart != null && if (forward) {
            EditPlan.isContiguous(edgeStart, edge.durationMs, nextStart)
        } else {
            EditPlan.isContiguous(nextStart, duration, edgeStart)
        }
        if (!contiguous) return "${next.name} 跟目前的片段沒有接在一起（中間可能少了一段）"
        val segment = Segment(next.uri, next.name, duration)
        if (forward) {
            segments = segments + segment
            player.addMediaItem(MediaItem.fromUri(segment.uri))
        } else {
            segments = listOf(segment) + segments
            player.addMediaItem(0, MediaItem.fromUri(segment.uri))
            // 時間軸前面多了一段，選取範圍跟著往後移
            range = (range.start + duration)..(range.endInclusive + duration)
        }
        return "已接上$which ${next.name}"
    }

    fun addSegment(forward: Boolean) {
        if (addingSegment) return
        addingSegment = true
        scope.launch {
            val message = try {
                joinAdjacent(forward)
            } finally {
                addingSegment = false
            }
            snackbar.showSnackbar(message)
        }
    }

    /** 拿掉時間軸最前面（[first]）或最後面的一段。 */
    fun removeSegment(first: Boolean) {
        if (segments.size <= 1) return
        val removed = if (first) segments.first() else segments.last()
        segments = if (first) segments.drop(1) else segments.dropLast(1)
        player.removeMediaItem(if (first) 0 else player.mediaItemCount - 1)
        val total = segments.sumOf { it.durationMs }.toFloat()
        val shift = if (first) removed.durationMs.toFloat() else 0f
        val start = (range.start - shift).coerceIn(0f, total)
        val end = (range.endInclusive - shift).coerceIn(start, total)
        range = if (end - start >= MIN_CLIP_MS) start..end else 0f..total
    }

    /** 依「App 設定」的匯出設定組出請求。 */
    fun exportRequest(pieces: List<ExportPiece>): ExportRequest {
        val s = container.settings.state.value
        val codec = effectiveCodec(s.exportCodec)
        val video = player.videoSize
        val srcW = video.width.takeIf { it > 0 } ?: 1920
        val srcH = video.height.takeIf { it > 0 } ?: 1080
        val outH = ExportOptions.outputHeight(srcH, s.exportResolution)
        val outW = if (outH != null) srcW * outH / srcH else srcW
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0 } ?: 30f
        return ExportRequest(
            pieces = pieces,
            removeAudio = removeAudio,
            videoMimeType = if (codec == ExportCodec.H265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264,
            outputHeight = outH,
            bitrate = ExportOptions.bitrate(outW, outH ?: srcH, fps, codec, s.exportQuality),
        )
    }

    fun export() {
        val durations = segments.map { it.durationMs }
        val pieces = EditPlan.pieces(durations, startMs, endMs)
        val (firstSegment, nameRange) = EditPlan.nameRange(durations, startMs, endMs) ?: return
        // 要匯出的每一支：各段的來源（同一個分段規劃）與檔名用的來源名稱
        val outputs = buildList {
            add(segments.map { it.uri } to segments[firstSegment].title)
            val others = counterpartUris
            val otherTitle = FileNames.counterpartName(segments[firstSegment].title)
            if (alsoOtherCamera && others != null && otherTitle != null) add(others to otherTitle)
        }
        player.pause()
        exportProgress = 0
        exportJob = scope.launch {
            val saved = ArrayList<String>()
            var failure: String? = null
            try {
                for ((i, output) in outputs.withIndex()) {
                    val (uris, sourceTitle) = output
                    val name = LocalLibrary.editedName(sourceTitle, nameRange.first, nameRange.last, removeAudio)
                    val request = exportRequest(pieces.map { p -> ExportPiece(uris[p.segment], p.startMs.takeIf { it > 0 }, p.endMs) })
                    val file = try {
                        exporter.export(request) { p -> exportProgress = (i * 100 + p) / outputs.size }
                    } catch (e: ExportException) {
                        failure = "匯出失敗：${e.message}"
                        break
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            try {
                                container.sink.saveFile(file, name, "video/mp4", isImage = false)
                            } finally {
                                file.delete()
                            }
                        }
                        saved += name
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = "匯出檔案儲存失敗：${e.message}"
                        break
                    }
                }
            } finally {
                exportProgress = null
                exportJob = null
                confirmLeave = false
            }
            if (saved.isNotEmpty()) container.libraryChanged.tryEmit(Unit)
            val error = failure
            if (error != null) {
                snackbar.showSnackbar(if (saved.isEmpty()) error else "已匯出 ${saved.joinToString("、")}；$error")
            } else {
                val action = snackbar.showSnackbar(
                    "已匯出 ${saved.joinToString("、")}",
                    actionLabel = "到相簿查看",
                    duration = SnackbarDuration.Long,
                )
                if (action == SnackbarResult.ActionPerformed) onOpenLibrary()
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
                        Text(
                            if (segments.size > 1) "${segments.first().title} 起，共 ${segments.size} 段" else segments.first().title,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (exporting) confirmLeave = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
                Text("目前 ${clock(positionMs)} / ${clock(totalMs)}", style = MaterialTheme.typography.bodyMedium)
                if (totalMs > 0) {
                    RangeSlider(
                        value = range,
                        onValueChange = { new ->
                            val movedStart = new.start != range.start
                            range = new
                            scrub.start = movedStart
                            val now = SystemClock.uptimeMillis()
                            if (now - scrub.lastSeekAt >= SCRUB_SEEK_INTERVAL_MS) {
                                scrub.lastSeekAt = now
                                player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                                seekCombined((if (movedStart) new.start else new.endInclusive).toLong())
                            }
                        },
                        onValueChangeFinished = {
                            player.setSeekParameters(SeekParameters.EXACT)
                            seekCombined((if (scrub.start) range.start else range.endInclusive).toLong())
                        },
                        valueRange = 0f..totalMs.toFloat(),
                        enabled = !exporting,
                    )
                }
                Text(
                    "起點 ${clock(startMs)}　終點 ${clock(endMs)}　長度 ${clock(endMs - startMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val canStep = !exporting && totalMs > 0
                    listOf("−1 秒" to { -1000L }, "−1 格" to { -frameMs() }, "+1 格" to { frameMs() }, "+1 秒" to { 1000L })
                        .forEach { (label, delta) ->
                            FilledTonalButton(
                                onClick = { step(delta()) },
                                enabled = canStep,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text(label) }
                        }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { range = positionMs.coerceAtMost(endMs - MIN_CLIP_MS).coerceAtLeast(0).toFloat()..range.endInclusive },
                        enabled = !exporting && totalMs > 0,
                    ) { Text("設為起點") }
                    OutlinedButton(
                        onClick = { range = range.start..positionMs.coerceAtLeast(startMs + MIN_CLIP_MS).coerceAtMost(totalMs).toFloat() },
                        enabled = !exporting && totalMs > 0,
                    ) { Text("設為終點") }
                    TextButton(onClick = { seekCombined(startMs); player.play() }, enabled = !exporting) { Text("播放範圍") }
                }
                if (canJoin) {
                    Text(
                        "事件跨到前後一段時，可以接上手機上已下載、錄影時間接在一起的片段一起剪",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val canAdd = !exporting && !addingSegment && totalMs > 0
                        AssistChip(onClick = { addSegment(forward = false) }, label = { Text("＋前一段") }, enabled = canAdd)
                        segments.forEachIndexed { i, s ->
                            // 只有兩端的段可以拿掉
                            val removable = segments.size > 1 && (i == 0 || i == segments.lastIndex)
                            InputChip(
                                selected = false,
                                onClick = { if (removable) removeSegment(first = i == 0) },
                                label = { Text(segmentLabel(s)) },
                                trailingIcon = if (removable) {
                                    { Icon(Icons.Filled.Close, contentDescription = "拿掉這一段", modifier = Modifier.size(16.dp)) }
                                } else {
                                    null
                                },
                                enabled = !exporting,
                            )
                        }
                        AssistChip(onClick = { addSegment(forward = true) }, label = { Text("＋後一段") }, enabled = canAdd)
                    }
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
                if (counterpartUris != null) {
                    val other = if (FileNames.cameraOfName(segments.first().title) == Camera.Rear) "前鏡頭" else "後鏡頭"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("同時匯出$other")
                            Text(
                                "同一個時間範圍再匯出一支$other（用同樣的匯出設定）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = alsoOtherCamera, onCheckedChange = { alsoOtherCamera = it }, enabled = !exporting)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("匯出設定")
                        Text(
                            "${effectiveCodec(settings.exportCodec).label}・${settings.exportResolution.label}・" +
                                "${settings.exportQuality.label}畫質",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showExportOptions = true }, enabled = !exporting) { Text("變更") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::screenshot, enabled = !exporting) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  截圖")
                    }
                    Button(onClick = ::export, enabled = !exporting && totalMs > 0 && endMs - startMs >= MIN_CLIP_MS) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  匯出 MP4")
                    }
                }
                exportProgress?.let { p ->
                    LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("轉檔中 $p%", modifier = Modifier.weight(1f))
                        TextButton(onClick = { exportJob?.cancel() }) { Text("取消") }
                    }
                }
                Text(
                    "匯出的 MP4 存到 Movies/${DashcamConfig.LOCAL_DIR}；截圖存到 Pictures/${DashcamConfig.LOCAL_DIR}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showExportOptions) {
        AlertDialog(
            onDismissRequest = { showExportOptions = false },
            title = { Text("匯出設定") },
            text = {
                Column {
                    ExportSettingsFields(
                        settings = settings,
                        update = { transform -> container.appScope.launch { container.settings.update(transform) } },
                        showMuteDefault = false,
                    )
                    Text(
                        "改的是「App 設定」裡的值，之後的剪輯也會沿用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showExportOptions = false }) { Text("完成") } },
        )
    }

    if (confirmLeave && exporting) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("匯出中") },
            text = { Text("離開這個畫面會取消匯出。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    exportJob?.cancel()
                    onBack()
                }) { Text("取消匯出並離開", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("繼續匯出") } },
        )
    }
}

private const val MIN_CLIP_MS = 500L

/** 拖曳範圍時，預覽 seek 的最小間隔。 */
private const val SCRUB_SEEK_INTERVAL_MS = 150L

/** 手機沒有 H.265 編碼器時改用 H.264。 */
private fun effectiveCodec(codec: ExportCodec): ExportCodec =
    if (codec == ExportCodec.H265 && !ClipExporter.hevcEncoderAvailable) ExportCodec.H264 else codec

/** 時間軸上一段的標籤：錄影開始時間與長度，例如 18:44:36（3:00）。 */
private fun segmentLabel(s: Segment): String {
    val start = FileNames.parse(s.title)?.dateTime?.let { Formats.time(it) } ?: s.title
    return if (s.durationMs > 0) "$start（${Formats.duration(s.durationMs)}）" else start
}

/** 00:12.3 */
private fun clock(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d.%d", t / 60000, t / 1000 % 60, t / 100 % 10)
}
