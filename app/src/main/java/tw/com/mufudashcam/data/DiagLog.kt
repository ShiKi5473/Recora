package tw.com.mufudashcam.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * App 內的診斷紀錄（連線過程、記錄器的請求與回應）。
 * 「裝置」頁會顯示並可複製，沒有接電腦時用來查記錄器的實際行為。
 */
object DiagLog {
    private const val TAG = "MufuDiag"
    private const val MAX_LINES = 150
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(message: String) {
        Log.i(TAG, message)
        val line = "${LocalTime.now().format(TIME)} $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    /** 把回應壓成一行、截斷，方便放進紀錄。 */
    fun compact(body: String, max: Int = 160): String {
        val one = body.trim().lines().filter { it.isNotBlank() }.joinToString(" | ") { it.trim() }
        return if (one.length > max) one.take(max) + "…" else one
    }
}
