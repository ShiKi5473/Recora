package tw.com.mufudashcam

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.com.mufudashcam.ui.common.DateGroups

class SelectionTest {
    @Test
    fun headerIndexCountsHeadersAndItems() {
        // 三天：2、3、1 個 → 標題在 0、3、7
        val sizes = listOf(2, 3, 1)
        assertEquals(0, DateGroups.headerIndex(sizes, 0))
        assertEquals(3, DateGroups.headerIndex(sizes, 1))
        assertEquals(7, DateGroups.headerIndex(sizes, 2))
    }

    @Test
    fun selectRangeFillsBetweenFirstAndLast() {
        val ordered = listOf("a", "b", "c", "d", "e")
        assertEquals(setOf("b", "c", "d"), DateGroups.selectRange(ordered, setOf("d", "b")))
        // 只選一個、或沒有選：不變
        assertEquals(setOf("c"), DateGroups.selectRange(ordered, setOf("c")))
        assertEquals(emptySet<String>(), DateGroups.selectRange(ordered, emptySet()))
        // 已選但不在畫面上的（例如被篩選掉）保留
        assertEquals(setOf("a", "b", "x"), DateGroups.selectRange(ordered, setOf("a", "b", "x")))
    }
}
