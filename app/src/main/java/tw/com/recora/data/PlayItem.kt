package tw.com.recora.data

/** 播放清單的一個項目：本機 content:// 或記錄器的 http 網址。 */
data class PlayItem(
    val url: String,
    val title: String,
    /** 記錄器上的 SD 卡路徑（用來從記錄器刪除）；本機來源不知道路徑時為 null。 */
    val sdPath: String? = null,
)

/** 播放清單的順序。各頁的清單都是新到舊排列（同畫面），所以「錄影時間的下一段」不是 index + 1。 */
object PlayOrder {
    /**
     * 錄影時間緊接在 [index] 之後的那一段：同鏡頭（看不出鏡頭的不限）、開始時間最早的。
     * 目前這段讀不出錄影時間時，退回清單裡的前一項（較新的一段）；沒有下一段回 null。
     */
    fun nextInTime(items: List<PlayItem>, index: Int): Int? {
        val current = items.getOrNull(index) ?: return null
        val start = LocalLibrary.recordingStart(current.title) ?: return (index - 1).takeIf { it >= 0 }
        val camera = FileNames.cameraOfName(current.title)
        return items.indices
            .mapNotNull { i ->
                val time = LocalLibrary.recordingStart(items[i].title)
                val cam = FileNames.cameraOfName(items[i].title)
                if (i == index || time == null || time <= start || (camera != null && cam != null && cam != camera)) {
                    null
                } else {
                    i to time
                }
            }
            .minByOrNull { it.second }
            ?.first
    }
}
