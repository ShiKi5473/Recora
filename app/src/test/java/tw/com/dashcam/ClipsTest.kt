package tw.com.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.dashcam.data.Brand
import tw.com.dashcam.data.Clip
import tw.com.dashcam.data.Clips
import tw.com.dashcam.data.DashcamFile
import tw.com.dashcam.data.DownloadChoice
import tw.com.dashcam.data.Folder

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
    fun filesForEachDownloadChoice() {
        val both = Clips.group(
            listOf(
                file("/mnt/mmc/Video/F/FILE260923-131459F.TS"),
                file("/mnt/mmc/Video/R/FILE260923-131459R.TS"),
            ),
        ).single()
        assertEquals(2, Clips.filesFor(both, DownloadChoice.Both).size)
        assertEquals(listOf(both.front), Clips.filesFor(both, DownloadChoice.FrontOnly))
        assertEquals(listOf(both.rear), Clips.filesFor(both, DownloadChoice.RearOnly))

        // 只有後鏡頭：「只下載前鏡頭」改下載後鏡頭；只有前鏡頭：「只下載後鏡頭」什麼都不下載
        val rearOnly = Clip("k", null, file("/mnt/mmc/Video/R/FILE260923-131459R.TS"))
        assertEquals(listOf(rearOnly.rear), Clips.filesFor(rearOnly, DownloadChoice.FrontOnly))
        val frontOnly = Clip("k", file("/mnt/mmc/Video/F/FILE260923-131459F.TS"), null)
        assertEquals(emptyList<DashcamFile>(), Clips.filesFor(frontOnly, DownloadChoice.RearOnly))
    }

    @Test
    fun filesToDeleteFollowsRearChoice() {
        val both = Clips.group(
            listOf(
                file("/mnt/mmc/Video/F/FILE260923-131459F.TS"),
                file("/mnt/mmc/Video/R/FILE260923-131459R.TS"),
            ),
        ).single()
        assertEquals(listOf(both.front, both.rear), Clips.filesToDelete(both, includeRear = true))
        assertEquals(listOf(both.front), Clips.filesToDelete(both, includeRear = false))
        // 只有後鏡頭：沒勾「一併刪除後鏡頭檔」也要刪，否則這一段什麼都沒刪卻算成功
        val rearOnly = Clip("k", null, file("/mnt/mmc/Video/R/FILE260923-131459R.TS"))
        assertEquals(listOf(rearOnly.rear), Clips.filesToDelete(rearOnly, includeRear = false))
    }

    @Test
    fun downloadedNeedsMatchingSize() {
        val f = file("/mnt/mmc/Video/F/FILE260923-131459F.TS", size = 100)
        assertTrue(Clips.isDownloaded(f, mapOf("FILE260923-131459F.TS" to 100L)))
        assertFalse(Clips.isDownloaded(f, mapOf("FILE260923-131459F.TS" to 99L)))
        assertFalse(Clips.isDownloaded(f, emptyMap()))
        // 大小未知（推算出來的後鏡頭）：只看檔名
        assertTrue(Clips.isDownloaded(f.copy(size = -1), mapOf("FILE260923-131459F.TS" to 5L)))
    }

    @Test
    fun missingRespectsIncludeRearAndIgnoresGuessedRear() {
        val listed = Clips.group(
            listOf(
                file("/mnt/mmc/Video/F/FILE260923-131459F.TS"),
                file("/mnt/mmc/Video/R/FILE260923-131459R.TS"),
            ),
        ).single()
        val frontDone = mapOf("FILE260923-131459F.TS" to 100L)
        assertTrue(Clips.isMissing(listed, includeRear = true, downloaded = frontDone))
        assertFalse(Clips.isMissing(listed, includeRear = false, downloaded = frontDone))

        // reardir 沒讀到：推算出來的後鏡頭不一定存在，不算沒下載
        val guessed = Clips.group(listOf(file("/mnt/mmc/Video/F/FILE260923-131459F.TS"))).single()
        assertFalse(Clips.isMissing(guessed, includeRear = true, downloaded = frontDone))
    }

    @Test
    fun backedUpOnlyWhenEveryListedFileIsDownloaded() {
        val listed = Clips.group(
            listOf(
                file("/mnt/mmc/Video/F/FILE260923-131459F.TS"),
                file("/mnt/mmc/Video/R/FILE260923-131459R.TS"),
            ),
        ).single()
        val frontDone = mapOf("FILE260923-131459F.TS" to 100L)
        // 後鏡頭還沒下載：從記錄器刪掉會連後鏡頭一起沒了
        assertFalse(Clips.isBackedUp(listed, frontDone))
        assertTrue(Clips.isBackedUp(listed, frontDone + ("FILE260923-131459R.TS" to 100L)))
        // 推算出來的後鏡頭不算
        assertTrue(Clips.isBackedUp(Clips.group(listOf(file("/mnt/mmc/Video/F/FILE260923-131459F.TS"))).single(), frontDone))
        assertFalse(Clips.isBackedUp(listed, emptyMap()))
    }

    @Test
    fun notDownloadedForBackup() {
        val clips = Clips.group(
            listOf(
                file("/mnt/mmc/Emergency/F/EMER260923-131459F.TS"),
                file("/mnt/mmc/Emergency/R/EMER260923-131459R.TS"),
                file("/mnt/mmc/Emergency/F/EMER260923-120000F.TS"),
            ),
        )
        val done = mapOf("EMER260923-131459F.TS" to 100L)
        assertEquals(
            listOf("EMER260923-131459R.TS", "EMER260923-120000F.TS"),
            Clips.notDownloaded(clips, includeRear = true, downloaded = done).map { it.name },
        )
        assertEquals(
            listOf("EMER260923-120000F.TS"),
            Clips.notDownloaded(clips, includeRear = false, downloaded = done).map { it.name },
        )
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

    @Test
    fun guessedRearKeepsFrontBrand() {
        val front = DashcamFile("a.TS", "/mnt/mmc/Video/F/FILE260923-131459F.TS", 100, "", Folder.Normal, brand = Brand.Astro)
        assertEquals(Brand.Astro, Clips.group(listOf(front)).single().rear!!.brand)
    }
}
