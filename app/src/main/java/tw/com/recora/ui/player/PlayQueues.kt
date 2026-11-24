package tw.com.recora.ui.player

import tw.com.recora.data.PlayItem

/**
 * 開播放畫面時帶過去的播放清單（左右滑動切換用）。清單可能是整個資料夾，不放進導覽參數：
 * 來源頁面先 [put] 拿到編號放進 [tw.com.recora.ui.PlayerRoute.queue]，播放畫面建立 [PlayerSession] 時 [take] 取走，
 * 之後清單跟著那個播放畫面（去剪輯再回來也還在）。
 * App 被系統關掉再回來時這裡是空的，播放畫面就只播那一段。
 */
class PlayQueues {
    private val pending = LinkedHashMap<Long, List<PlayItem>>()
    private var nextId = 1L

    /** 登記一個清單，回傳編號（從 1 開始）。 */
    @Synchronized
    fun put(items: List<PlayItem>): Long {
        val id = nextId++
        pending[id] = items
        // 沒被取走的（例如導覽被打斷）不要一直留著
        while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
        return id
    }

    /** 取走編號 [id] 的清單；沒有（0、已取走、App 重開過）回 null。 */
    @Synchronized
    fun take(id: Long): List<PlayItem>? = pending.remove(id)

    private companion object {
        const val MAX_PENDING = 4
    }
}
