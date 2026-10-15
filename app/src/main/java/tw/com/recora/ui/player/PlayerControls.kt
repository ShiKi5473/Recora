package tw.com.recora.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import tw.com.recora.ui.Formats
import tw.com.recora.ui.common.OverlayChip
import tw.com.recora.ui.theme.DarkScheme
import tw.com.recora.ui.theme.Scrim
import tw.com.recora.ui.theme.tabular

/** 黑底影片上的強調色：不論淺色或深色主題，都用深色主題的柔酒紅才夠亮。 */
private val OverlayAccent = DarkScheme.primary

/**
 * 播放器的底部控制列（取代 PlayerView 內建的控制列）：進度與時間、上一段／播放／下一段、全螢幕，
 * 上面一排是切換鏡頭、逐格（暫停時）、自動播下一段。
 *
 * 拖曳進度時 [onSeekPreview] 會一直呼叫（呼叫端負責節流），放開時呼叫 [onSeekFinished]。
 * [onTopChanged] 回報控制列頂端在畫面上的 Y（消失時回報 [Float.MAX_VALUE]），
 * 給左右滑動、縮放平移的手勢排除這個範圍，免得拖進度條被當成換段。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPrevious: Boolean,
    hasNext: Boolean,
    switchCameraLabel: String?,
    showAutoPlay: Boolean,
    autoPlayNext: Boolean,
    fullscreen: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    onStepFrame: (forward: Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onTopChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) { onDispose { onTopChanged(Float.MAX_VALUE) } }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(0L)
    val shown = scrub?.toLong() ?: positionMs.coerceIn(0L, duration.coerceAtLeast(positionMs))
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim.strong)))
            .onGloballyPositioned { onTopChanged(it.positionInRoot().y) }
            .padding(horizontal = 12.dp)
            .padding(top = 24.dp, bottom = 4.dp),
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (switchCameraLabel != null) OverlayChip(switchCameraLabel, Icons.Filled.Cameraswitch, onClick = onSwitchCamera)
            if (!isPlaying) {
                OverlayChip("上一格", Icons.AutoMirrored.Filled.KeyboardArrowLeft) { onStepFrame(false) }
                OverlayChip("下一格", Icons.AutoMirrored.Filled.KeyboardArrowRight) { onStepFrame(true) }
            }
            if (showAutoPlay) {
                OverlayChip("自動播下一段", Icons.AutoMirrored.Filled.PlaylistPlay, selected = autoPlayNext, onClick = onToggleAutoPlay)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Formats.duration(shown), color = Color.White, style = MaterialTheme.typography.labelMedium.tabular())
            Slider(
                value = shown.toFloat().coerceAtMost(duration.toFloat()),
                onValueChange = {
                    scrub = it
                    onSeekPreview(it.toLong())
                },
                onValueChangeFinished = {
                    scrub?.let { onSeekFinished(it.toLong()) }
                    scrub = null
                },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = OverlayAccent,
                    activeTrackColor = OverlayAccent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    disabledThumbColor = Color.White.copy(alpha = 0.4f),
                    disabledActiveTrackColor = Color.White.copy(alpha = 0.3f),
                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(Formats.duration(duration), color = Color.White, style = MaterialTheme.typography.labelMedium.tabular())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = if (hasPrevious) "上一段" else "回到開頭", tint = Color.White)
            }
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.padding(horizontal = 4.dp).size(52.dp).background(Color.White, CircleShape),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暫停" else "播放",
                    tint = Color.Black,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一段", tint = Color.White, modifier = Modifier.alpha(if (hasNext) 1f else 0.5f))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (fullscreen) "離開全螢幕" else "全螢幕",
                    tint = Color.White,
                )
            }
        }
    }
}
