package tw.com.mufudashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.mufudashcam.data.Clips
import tw.com.mufudashcam.data.DashcamFile
import tw.com.mufudashcam.data.Folder

class ClipsTest {
    private fun file(path: String, size: Long = 100) =
        DashcamFile(path.substringAfterLast('/'), path, size, "", Folder.Normal)

    @Test
    fun pairsListedFrontAndRear() {
        val clips = Clips.group(
            listOf(
                file("/mnt/mmc/Video/F/FILE260923-131459F.TS"),
                file("/mnt/mmc/Video/R/FILE260923-131459R.TS"),
                file("/mnt/mmc/Video/F/FILE260923-131359F.TS"),
            ),
        )
        assertEquals(2, clips.size)
        // 最新的在前
        assertEquals("FILE260923-131459", clips[0].key)
        assertFalse(clips[0].rear!!.guessed)
        // 列表有後鏡頭檔時，不自行推算
        assertEquals(null, clips[1].rear)
    }

    @Test
    fun guessesRearWhenListingHasOnlyFront() {
        val clips = Clips.group(listOf(file("/mnt/mmc/Video/F/FILE260923-131459F.TS")))
        val rear = clips.single().rear!!
        assertTrue(rear.guessed)
        assertEquals("/mnt/mmc/Video/R/FILE260923-131459R.TS", rear.sdPath)
        assertEquals("FILE260923-131459R.TS", rear.name)
        assertEquals(-1L, rear.size)
    }

    @Test
    fun sortsAcrossDaysNewestFirst() {
        val clips = Clips.group(
            listOf(
                file("/mnt/mmc/Video/F/FILE260922-235959F.TS"),
                file("/mnt/mmc/Video/F/FILE260923-000059F.TS"),
            ),
        )
        assertEquals(listOf("FILE260923-000059", "FILE260922-235959"), clips.map { it.key })
    }
}
