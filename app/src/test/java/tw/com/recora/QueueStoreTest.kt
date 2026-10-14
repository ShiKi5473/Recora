package tw.com.recora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.com.recora.data.DashcamFile
import tw.com.recora.data.Folder
import tw.com.recora.data.SubfolderMode
import tw.com.recora.download.DownloadFolders
import tw.com.recora.download.QueueStore

class QueueStoreTest {
    private val front = DashcamFile(
        "EMER260923-184436F.TS", "/mnt/mmc/Emergency/F/EMER260923-184436F.TS", 265_000_000, "2026-09-23 18:44:36", Folder.Event,
    )
    private val guessedRear = DashcamFile(
        "EMER260923-184436R.TS", "/mnt/mmc/Emergency/R/EMER260923-184436R.TS", -1, "2026-09-23 18:44:36", Folder.Event,
        guessed = true,
    )

    @Test
    fun roundTrips() {
        val files = listOf(front, guessedRear)
        assertEquals(files, QueueStore.decode(QueueStore.encode(files)))
    }

    @Test
    fun skipsUnreadableLines() {
        val text = listOf(
            QueueStore.encode(listOf(front)),
            "garbage",
            "/mnt/mmc/x.TS\tnot-a-number\t\tNormal\tfalse",
            "/mnt/mmc/x.TS\t1\t\tNoSuchFolder\tfalse",
            "relative/x.TS\t1\t\tNormal\tfalse",
        ).joinToString("\n")
        assertEquals(listOf(front), QueueStore.decode(text))
    }

    @Test
    fun tabsInTimeDoNotBreakTheLine() {
        val odd = front.copy(time = "2026-09-23\t18:44:36")
        assertEquals("2026-09-23 18:44:36", QueueStore.decode(QueueStore.encode(listOf(odd))).single().time)
    }

    @Test
    fun subfolderByModes() {
        assertNull(DownloadFolders.subdir(front, SubfolderMode.None))
        assertEquals("緊急", DownloadFolders.subdir(front, SubfolderMode.ByFolder))
        assertEquals("2026-09-23", DownloadFolders.subdir(front, SubfolderMode.ByDate))
        // 照片本來就在 Pictures，依類型時不再分
        val photo = DashcamFile("IMG260923-162041F.JPG", "/mnt/mmc/Picture/F/IMG260923-162041F.JPG", 1, "", Folder.Photo)
        assertNull(DownloadFolders.subdir(photo, SubfolderMode.ByFolder))
        // 檔名讀不出時間：用列表的時間
        val unnamed = DashcamFile("clip.TS", "/mnt/mmc/Video/F/clip.TS", 1, "2026-09-22 08:00:00", Folder.Normal)
        assertEquals("2026-09-22", DownloadFolders.subdir(unnamed, SubfolderMode.ByDate))
    }
}
