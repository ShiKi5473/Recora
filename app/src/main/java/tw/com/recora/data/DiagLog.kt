package tw.com.recora.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * App 內的診斷紀錄（連線過程、記錄器的請求與回應）。
 * 「裝置」頁會顯示並可複製；[init] 之後也寫進檔案（每天一個，保留 [KEEP_DAYS] 天）：
 * 手機在車上連著記錄器的 Wi-Fi 時沒辦法接電腦，回來再用 adb pull 拿完整的紀錄
 * （這支手機的 logcat 只留得住幾秒，事後讀不到）。
 */
object DiagLog {
    private const val TAG = "RecoraDiag"
    private const val MAX_LINES = 150
    private const val KEEP_DAYS = 7L
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val FILE_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd")

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** 依序寫檔、不擋住呼叫端（常在主執行緒記錄）。 */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "DiagLog").apply { isDaemon = true } }

    @Volatile
    private var dir: File? = null

    /** 從現在起也寫進 [directory]（null = 外部儲存空間不能用，只留在記憶體），順便刪掉太舊的檔案。 */
    fun init(directory: File?, header: String) {
        dir = directory ?: return
        writer.execute {
            directory.mkdirs()
            val cutoff = LocalDate.now().minusDays(KEEP_DAYS)
            directory.listFiles()?.forEach { f ->
                val date = runCatching { LocalDate.parse(f.nameWithoutExtension, FILE_NAME) }.getOrNull()
                if (date != null && date < cutoff) f.delete()
            }
        }
        detail("===== $header =====")
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val line = "${LocalTime.now().format(TIME)} $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
        append(message)
    }

    /** 只寫進檔案、不顯示在「裝置」頁：記錄器回應的全文之類，車上實測後對照用。 */
    fun detail(message: String) = append(message)

    private fun append(message: String) {
        val d = dir ?: return
        val now = LocalDateTime.now()
        writer.execute {
            runCatching { File(d, "${now.format(FILE_NAME)}.txt").appendText("${now.format(FILE_TIME)} $message\n") }
        }
    }

    /** 把回應壓成一行、截斷，方便放進紀錄。 */
    fun compact(body: String, max: Int = 160): String {
        val one = body.trim().lines().filter { it.isNotBlank() }.joinToString(" | ") { it.trim() }
        return if (one.length > max) one.take(max) + "…" else one
    }
}
