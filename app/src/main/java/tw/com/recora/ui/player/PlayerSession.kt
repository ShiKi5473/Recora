package tw.com.recora.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import tw.com.recora.data.PlayItem

/**
 * 播放畫面的清單與目前位置。放在 ViewModel 裡跟著導覽的返回堆疊保存：
 * 從播放畫面去剪輯再回來，畫面會重新組合，但仍停在剛才滑到的那一段、那個時間點，不會回到最初開啟的那段。
 * （播放器本身不放這裡：離開畫面時要釋放解碼器、讓出記錄器的連線。）
 */
class PlayerSession(queue: List<PlayItem>, index: Int) : ViewModel() {
    var queue by mutableStateOf(queue)
    var index by mutableIntStateOf(index)

    /** 正在播的檔案：清單裡的那一段，或換成了另一個鏡頭、手機上的檔案。 */
    var current by mutableStateOf(queue[index])

    /** 切到了另一個鏡頭：之後左右切換也盡量播另一個鏡頭。 */
    var otherCamera by mutableStateOf(false)

    /** 離開畫面時的播放位置與是否在播，回來時從這裡繼續。 */
    var resumePositionMs = 0L
    var resumePlaying = true
}
