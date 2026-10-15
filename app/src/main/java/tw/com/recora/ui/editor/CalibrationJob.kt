package tw.com.recora.ui.editor

/**
 * debug 用：校正腳本用 intent 指定的一次剪輯匯出（見 MainActivity.handleDebugIntent）。
 * 剪輯畫面設好範圍後走跟手動一樣的匯出流程，量測才有效；[id] 讓同一支影片連續兩次也是不同的工作。
 */
data class CalibrationJob(
    val id: Long,
    val uri: String,
    val title: String,
    val startMs: Long,
    val lengthMs: Long,
    val removeAudio: Boolean,
)
