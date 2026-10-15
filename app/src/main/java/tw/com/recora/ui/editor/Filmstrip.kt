package tw.com.recora.ui.editor

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tw.com.recora.data.EditPlan
import tw.com.recora.data.Segment

/** 影格條的畫面：[frames] 取不到的格是 null（留空）；[failed] = 整條都取不到，畫面應該隱藏。 */
class FilmstripState(val frames: List<ImageBitmap?>, val failed: Boolean)

/**
 * 在合併後的時間軸（[segments] 依序接起來）上平均取 [count] 格縮圖，離開畫面就取消。
 * 用 [MediaMetadataRetriever] 逐格取最近的關鍵格（實測 TS 每格約 30–240 ms、MP4 約 50 ms），
 * 取不到時（例如解碼器被播放器占住）[FilmstripState.failed] 為 true，剪輯本身不受影響。
 */
@Composable
fun rememberFilmstrip(segments: List<Segment>, count: Int = FILMSTRIP_COUNT): FilmstripState {
    val context = LocalContext.current
    val sources = segments.map { it.uri to it.durationMs }
    var state by remember(sources) { mutableStateOf(FilmstripState(List(count) { null }, failed = false)) }
    LaunchedEffect(sources) {
        val durations = sources.map { it.second }
        val total = durations.sum()
        if (total <= 0) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            var retriever: MediaMetadataRetriever? = null
            var openIndex = -1
            try {
                val frames = arrayOfNulls<ImageBitmap>(count)
                for (i in 0 until count) {
                    ensureActive()
                    val (index, local) = EditPlan.locate(durations, total * (2 * i + 1) / (2 * count))
                    if (index != openIndex) {
                        retriever?.release()
                        retriever = MediaMetadataRetriever().also { it.setDataSource(context, Uri.parse(sources[index].first)) }
                        openIndex = index
                    }
                    frames[i] = retriever?.getScaledFrameAtTime(
                        local * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        FRAME_WIDTH,
                        FRAME_HEIGHT,
                    )?.asImageBitmap()
                    state = FilmstripState(frames.toList(), failed = false)
                }
                // 一格都沒取到：當作失敗，免得畫一條空的
                if (frames.all { it == null }) state = FilmstripState(state.frames, failed = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = FilmstripState(state.frames, failed = true)
            } finally {
                retriever?.release()
            }
        }
    }
    return state
}

/**
 * 一排縮圖，[startFraction]～[endFraction]（0～1）以外的部分壓暗，標出剪輯的範圍。
 * 疊在 RangeSlider 後面用，寬度要和滑桿的軌道對齊。
 */
@Composable
fun Filmstrip(state: FilmstripState, startFraction: Float, endFraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Row(Modifier.fillMaxSize()) {
            state.frames.forEach { frame ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (frame != null) {
                        Image(frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        val dim = Color.Black.copy(alpha = 0.55f)
        // 範圍外：左右兩塊壓暗（用 fillMaxWidth 的比例換算，免得量測寬度）
        Row(Modifier.fillMaxSize()) {
            if (startFraction > 0f) Box(Modifier.weight(startFraction).fillMaxHeight().background(dim))
            Box(Modifier.weight((endFraction - startFraction).coerceAtLeast(0.0001f)).fillMaxHeight())
            if (endFraction < 1f) Box(Modifier.weight(1f - endFraction).fillMaxHeight().background(dim))
        }
    }
}

private const val FILMSTRIP_COUNT = 10
private const val FRAME_WIDTH = 192
private const val FRAME_HEIGHT = 108
