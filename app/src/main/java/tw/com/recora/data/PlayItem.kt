package tw.com.recora.data

/** 播放清單的一個項目：本機 content:// 或記錄器的 http 網址。 */
data class PlayItem(
    val url: String,
    val title: String,
    /** 記錄器上的 SD 卡路徑（用來從記錄器刪除）；本機來源不知道路徑時為 null。 */
    val sdPath: String? = null,
) {
    /** 手機上（相簿）的檔案（content://），不是記錄器上的串流。 */
    val isLocal: Boolean get() = url.startsWith("content://")

    /** 直接從記錄器串流（http://）：要先取得記錄器的使用權。 */
    val isStreaming: Boolean get() = url.startsWith("http")

    /** 刪不刪得掉：本機檔一定可以，記錄器上的要知道 SD 卡路徑。 */
    val isDeletable: Boolean get() = isLocal || sdPath != null
}

/** 播放清單的順序。各頁的清單都是新到舊排列（同畫面），所以「錄影時間的下一段」不是 index + 1。 */
object PlayOrder {
    /**
     * 錄影時間緊接在 [index] 之後的那一段：同鏡頭（看不出鏡頭的不限）、開始時間最早的。
     * 目前這段讀不出錄影時間時，退回清單裡的前一項（較新的一段）；沒有下一段回 null。
     */
    fun nextInTime(items: List<PlayItem>, index: Int): Int? = neighbourInTime(items, index, later = true)

    /**
     * 錄影時間緊接在 [index] 之前的那一段：同鏡頭（看不出鏡頭的不限）、開始時間最晚的。
     * 目前這段讀不出錄影時間時，退回清單裡的後一項（較舊的一段）；沒有上一段回 null。
     */
    fun previousInTime(items: List<PlayItem>, index: Int): Int? = neighbourInTime(items, index, later = false)

    private fun neighbourInTime(items: List<PlayItem>, index: Int, later: Boolean): Int? {
        val current = items.getOrNull(index) ?: return null
        val start = LocalLibrary.recordingStart(current.title)
            ?: return (if (later) index - 1 else index + 1).takeIf { it in items.indices }
        val camera = FileNames.cameraOfName(current.title)
        val candidates = items.indices
            .mapNotNull { i ->
                val time = LocalLibrary.recordingStart(items[i].title) ?: return@mapNotNull null
                val cam = FileNames.cameraOfName(items[i].title)
                val onOtherSide = if (later) time > start else time < start
                if (i == index || !onOtherSide || (camera != null && cam != null && cam != camera)) {
                    null
                } else {
                    i to time
                }
            }
        return (if (later) candidates.minByOrNull { it.second } else candidates.maxByOrNull { it.second })?.first
    }
}
