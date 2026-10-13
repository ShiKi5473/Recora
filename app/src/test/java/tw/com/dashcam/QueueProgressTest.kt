package tw.com.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.dashcam.data.DashcamFile
import tw.com.dashcam.data.Folder
import tw.com.dashcam.download.DownloadTask
import tw.com.dashcam.download.QueueProgress
import tw.com.dashcam.download.TaskStatus
import tw.com.dashcam.ui.Formats

class QueueProgressTest {
    private fun task(size: Long, status: TaskStatus = TaskStatus.Queued, bytes: Long = 0, speed: Long = 0) =
        DownloadTask(
            DashcamFile("FILE260923-131459F.TS", "/mnt/mmc/Video/F/FILE260923-131459F.TS", size, "", Folder.Normal),
            status = status,
            bytes = bytes,
            speedBps = speed,
        )

    @Test
    fun nothingLeftIsNull() {
        assertNull(QueueProgress.of(emptyList()))
        assertNull(QueueProgress.of(listOf(task(100, TaskStatus.Done, bytes = 100), task(100, TaskStatus.Failed))))
    }

    @Test
    fun remainingBytesAndEtaFromCurrentSpeed() {
        val p = QueueProgress.of(
            listOf(
                task(1_000, TaskStatus.Running, bytes = 400, speed = 100),
                task(2_000),
                task(500, TaskStatus.Done, bytes = 500),
            ),
        )!!
        assertEquals(2, p.files)
        assertEquals(2_600L, p.bytes)
        assertTrue(p.allSizesKnown)
        assertEquals(26L, p.etaSeconds)
    }

    @Test
    fun unknownSizeAndNotDownloading() {
        // 推算出來的後鏡頭大小未知；等待連線時沒有正在下載的檔案，不估時間
        val p = QueueProgress.of(listOf(task(1_000), task(-1)))!!
        assertEquals(2, p.files)
        assertEquals(1_000L, p.bytes)
        assertFalse(p.allSizesKnown)
        assertNull(p.etaSeconds)
    }

    @Test
    fun etaText() {
        assertEquals("不到 1 分鐘", Formats.eta(59))
        assertEquals("約 1 分鐘", Formats.eta(60))
        assertEquals("約 2 分鐘", Formats.eta(61))
        assertEquals("約 1 小時", Formats.eta(3_600))
        assertEquals("約 1 小時 5 分", Formats.eta(3_900))
    }
}
