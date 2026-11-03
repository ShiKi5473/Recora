package tw.com.recora.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import tw.com.recora.AppContainer

/**
 * 串流播放、即時畫面這類長時間用記錄器的畫面：在前景（STARTED）時向 [tw.com.recora.data.net.DashcamAccess]
 * 取得使用權（暫停下載、等它停下來），App 進背景或離開畫面時歸還，下載從中斷處繼續。
 *
 * 回傳的值 = 目前是否持有使用權；拿到之前不要開始讀記錄器上的檔案。
 * [enabled] = false 時不取得（例如改播手機上的檔案）；[files] = 也要 Astro 的檔案模式。
 * 有下載因此暫停時呼叫 [onPausedDownloads]（提示使用者）。
 */
@Composable
fun rememberDashcamGrant(
    container: AppContainer,
    reason: String,
    enabled: Boolean = true,
    files: Boolean = false,
    onPausedDownloads: () -> Unit = {},
): State<Boolean> {
    val granted = remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onPaused by rememberUpdatedState(onPausedDownloads)
    LaunchedEffect(enabled, lifecycle) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val grant = container.access.acquire(reason, files)
            try {
                if (grant.pausedDownloads) onPaused()
                granted.value = true
                awaitCancellation()
            } finally {
                granted.value = false
                grant.close()
            }
        }
    }
    return granted
}
