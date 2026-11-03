package tw.com.recora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.com.recora.data.PlayItem
import tw.com.recora.data.PlayOrder

class PlayOrderTest {
    private fun items(vararg names: String) = names.map { PlayItem("content://x/$it", it) }

    @Test
    fun nextIsLaterRecordingNotNextIndex() {
        // 畫面上新到舊
        val q = items("FILE260923-132059F.TS", "FILE260923-131759F.TS", "FILE260923-131459F.TS")
        assertEquals(1, PlayOrder.nextInTime(q, 2))
        assertEquals(0, PlayOrder.nextInTime(q, 1))
        assertNull(PlayOrder.nextInTime(q, 0))
    }

    @Test
    fun staysOnSameCamera() {
        val q = items(
            "FILE260923-131759F.TS", "FILE260923-131759R.TS",
            "FILE260923-131459F.TS", "FILE260923-131459R.TS",
        )
        assertEquals(1, PlayOrder.nextInTime(q, 3))
        assertEquals(0, PlayOrder.nextInTime(q, 2))
    }

    @Test
    fun crossesPrefixesAndEditedClips() {
        // 緊急錄影後面接一般錄影；剪輯檔用來源時間 + 片段起點
        val q = items("FILE260923-184736F.TS", "EMER260923-184436F_2m50s0-3m00s0.mp4", "EMER260923-184436F.TS")
        assertEquals(1, PlayOrder.nextInTime(q, 2))
        assertEquals(0, PlayOrder.nextInTime(q, 1))
    }

    @Test
    fun unknownTimeFallsBackToNewerNeighbour() {
        val q = items("b.mp4", "a.mp4")
        assertEquals(0, PlayOrder.nextInTime(q, 1))
        assertNull(PlayOrder.nextInTime(q, 0))
    }

    @Test
    fun previousIsEarlierRecordingNotPreviousIndex() {
        val q = items("FILE260923-132059F.TS", "FILE260923-131759F.TS", "FILE260923-131459F.TS")
        assertEquals(1, PlayOrder.previousInTime(q, 0))
        assertEquals(2, PlayOrder.previousInTime(q, 1))
        assertNull(PlayOrder.previousInTime(q, 2))
    }

    @Test
    fun previousStaysOnSameCamera() {
        val q = items(
            "FILE260923-131759F.TS", "FILE260923-131759R.TS",
            "FILE260923-131459F.TS", "FILE260923-131459R.TS",
        )
        assertEquals(3, PlayOrder.previousInTime(q, 1))
        assertEquals(2, PlayOrder.previousInTime(q, 0))
    }

    @Test
    fun previousCrossesPrefixesAndEditedClips() {
        val q = items("FILE260923-184736F.TS", "EMER260923-184436F_2m50s0-3m00s0.mp4", "EMER260923-184436F.TS")
        assertEquals(1, PlayOrder.previousInTime(q, 0))
        assertEquals(2, PlayOrder.previousInTime(q, 1))
    }

    @Test
    fun previousOfUnknownTimeFallsBackToOlderNeighbour() {
        val q = items("b.mp4", "a.mp4")
        assertEquals(1, PlayOrder.previousInTime(q, 0))
        assertNull(PlayOrder.previousInTime(q, 1))
    }
}
