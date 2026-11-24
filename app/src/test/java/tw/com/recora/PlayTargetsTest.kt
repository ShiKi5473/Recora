package tw.com.recora

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.com.recora.data.PlayItem
import tw.com.recora.data.PlayOrder
import tw.com.recora.data.PlaySources
import tw.com.recora.data.PlayTargets
import tw.com.recora.data.gps.GpsPoint
import tw.com.recora.data.gps.GpsTrack

class PlayTargetsTest {
    /** [local] = 手機上已下載的檔名 → content:// 網址。 */
    private class Sources(private val local: Map<String, String> = emptyMap()) : PlaySources {
        override suspend fun localCopy(name: String): String? = local[name]
        override fun streamUrl(sdPath: String): String = "http://dashcam$sdPath"
    }

    private val front = PlayItem("http://dashcam/mnt/mmc/Video/F/FILE260923-131459F.TS", "FILE260923-131459F.TS", "/mnt/mmc/Video/F/FILE260923-131459F.TS")

    @Test
    fun counterpartStreamsOrPlaysLocalCopy() = runTest {
        val rearPath = "/mnt/mmc/Video/R/FILE260923-131459R.TS"
        assertEquals(PlayItem("http://dashcam$rearPath", "FILE260923-131459R.TS", rearPath), PlayTargets.counterpart(front, Sources()))
        // 手機上有另一鏡頭的檔：播手機上的，仍記得記錄器上的路徑（可以從記錄器刪）
        val local = Sources(mapOf("FILE260923-131459R.TS" to "content://media/9"))
        assertEquals(PlayItem("content://media/9", "FILE260923-131459R.TS", rearPath), PlayTargets.counterpart(front, local))
        // 看不出鏡頭
        assertNull(PlayTargets.counterpart(PlayItem("content://media/1", "clip.mp4"), Sources()))
        // 相簿裡的檔案不知道記錄器上的路徑：手機上也沒有就沒得播
        assertNull(PlayTargets.counterpart(PlayItem("content://media/2", "FILE260923-131459F.TS"), Sources()))
    }

    @Test
    fun resolvePrefersLocalAndKeepsOtherCamera() = runTest {
        val sources = Sources(mapOf("FILE260923-131459F.TS" to "content://media/7"))
        assertEquals(front.copy(url = "content://media/7"), PlayTargets.resolve(front, otherCamera = false, sources))
        assertEquals("FILE260923-131459R.TS", PlayTargets.resolve(front, otherCamera = true, sources).title)
        // 本機檔不再找
        val localItem = PlayItem("content://media/3", "FILE260923-131459F.TS")
        assertEquals(localItem, PlayTargets.preferLocal(localItem, sources))
    }

    @Test
    fun removeKeepsPositionOrStepsBack() {
        val q = listOf("a", "b", "c").map { PlayItem("content://$it", it) }
        assertEquals(listOf(q[0], q[2]) to 1, PlayOrder.remove(q, 1))
        // 刪掉最後一段：播前一段
        assertEquals(listOf(q[0], q[1]) to 1, PlayOrder.remove(q, 2))
        assertNull(PlayOrder.remove(q.take(1), 0))
    }

    @Test
    fun gpsPointForPositionCoversTheTail() {
        val p = { lat: Double -> GpsPoint(null, true, lat, 120.0, 0f, 0f) }
        val track = GpsTrack(listOf(p(24.0), null, p(24.2)))
        assertEquals(24.0, track.pointForPosition(999)!!.latitude, 0.0)
        assertNull(track.pointForPosition(1_500))
        // 片長 3.05 秒、3 筆：最後那一點點算最後一筆
        assertEquals(24.2, track.pointForPosition(3_050)!!.latitude, 0.0)
        assertNull(track.pointForPosition(4_000))
    }
}
