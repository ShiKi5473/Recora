package tw.com.mufudashcam.data

/** 播放清單的一個項目：本機 content:// 或記錄器的 http 網址。 */
data class PlayItem(
    val url: String,
    val title: String,
    /** 記錄器上的 SD 卡路徑（用來從記錄器刪除）；本機來源不知道路徑時為 null。 */
    val sdPath: String? = null,
)
