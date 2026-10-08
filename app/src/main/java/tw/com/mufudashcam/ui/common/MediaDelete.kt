package tw.com.mufudashcam.ui.common

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.PlayItem
import java.io.IOException

/** 正在播放的這個檔案刪不刪得掉：本機檔一定可以，記錄器上的要知道 SD 卡路徑。 */
fun isDeletable(item: PlayItem): Boolean = isLocal(item) || item.sdPath != null

/** 本機（相簿）檔案，不是記錄器上的串流。 */
fun isLocal(item: PlayItem): Boolean = item.url.startsWith("content://")

/**
 * 播放器的刪除：本機檔（content://）從手機相簿刪除，記錄器上的（http://）送 action=del。
 * [onResult] 的 deleted 為 false 表示沒刪成或使用者在系統視窗取消，呼叫端可以接著恢復播放。
 */
@Composable
fun rememberMediaDeleter(
    container: AppContainer,
    onMessage: (String) -> Unit,
    onResult: (item: PlayItem, deleted: Boolean) -> Unit,
): (PlayItem) -> Unit {
    val scope = rememberCoroutineScope()
    // 正在等系統刪除確認視窗的檔案
    var awaiting by remember { mutableStateOf<PlayItem?>(null) }
    val systemConfirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val item = awaiting ?: return@rememberLauncherForActivityResult
        awaiting = null
        val ok = result.resultCode == Activity.RESULT_OK
        if (ok) {
            container.libraryChanged.tryEmit(Unit)
            onMessage("已刪除 ${item.title}")
        } else {
            onMessage("已取消刪除")
        }
        onResult(item, ok)
    }
    return { item ->
        scope.launch {
            when (val outcome = delete(container, item)) {
                is Outcome.Done -> {
                    onMessage(outcome.message)
                    onResult(item, true)
                }
                is Outcome.Failed -> {
                    onMessage(outcome.message)
                    onResult(item, false)
                }
                // 不是本 App 建立的相簿檔（例如重新安裝前下載的）：請系統確認
                is Outcome.NeedsConfirm -> {
                    awaiting = item
                    systemConfirm.launch(IntentSenderRequest.Builder(outcome.sender).build())
                }
            }
        }
    }
}

private sealed interface Outcome {
    data class Done(val message: String) : Outcome
    data class Failed(val message: String) : Outcome
    data class NeedsConfirm(val sender: IntentSender) : Outcome
}

private suspend fun delete(container: AppContainer, item: PlayItem): Outcome =
    if (isLocal(item)) deleteLocal(container, item) else deleteOnDashcam(container, item)

private suspend fun deleteLocal(container: AppContainer, item: PlayItem): Outcome {
    val uri = Uri.parse(item.url)
    return try {
        withContext(Dispatchers.IO) { container.sink.delete(uri) }
        container.libraryChanged.tryEmit(Unit)
        Outcome.Done("已從相簿刪除 ${item.title}")
    } catch (e: SecurityException) {
        val request = MediaStore.createDeleteRequest(container.app.contentResolver, listOf(uri))
        Outcome.NeedsConfirm(request.intentSender)
    }
}

private suspend fun deleteOnDashcam(container: AppContainer, item: PlayItem): Outcome {
    val sdPath = item.sdPath ?: return Outcome.Failed("不知道 ${item.title} 在記錄器上的位置")
    if (!container.connection.isConnected) return Outcome.Failed("沒有連線到記錄器")
    return try {
        val result = container.api.delete(sdPath)
        if (result.ok) {
            container.files.forget(listOf(sdPath))
            container.dashcamChanged.tryEmit(Unit)
            Outcome.Done("已從記錄器刪除 ${item.title}")
        } else {
            Outcome.Failed("刪除失敗：${result.body}")
        }
    } catch (e: IOException) {
        Outcome.Failed("刪除失敗：${e.message}")
    }
}
