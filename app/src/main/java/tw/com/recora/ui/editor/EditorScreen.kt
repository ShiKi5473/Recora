package tw.com.recora.ui.editor

import android.net.Uri
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.animation.animateContentSize
import tw.com.recora.ui.theme.tabular
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.height
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
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
import kotlinx.coroutines.withTimeoutOrNull
import tw.com.recora.AppContainer
import tw.com.recora.BuildConfig
import tw.com.recora.data.AppSettings
import tw.com.recora.data.Camera
import tw.com.recora.data.EditPlan
import tw.com.recora.data.ExportCodec
import tw.com.recora.data.FileNames
import tw.com.recora.data.LocalLibrary
import tw.com.recora.data.Segment
import tw.com.recora.ui.Formats
import tw.com.recora.ui.common.captureFrame
import tw.com.recora.ui.player.dashcamPlayer
import tw.com.recora.ui.player.playbackErrorMessage
import tw.com.recora.ui.player.probeDurationMs
import tw.com.recora.ui.settings.ExportSettingsFields
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 剪輯：預覽並選取範圍、去除聲音、截圖，匯出成 MP4 存到 Movies/Recora
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
    // 播放器讀到（或換了）影音軌時加一，預估檔案大小跟著重算
    var formatVersion by remember { mutableIntStateOf(0) }
    var sourceBitrate by remember { mutableStateOf<Int?>(null) }
    var rangeBytes by remember { mutableStateOf<SourceRangeBytes.Bytes?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                scope.launch { snackbar.showSnackbar(playbackErrorMessage(error, streaming = false)) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                formatVersion++
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                formatVersion++
            }
        }
        player.addListener(listener)
        onDispose {
            // 匯出中的協程隨這個畫面的 scope 一起取消，暫存檔由 ClipExporter 刪除
            player.removeListener(listener)
            player.release()
        }
    }
    // 退到背景時暫停預覽（否則聲音會一直播）；匯出由 Transformer 處理，不受影響
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(removeAudio) { player.volume = if (removeAudio) 0f else 1f }

    /** 組合時間軸上的目前位置 = 這一段的起點 + 段內位置。 */
    fun combinedPosition(): Long =
        EditPlan.offsets(segments.map { it.durationMs }).getOrElse(player.currentMediaItemIndex) { 0L } + player.currentPosition

    fun seekCombined(ms: Long) {
        val (index, local) = EditPlan.locate(segments.map { it.durationMs }, ms)
        player.seekTo(index, local)
    }

    // 更新目前位置；播放中經過選取範圍終點時停下。
    // 只看「經過」：本來就在終點之後（例如接上後一段、往後看終點之後的畫面）按播放就照常播，不會被拉回終點
    LaunchedEffect(player) {
        var lastMs = 0L
        while (true) {
            if (segments.size == 1 && segments[0].durationMs <= 0 && player.duration > 0) {
                segments = listOf(segments[0].copy(durationMs = player.duration))
                range = 0f..player.duration.toFloat()
            }
            positionMs = combinedPosition()
            val end = range.endInclusive.toLong()
            if (player.isPlaying && segments.sumOf { it.durationMs } > 0 && lastMs < end && positionMs >= end) {
                player.pause()
                seekCombined(end)
            }
            lastMs = positionMs
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
    // 來源 TS 的平均位元率：不重新編碼時預估檔案大小用
    LaunchedEffect(segments) {
        val bytes = withContext(Dispatchers.IO) {
            segments.sumOf { s -> runCatching { container.sink.currentSize(Uri.parse(s.uri)) }.getOrDefault(0L) }
        }
        sourceBitrate = ExportOptions.averageBitrate(bytes, segments.sumOf { it.durationMs })
    }

    val startMs = range.start.roundToLong()
    val endMs = range.endInclusive.roundToLong()
    // 量選取範圍內來源的實際資料量（拖曳範圍時等停下來才量；量好之前用整支檔案的平均估）：
    // 不重新編碼時就是要複製的資料；MP4 重新編碼時用它的位元率估畫面複雜度（見 ExportOptions.complexityBitrate）
    val copyMode = effectiveCodec(settings.exportCodec) == ExportCodec.Original
    val complexitySource = FileNames.extension(segments.first().title) == "MP4"
    LaunchedEffect(copyMode, segments, startMs, endMs) {
        rangeBytes = null
        // debug build 一律量：校正時用原檔這一段的位元率分析編碼器超量多少
        if (!(copyMode || complexitySource || BuildConfig.DEBUG) || endMs <= startMs) return@LaunchedEffect
        delay(RANGE_MEASURE_DELAY_MS)
        val parts = EditPlan.pieces(segments.map { it.durationMs }, startMs, endMs).map { p ->
            SourceRangeBytes.measure(context, segments[p.segment].uri, p.startMs, p.endMs) ?: return@LaunchedEffect
        }
        rangeBytes = SourceRangeBytes.Bytes(parts.sumOf { it.video }, parts.sumOf { it.audio })
    }
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
            // 終點原本在時間軸最後面（沒剪掉結尾）就延伸到新一段的結尾，播放範圍才會接著播下去
            val oldTotal = segments.sumOf { it.durationMs }
            segments = segments + segment
            player.addMediaItem(MediaItem.fromUri(segment.uri))
            if (range.endInclusive.roundToLong() >= oldTotal) range = range.start..(oldTotal + duration).toFloat()
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

    /**
     * 依匯出設定 [s] 算出輸出的規格；讀不到來源的解析度、影格率時以 1920×1080、30 fps 計算。
     * 不重新編碼時解析度跟原檔一樣，不看解析度設定。
     */
    fun outputSpec(s: AppSettings): OutputSpec {
        val codec = effectiveCodec(s.exportCodec)
        val video = player.videoSize
        val srcW = video.width.takeIf { it > 0 } ?: 1920
        val srcH = video.height.takeIf { it > 0 } ?: 1080
        val scaledHeight = if (codec == ExportCodec.Original) null else ExportOptions.outputHeight(srcH, s.exportResolution)
        val outW = if (scaledHeight != null) srcW * scaledHeight / srcH else srcW
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0 } ?: 30f
        return OutputSpec(outW, scaledHeight ?: srcH, scaledHeight, fps, codec)
    }

    /** 依「App 設定」的匯出設定組出請求。 */
    fun exportRequest(pieces: List<ExportPiece>): ExportRequest {
        val s = container.settings.state.value
        val spec = outputSpec(s)
        return ExportRequest(
            pieces = pieces,
            removeAudio = removeAudio,
            videoMimeType = when (spec.codec) {
                ExportCodec.H264 -> MimeTypes.VIDEO_H264
                ExportCodec.H265 -> MimeTypes.VIDEO_H265
                ExportCodec.Original -> null
            },
            outputHeight = spec.scaledHeight,
            bitrate = ExportOptions.bitrate(spec.width, spec.height, spec.fps, spec.codec, s.exportQuality),
        )
    }

    /** 要求編碼器的影像位元率（見 [ExportOptions.videoBitrate]）；不重新編碼時是來源的平均位元率。 */
    fun requestedVideoBitrate(s: AppSettings, spec: OutputSpec): Int =
        ExportOptions.videoBitrate(spec.width, spec.height, spec.fps, spec.codec, s.exportQuality, sourceBitrate)

    /** 原檔這一段的影像位元率；還沒量好時用整支檔案的平均，都沒有回 null。 */
    fun sourceRangeBitrate(): Int? =
        rangeBytes?.let { ExportOptions.averageBitrate(it.video, endMs - startMs) } ?: sourceBitrate

    /** 重新編碼 MP4（Astro 的 H.264）時用畫面複雜度預估（見 [ExportOptions.complexityBitrate]）。 */
    fun usesComplexity(spec: OutputSpec): Boolean =
        complexitySource && spec.codec != ExportCodec.Original && sourceRangeBitrate() != null

    /** 預估用的影像位元率：要求的位元率，或依畫面複雜度預估的實際輸出。 */
    fun videoBitrate(s: AppSettings, spec: OutputSpec): Int {
        val requested = requestedVideoBitrate(s, spec)
        val source = sourceRangeBitrate()
        if (!usesComplexity(spec) || source == null) return requested
        val video = player.videoSize
        val pixelRatio = if (video.width > 0 && video.height > 0) spec.width.toDouble() * spec.height / (video.width * video.height) else 1.0
        return ExportOptions.complexityBitrate(requested, source, spec.codec, pixelRatio)
    }

    /** 預估倍數的 key（見 [ExportOptions.factorKey]）；來源容器看第一段的副檔名（接起來的段都是同一台記錄器）。 */
    fun factorKey(s: AppSettings, spec: OutputSpec): String =
        ExportOptions.factorKey(spec.codec, spec.height, s.exportQuality, FileNames.extension(segments.first().title), usesComplexity(spec))

    /** 匯出檔的聲音位元率；去除聲音或來源沒有聲音（例如後鏡頭）時是 0。 */
    fun audioBitrate(): Int {
        val audio = player.audioFormat
        return if (removeAudio || audio == null) 0 else audio.bitrate.takeIf { it > 0 } ?: ExportOptions.DEFAULT_AUDIO_BITRATE
    }

    /** 預估匯出一支的檔案大小（目前的範圍與匯出設定），用之前匯出學到的編碼器倍數校正。 */
    fun estimatedBytes(s: AppSettings): Long {
        val spec = outputSpec(s)
        val measured = rangeBytes
        if (spec.codec == ExportCodec.Original && measured != null) {
            return ExportOptions.estimatedCopyBytes(measured.video, measured.audio, removeAudio)
        }
        val factor = ExportOptions.lookupFactor(s.exportBitrateFactors, factorKey(s, spec))
        return ExportOptions.estimatedBytes(endMs - startMs, videoBitrate(s, spec), audioBitrate(), factor ?: 1f)
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
        // 匯出完用實際檔案大小校正預估（只看第一支：另一個鏡頭的解析度可能不同）
        val spec = outputSpec(container.settings.state.value)
        val requestedVideoBps = requestedVideoBitrate(container.settings.state.value, spec)
        val predictedVideoBps = videoBitrate(container.settings.state.value, spec)
        val complexity = usesComplexity(spec)
        val factorKey = factorKey(container.settings.state.value, spec)
        val audioBps = audioBitrate()
        val clipMs = endMs - startMs
        val estimate = estimatedBytes(container.settings.state.value)
        val startedAt = SystemClock.elapsedRealtime()
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
                    if (i == 0) {
                        val measuredFactor = ExportOptions.measuredFactor(file.length(), clipMs, predictedVideoBps, audioBps)
                        // debug build：校正預估用，腳本從 logcat 收集（adb logcat -s RecoraExport）
                        if (BuildConfig.DEBUG) {
                            Log.i(
                                "RecoraExport",
                                "name=$name codec=${spec.codec} size=${spec.width}x${spec.height} " +
                                    "quality=${container.settings.state.value.exportQuality} mute=$removeAudio segments=${segments.size} " +
                                    "clipMs=$clipMs estimate=$estimate srcVideoBytes=${rangeBytes?.video} srcAudioBytes=${rangeBytes?.audio} actual=${file.length()} videoBps=$requestedVideoBps predictedBps=$predictedVideoBps " +
                                    "audioBps=$audioBps factorKey=$factorKey factorBefore=${container.settings.state.value.exportBitrateFactors[factorKey]} " +
                                    "measured=$measuredFactor tookMs=${SystemClock.elapsedRealtime() - startedAt}",
                            )
                        }
                        measuredFactor?.let { measured ->
                            container.appScope.launch {
                                container.settings.update {
                                    val old = it.exportBitrateFactors[factorKey]
                                    val merged = if (complexity) ExportOptions.mergeCorrection(old, measured) else ExportOptions.mergeFactor(old, measured)
                                    it.copy(exportBitrateFactors = it.exportBitrateFactors + (factorKey to merged))
                                }
                            }
                        }
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
            // debug：校正腳本等這一行（存檔完成後）才送下一支，太早關掉這個畫面會取消存檔
            if (BuildConfig.DEBUG) Log.i("RecoraExport", "done saved=${saved.size} failure=$failure")
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

    // debug：校正腳本指定的工作（見 MainActivity.handleCalibrationIntent）。設好範圍與去除聲音，
    // 等播放器讀出規格、量完原檔這一段，再照手動按「匯出」的流程走，預估與量測才跟一般使用一樣
    val calibration = remember { container.calibrationJob.value?.takeIf { BuildConfig.DEBUG && it.uri == uri } }
    val currentExport by rememberUpdatedState<() -> Unit> { export() }
    if (calibration != null) {
        LaunchedEffect(calibration) {
            container.calibrationJob.value = null
            val ready = withTimeoutOrNull(CALIBRATION_TIMEOUT_MS) {
                while (segments.sumOf { it.durationMs } <= 0) delay(100)
                val total = segments.sumOf { it.durationMs }
                val start = calibration.startMs.coerceIn(0L, total)
                removeAudio = calibration.removeAudio
                range = start.toFloat()..(start + calibration.lengthMs).coerceAtMost(total).toFloat()
                rangeBytes = null
                delay(RANGE_MEASURE_DELAY_MS) // 整支範圍的量測被取消之後才開始等
                while (rangeBytes == null || sourceBitrate == null || player.videoSize.width <= 0) delay(100)
            }
            if (ready == null) {
                Log.i("RecoraExport", "done saved=0 failure=逾時：讀不到 ${calibration.title} 的長度、規格或原檔資料量")
            } else {
                currentExport()
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
            Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("目前 ${clock(positionMs)} / ${clock(totalMs)}", style = MaterialTheme.typography.bodyMedium.tabular())
                if (totalMs > 0) {
                    // 時間軸底下墊一排影格；取不到時隱藏，滑桿維持原樣
                    val strip = rememberFilmstrip(segments)
                    Box(Modifier.fillMaxWidth()) {
                        if (!strip.failed) {
                            Filmstrip(
                                strip,
                                startFraction = range.start / totalMs,
                                endFraction = range.endInclusive / totalMs,
                                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 10.dp)
                                    .height(44.dp).clip(MaterialTheme.shapes.small),
                            )
                        }
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
                        colors = if (strip.failed) {
                            SliderDefaults.colors()
                        } else {
                            SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent)
                        },
                    )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("起點" to startMs, "終點" to endMs, "長度" to endMs - startMs).forEach { (label, ms) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(clock(ms), style = MaterialTheme.typography.titleMedium.tabular())
                        }
                    }
                }
                if (totalMs > 0) {
                    val estimate = remember(formatVersion, sourceBitrate, rangeBytes, startMs, endMs, removeAudio, settings) { estimatedBytes(settings) }
                    val another = alsoOtherCamera && counterpartUris != null
                    Text(
                        "預估檔案大小 約 ${Formats.size(estimate)}" +
                            (if (another) "（另一個鏡頭的那支另計）" else "") +
                            "，實際大小依畫面變化多寡而定",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            effectiveCodec(settings.exportCodec).let { codec ->
                                if (codec == ExportCodec.Original) {
                                    "不重新編碼（畫質、解析度同原檔）"
                                } else {
                                    "${codec.label}・${settings.exportResolution.label}・${settings.exportQuality.label}畫質"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showExportOptions = true }, enabled = !exporting) { Text("變更") }
                }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::screenshot, enabled = !exporting) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("截圖")
                    }
                    Button(
                        onClick = ::export,
                        enabled = !exporting && totalMs > 0 && endMs - startMs >= MIN_CLIP_MS,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("匯出 MP4")
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
                    "匯出的 MP4 存到 Movies/${settings.albumDir}；截圖存到 Pictures/${settings.albumDir}。",
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

/** 匯出的輸出規格；[scaledHeight] = null 表示維持原始解析度（不縮放）。 */
private data class OutputSpec(val width: Int, val height: Int, val scaledHeight: Int?, val fps: Float, val codec: ExportCodec)

private const val MIN_CLIP_MS = 500L

/** 範圍停下來多久才量來源的資料量（TS 要掃檔，拖曳中不量）。 */
private const val RANGE_MEASURE_DELAY_MS = 500L

/** debug 校正：開剪輯畫面後等播放器與原檔資料量的上限。 */
private const val CALIBRATION_TIMEOUT_MS = 60_000L

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
