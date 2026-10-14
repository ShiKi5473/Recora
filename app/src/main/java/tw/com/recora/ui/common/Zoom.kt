package tw.com.recora.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * 檢視影片 / 照片時的縮放與平移狀態。以內容中心為軸心：
 * 畫面上的點 = 內容座標（相對中心）× [scale] + [offset]。
 */
@Stable
class ZoomState(private val maxScale: Float = 5f) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** 手勢區域的大小（由 [zoomGestures] 更新）。 */
    internal var layoutSize = IntSize.Zero

    /** 被縮放內容的大小，用來限制平移範圍；預設等於手勢區域。 */
    var contentSize: () -> IntSize = { layoutSize }

    val isZoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** 以 [centroid]（手勢區域座標）為中心縮放 [zoom] 倍，再平移 [pan]。 */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(1f, maxScale)
        val c = centroid - Offset(layoutSize.width / 2f, layoutSize.height / 2f)
        // 讓手指底下的那一點在縮放後仍留在手指底下
        val raw = c - (c - offset) * (newScale / scale) + pan
        scale = newScale
        offset = clamp(raw)
        if (!isZoomed) reset()
    }

    /**
     * 畫面上看得到的那一部分，以內容的比例表示（0..1，左上為原點；沒放大時是整張）。
     * 內容會被裁在原本的範圍內，所以看得到的寬高 = 原本的 1 / [scale]。
     */
    fun visibleRect(): Rect {
        val size = contentSize()
        if (!isZoomed || size.width <= 0 || size.height <= 0) return Rect(0f, 0f, 1f, 1f)
        val half = 0.5f / scale
        val cx = 0.5f - offset.x / (scale * size.width)
        val cy = 0.5f - offset.y / (scale * size.height)
        return Rect(
            (cx - half).coerceIn(0f, 1f), (cy - half).coerceIn(0f, 1f),
            (cx + half).coerceIn(0f, 1f), (cy + half).coerceIn(0f, 1f),
        )
    }

    /** 雙擊：沒放大就以 [at] 為中心放大，已放大就還原。 */
    fun toggle(at: Offset, target: Float = 2.5f) {
        if (isZoomed) reset() else transform(at, Offset.Zero, target)
    }

    /** 不讓內容被拖出畫面：最多平移到內容邊緣貼齊原本的邊。 */
    private fun clamp(o: Offset): Offset {
        val size = contentSize()
        val maxX = (scale - 1) * size.width / 2f
        val maxY = (scale - 1) * size.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
}

/**
 * 雙指縮放；放大後單指拖曳平移。沒放大時單指手勢不處理，留給外層（例如左右滑動切換）。
 * 處理過的手勢會被消耗，外層的 Pager / 播放器點擊就不會再反應。
 * [pass] 用 Initial 可以搶在子 View（例如 AndroidView 裡的 PlayerView）之前處理。
 * [panExcluded]：從這些位置開始的單指拖曳不拿來平移（例如播放器下方的進度條）。
 */
fun Modifier.zoomGestures(
    state: ZoomState,
    pass: PointerEventPass = PointerEventPass.Main,
    panExcluded: (position: Offset, size: IntSize) -> Boolean = { _, _ -> false },
): Modifier = this
    .onSizeChanged { state.layoutSize = it }
    .pointerInput(state, pass) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
            val canPan = !panExcluded(down.position, size)
            do {
                val event = awaitPointerEvent(pass)
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2 || (fingers == 1 && state.isZoomed && canPan)) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) {
                        state.transform(event.calculateCentroid(useCurrent = true), pan, zoom)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
