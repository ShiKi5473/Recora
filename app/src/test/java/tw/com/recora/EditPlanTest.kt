package tw.com.recora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.recora.data.EditPlan
import tw.com.recora.data.EditPlan.Piece
import tw.com.recora.data.LocalLibrary
import java.time.LocalDateTime

class EditPlanTest {
    /** 兩段 3 分鐘 + 一段 1 分鐘 */
    private val durations = listOf(180_000L, 180_000L, 60_000L)

    @Test
    fun offsetsAndLocate() {
        assertEquals(listOf(0L, 180_000L, 360_000L), EditPlan.offsets(durations))
        assertEquals(0 to 10_000L, EditPlan.locate(durations, 10_000))
        assertEquals(1 to 0L, EditPlan.locate(durations, 180_000))
        assertEquals(2 to 5_000L, EditPlan.locate(durations, 365_000))
        // 超過結尾：最後一段的結尾
        assertEquals(2 to 60_000L, EditPlan.locate(durations, 999_000))
    }

    @Test
    fun piecesWithinOneSegment() {
        assertEquals(listOf(Piece(0, 10_000, 20_000)), EditPlan.pieces(durations, 10_000, 20_000))
    }

    @Test
    fun piecesAcrossSegments() {
        // 2:50 到 6:20：第一段剪尾巴、第二段整段、第三段剪開頭
        assertEquals(
            listOf(Piece(0, 170_000, null), Piece(1, 0, null), Piece(2, 0, 20_000)),
            EditPlan.pieces(durations, 170_000, 380_000),
        )
        // 範圍只跨進下一段幾毫秒：不帶那一小段
        assertEquals(listOf(Piece(0, 170_000, null)), EditPlan.pieces(durations, 170_000, 180_050))
    }

    @Test
    fun nameRangeIsRelativeToFirstSource() {
        // 檔名：第一段來源 + 2m50s0-3m20s0（可以超過該段長度）
        assertEquals(0 to (170_000L..200_000L), EditPlan.nameRange(durations, 170_000, 200_000))
        // 從第二段開始：相對於第二段
        assertEquals(1 to (10_000L..40_000L), EditPlan.nameRange(durations, 190_000, 220_000))
        assertNull(EditPlan.nameRange(durations, 10_000, 10_050))
        // 檔名格式可以正確解回來源與範圍
        assertEquals("FILE260923-131459F_2m50s0-3m20s0.mp4", LocalLibrary.editedName("FILE260923-131459F.TS", 170_000, 200_000, false))
    }

    @Test
    fun contiguousWithinTolerance() {
        val a = LocalDateTime.of(2026, 9, 23, 13, 14, 59)
        assertTrue(EditPlan.isContiguous(a, 180_000, LocalDateTime.of(2026, 9, 23, 13, 17, 59)))
        // 檔名時間只到秒，誤差幾秒內都算
        assertTrue(EditPlan.isContiguous(a, 179_400, LocalDateTime.of(2026, 9, 23, 13, 18, 1)))
        // 中間少了一段
        assertFalse(EditPlan.isContiguous(a, 180_000, LocalDateTime.of(2026, 9, 23, 13, 20, 59)))
    }

    @Test
    fun rangeFollowsJoinedSegments() {
        // 終點在結尾：延伸到新一段的結尾；剪掉了結尾就不動
        assertEquals(10_000f..240_000f, EditPlan.rangeAfterAppend(10_000f..180_000f, oldTotalMs = 180_000, addedMs = 60_000))
        assertEquals(10_000f..170_000f, EditPlan.rangeAfterAppend(10_000f..170_000f, oldTotalMs = 180_000, addedMs = 60_000))
        // 前面多一段：整個往後移
        assertEquals(70_000f..240_000f, EditPlan.rangeAfterPrepend(10_000f..180_000f, addedMs = 60_000))
    }

    @Test
    fun rangeAfterRemovingASegment() {
        // 拿掉前面 60 秒：往前移、夾在剩下的 180 秒裡
        assertEquals(0f..120_000f, EditPlan.rangeAfterRemove(30_000f..180_000f, 60_000, first = true, newTotalMs = 180_000, minClipMs = 500))
        // 拿掉後面：終點夾到新的結尾
        assertEquals(30_000f..180_000f, EditPlan.rangeAfterRemove(30_000f..220_000f, 60_000, first = false, newTotalMs = 180_000, minClipMs = 500))
        // 選取範圍整個在拿掉的那段裡：改成全選
        assertEquals(0f..180_000f, EditPlan.rangeAfterRemove(200_000f..230_000f, 60_000, first = false, newTotalMs = 180_000, minClipMs = 500))
    }
}
