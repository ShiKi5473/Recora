package tw.com.dashcam.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 依日期分組的清單（「檔案」頁、相簿）：每組 = 1 個日期標題 + n 項，LazyColumn 與 LazyVerticalGrid 的 item 都這樣排。
 */
object DateGroups {
    /** 第 [group] 組的日期標題在清單裡的位置。 */
    fun headerIndex(groupSizes: List<Int>, group: Int): Int = (0 until group).sumOf { groupSizes[it] + 1 }

    /** 頭尾之間全選：[ordered]（畫面順序）裡第一個與最後一個已選的之間，全部加進選取。 */
    fun selectRange(ordered: List<String>, selection: Set<String>): Set<String> {
        val first = ordered.indexOfFirst { it in selection }
        if (first < 0) return selection
        val last = ordered.indexOfLast { it in selection }
        return selection + ordered.subList(first, last + 1)
    }
}

/** 「跳到日期」：列出每一天與數量（[days] = 日期文字 to 數量，依畫面順序），選了就呼叫 [onPick]。少於兩天不顯示。 */
@Composable
fun DateJumpButton(days: List<Pair<String, Int>>, onPick: (group: Int) -> Unit) {
    if (days.size < 2) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.CalendarMonth, contentDescription = "跳到日期") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            days.forEachIndexed { i, (label, count) ->
                DropdownMenuItem(text = { Text("$label　$count") }, onClick = { open = false; onPick(i) })
            }
        }
    }
}
