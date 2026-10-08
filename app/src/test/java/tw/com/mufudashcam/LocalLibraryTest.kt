package tw.com.mufudashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.mufudashcam.data.Camera
import tw.com.mufudashcam.data.LocalLibrary
import tw.com.mufudashcam.data.MediaKind
import java.time.LocalDateTime
import java.time.ZoneOffset

class LocalLibraryTest {
    private val zone = ZoneOffset.ofHours(8)

    /** 2026-09-24 14:30:00 +08:00 */
    private val added = LocalDateTime.of(2026, 9, 24, 14, 30).toEpochSecond(zone)

    private fun build(name: String, path: String, taken: Long? = null) =
        LocalLibrary.build("content://x/$name", name, path, 100, 60_000, added, taken, zone)

    @Test
    fun originalUsesShootingTimeFromName() {
        val m = build("EMER260923-184436F.TS", "Movies/MufuDashcam/")!!
        assertEquals(MediaKind.Original, m.kind)
        assertEquals(LocalDateTime.of(2026, 9, 23, 18, 44, 36), m.time)
    }

    @Test
    fun originalFallsBackToDateTakenThenAdded() {
        val takenMs = LocalDateTime.of(2026, 1, 2, 3, 4, 5).toEpochSecond(zone) * 1000
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), build("clip.TS", "Movies/MufuDashcam/", takenMs)!!.time)
        assertEquals(LocalDateTime.of(2026, 9, 24, 14, 30), build("clip.TS", "Movies/MufuDashcam/")!!.time)
    }

    @Test
    fun editedUsesEditTimeAndParsesName() {
        val m = build("EMER260923-184436F_0m10s0-0m15s0_靜音.mp4", "Movies/MufuDashcam/")!!
        assertEquals(MediaKind.Edited, m.kind)
        assertEquals(LocalDateTime.of(2026, 9, 24, 14, 30), m.time) // 剪輯時間 = 寫入相簿的時間
        assertEquals("EMER260923-184436F", m.source)
        assertEquals(10_000L..15_000L, m.rangeMs)
        assertTrue(m.muted)

        val withSound = build("FILE260924-091146F_1m02s5-1m30s0.mp4", "Movies/MufuDashcam/")!!
        assertFalse(withSound.muted)
        assertEquals(62_500L..90_000L, withSound.rangeMs)
    }

    @Test
    fun editedNameRoundTrips() {
        val name = LocalLibrary.editedName("EMER260923-184436R.TS", 62_500, 90_000, muted = true)
        assertEquals("EMER260923-184436R_1m02s5-1m30s0_靜音.mp4", name)
        val m = build(name, "Movies/MufuDashcam/")!!
        assertEquals(MediaKind.Edited, m.kind)
        assertEquals("EMER260923-184436R", m.source)
        assertEquals(62_500L..90_000L, m.rangeMs)
        assertTrue(m.muted)
        assertEquals(Camera.Rear, m.camera)

        assertFalse(build(LocalLibrary.editedName("FILE260924-091146F.TS", 0, 5_000, muted = false), "Movies/MufuDashcam/")!!.muted)
    }

    @Test
    fun editedWithUnknownNameStillEdited() {
        val m = build("my-clip.mp4", "Movies/MufuDashcam/")!!
        assertEquals(MediaKind.Edited, m.kind)
        assertNull(m.source)
    }

    @Test
    fun screenshotTimeIsClipStartPlusOffset() {
        val m = build("EMER260923-184436F_0m15s0.jpg", "Pictures/MufuDashcam/")!!
        assertEquals(MediaKind.Photo, m.kind)
        assertEquals(LocalDateTime.of(2026, 9, 23, 18, 44, 51), m.time)
    }

    @Test
    fun cameraPhotoUsesNameTime() {
        assertEquals(
            LocalDateTime.of(2026, 9, 23, 16, 20, 41),
            build("IMG260923-162041F.JPG", "Pictures/MufuDashcam/")!!.time,
        )
    }

    @Test
    fun otherFoldersExcluded() {
        assertNull(build("EMER260923-184436F.TS", "Movies/Test/"))
        assertNull(build("a.jpg", "DCIM/Camera/"))
        assertNull(build("x.mp4", "Movies/MufuDashcamOther/"))
    }

    @Test
    fun screenshotNameFromOriginal() {
        assertEquals("EMER260923-184436F_0m15s0.jpg", LocalLibrary.screenshotName("EMER260923-184436F.TS", 15_000))
        assertEquals("FILE260924-091146F_1m02s5.jpg", LocalLibrary.screenshotName("FILE260924-091146F.TS", 62_500))
    }

    @Test
    fun screenshotOnEditedMapsBackToSource() {
        // 片段從原影片 0:10 開始，在 MP4 的第 2.5 秒截圖 = 原影片 0:12.5
        val name = LocalLibrary.screenshotName("EMER260923-184436F_0m10s0-0m15s0_靜音.mp4", 2_500)
        assertEquals("EMER260923-184436F_0m12s5.jpg", name)
        // 相簿算出來的時間 = 18:44:36 + 12.5 秒
        assertEquals(
            LocalDateTime.of(2026, 9, 23, 18, 44, 48, 500_000_000),
            build(name, "Pictures/MufuDashcam/")!!.time,
        )
    }

    @Test
    fun cameraFromNames() {
        assertEquals(Camera.Front, build("EMER260923-184436F.TS", "Movies/MufuDashcam/")!!.camera)
        assertEquals(Camera.Rear, build("EMER260923-184436R.TS", "Movies/MufuDashcam/")!!.camera)
        // 已剪輯：看來源影片
        assertEquals(Camera.Rear, build("EMER260923-184436R_0m10s0-0m15s0.mp4", "Movies/MufuDashcam/")!!.camera)
        // 截圖：看來源影片；記錄器照片：看檔名
        assertEquals(Camera.Rear, build("EMER260923-184436R_0m15s0.jpg", "Pictures/MufuDashcam/")!!.camera)
        assertEquals(Camera.Front, build("IMG260923-162041F.JPG", "Pictures/MufuDashcam/")!!.camera)
        // 分辨不出
        assertNull(build("my-clip.mp4", "Movies/MufuDashcam/")!!.camera)
    }

    @Test
    fun sortedNewestFirst() {
        val a = build("FILE260922-120000F.TS", "Movies/MufuDashcam/")!!
        val b = build("FILE260924-080000F.TS", "Movies/MufuDashcam/")!!
        val c = build("FILE260923-235959F.TS", "Movies/MufuDashcam/")!!
        assertEquals(listOf(b, c, a), LocalLibrary.sorted(listOf(a, b, c)))
    }
}
