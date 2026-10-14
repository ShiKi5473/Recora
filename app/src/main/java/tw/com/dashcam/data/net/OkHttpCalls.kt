package tw.com.dashcam.data.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 可取消的 [Call.execute]：協程被取消（例如 withTimeout 逾時）時馬上 [Call.cancel] 切掉連線。
 * 阻塞的 execute() 不會理會協程取消，會一直卡到 OkHttp 自己的逾時。
 * 回傳的 [Response] 要由呼叫端關閉（`use`）。
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // 已經被取消：沒人會收，直接關掉
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
