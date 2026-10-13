package tw.com.mufudashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.mufudashcam.data.Brand
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
    fun duplicateNamesRenamedByMediaStoreStillParsed() {
        // 同一範圍匯出兩次：MediaStore 自動改名成 "… (1).mp4"
        val m = build("EMER260923-184436F_0m10s0-0m15s0_靜音 (1).mp4", "Movies/MufuDashcam/")!!
        assertEquals("EMER260923-184436F", m.source)
        assertEquals(10_000L..15_000L, m.rangeMs)
        assertTrue(m.muted)
        assertEquals(Camera.Front, m.camera)
        // 同一位置截圖兩次
        val shot = build("EMER260923-184436R_0m15s0 (2).jpg", "Pictures/MufuDashcam/")!!
        assertEquals(LocalDateTime.of(2026, 9, 23, 18, 44, 51), shot.time)
        assertEquals(Camera.Rear, shot.camera)
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
    fun astroMp4IsOriginal() {
        val m = build("20260814081925_002902F.MP4", "Movies/astoramily/")!!
        assertEquals(MediaKind.Original, m.kind)
        assertEquals(LocalDateTime.of(2026, 8, 14, 8, 19, 25), m.time)
        assertEquals(Camera.Front, m.camera)
        assertEquals(LocalDateTime.of(2026, 8, 14, 8, 19, 25), LocalLibrary.recordingStart(m.name))
    }

    @Test
    fun astroEditedAndScreenshotTrackSource() {
        val name = LocalLibrary.editedName("20260814081925_002902F.MP4", 10_000, 15_000, muted = false)
        val edited = build(name, "Movies/MufuDashcam/")!!
        assertEquals(MediaKind.Edited, edited.kind)
        assertEquals("20260814081925_002902F", edited.source)
        assertEquals(Camera.Front, edited.camera)
        assertEquals(LocalDateTime.of(2026, 8, 14, 8, 19, 35), LocalLibrary.recordingStart(name))

        val shot = build(LocalLibrary.screenshotName("20260814081925_002902F.MP4", 15_000), "Pictures/MufuDashcam/")!!
        assertEquals(LocalDateTime.of(2026, 8, 14, 8, 19, 40), shot.time)

        val source = build("20260814081925_002902F.MP4", "Movies/astoramily/")!!
        assertEquals(listOf(source), LocalLibrary.editedOriginals(listOf(edited, source)))
    }

    @Test
    fun brandFromNameOrSource() {
        assertEquals(Brand.Mufu, build("EMER260923-184436F.TS", "Movies/MufuDashcam/")!!.brand)
        assertEquals(Brand.Mufu, build("FILE260924-091146F_1m02s5-1m30s0.mp4", "Movies/MufuDashcam/")!!.brand)
        assertEquals(Brand.Mufu, build("IMG260923-162041F.JPG", "Pictures/MufuDashcam/")!!.brand)
        assertEquals(Brand.Astro, build("20260814081925_002902F.MP4", "Movies/astoramily/")!!.brand)
        assertEquals(Brand.Astro, build("20260814081925_002902F_0m10s0-0m15s0.mp4", "Movies/MufuDashcam/")!!.brand)
        assertEquals(Brand.Astro, build("20260814081925_002902F_0m15s0.jpg", "Pictures/MufuDashcam/")!!.brand)
        // 看不出來源：原始檔看資料夾，剪輯檔、照片歸「其它」
        assertEquals(Brand.Mufu, build("clip.TS", "Movies/MufuDashcam/")!!.brand)
        assertEquals(Brand.Astro, build("renamed.MP4", "Movies/astoramily/")!!.brand)
        assertEquals(Brand.Other, build("my-clip.mp4", "Movies/MufuDashcam/")!!.brand)
        assertEquals(Brand.Other, build("photo.jpg", "Pictures/MufuDashcam/")!!.brand)
    }

    @Test
    fun astroAdjacentSegment() {
        val a = build("20260814081925_002902F.MP4", "Movies/astoramily/")!!
        val b = build("20260814082024_002903F.MP4", "Movies/astoramily/")!!
        assertEquals(b, LocalLibrary.adjacent(listOf(a, b), a.name, forward = true))
        assertEquals(a, LocalLibrary.adjacent(listOf(a, b), b.name, forward = false))
    }

    @Test
    fun officialAppDownloadsAreOriginals() {
        val m = build("EMER260909-090223R_P2.mp4", "Movies/MufuDashcam/")!!
        assertEquals(MediaKind.Original, m.kind)
        assertEquals(LocalDateTime.of(2026, 9, 9, 9, 2, 23), m.time)
        assertEquals(Camera.Rear, m.camera)
        assertEquals(Brand.Mufu, m.brand)
        // Astro 的檔案被複製到 App 資料夾也是原始檔
        assertEquals(MediaKind.Original, build("20261010202303_004583F.MP4", "Movies/MufuDashcam/")!!.kind)
    }

    @Test
    fun officialAppEditUsesNameTime() {
        // 官方 App 存的檔案常沒有 DATE_ADDED：時間看檔名（存檔時間）
        val m = LocalLibrary.build("content://x/v", "video_2026-0922-150830_10_E_O_N_38072.mp4", "Movies/MufuDashcam/", 1, 10_000, 0, null, zone)!!
        assertEquals(MediaKind.Edited, m.kind)
        assertEquals(LocalDateTime.of(2026, 9, 22, 15, 8, 30), m.time)
        assertNull(m.source)
    }

    @Test
    fun customFoldersClassifiedByName() {
        val folders = setOf("DCIM/Camera/")
        assertNull(build("VID_20261010_120000.mp4", "DCIM/Camera/"))
        val video = LocalLibrary.build("content://x/v", "VID_20261010_120000.mp4", "DCIM/Camera/", 1, 1, added, null, zone, folders = folders)!!
        assertEquals(MediaKind.Original, video.kind)
        assertEquals(Brand.Other, video.brand)
        val photo = LocalLibrary.build("content://x/p", "IMG_1.jpg", "DCIM/Camera/", 1, 0, added, null, zone, isImage = true, folders = folders)!!
        assertEquals(MediaKind.Photo, photo.kind)
        val edited = LocalLibrary.build(
            "content://x/e", "FILE260924-091146F_1m02s5-1m30s0.mp4", "DCIM/Camera/", 1, 1, added, null, zone, folders = folders,
        )!!
        assertEquals(MediaKind.Edited, edited.kind)
        // 子資料夾也算；取消勾選 Astro 的資料夾後就不讀
        assertEquals(MediaKind.Original, LocalLibrary.build("u", "a.mp4", "DCIM/Camera/sub/", 1, 1, added, null, zone, folders = folders)!!.kind)
        assertNull(LocalLibrary.build("u", "20260814081925_002902F.MP4", "Movies/astoramily/", 1, 1, added, null, zone, folders = emptySet()))
        // 手機相機拍的影片不會被當成可以清掉的舊原始檔
        assertEquals(emptyList<Any>(), LocalLibrary.originalsRecordedBefore(listOf(video), LocalDateTime.of(2030, 1, 1, 0, 0)))
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
    fun recordingStartOfOriginalAndEdited() {
        assertEquals(LocalDateTime.of(2026, 9, 23, 18, 44, 36), LocalLibrary.recordingStart("EMER260923-184436F.TS"))
        // 剪輯檔 = 來源開始時間 + 片段起點
        assertEquals(
            LocalDateTime.of(2026, 9, 23, 18, 45, 38, 500_000_000),
            LocalLibrary.recordingStart("EMER260923-184436F_1m02s5-1m30s0_靜音 (1).mp4"),
        )
        assertNull(LocalLibrary.recordingStart("my-clip.mp4"))
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
    fun adjacentSameCameraNearestInTime() {
        val event = build("EMER260923-184436F.TS", "Movies/MufuDashcam/")!!
        val after = build("FILE260923-184736F.TS", "Movies/MufuDashcam/")!!
        val later = build("FILE260923-185036F.TS", "Movies/MufuDashcam/")!!
        val before = build("FILE260923-184136F.TS", "Movies/MufuDashcam/")!!
        val rear = build("FILE260923-184736R.TS", "Movies/MufuDashcam/")!!
        val edited = build("FILE260923-184736F_0m00s0-0m10s0.mp4", "Movies/MufuDashcam/")!!
        val all = listOf(later, rear, after, edited, event, before)
        // 緊急錄影後面接的是一般錄影（前綴不同也算）；只找同鏡頭的原始檔
        assertEquals(after, LocalLibrary.adjacent(all, event.name, forward = true))
        assertEquals(before, LocalLibrary.adjacent(all, event.name, forward = false))
        assertNull(LocalLibrary.adjacent(all, later.name, forward = true))
        assertNull(LocalLibrary.adjacent(all, "my-clip.mp4", forward = true))
    }

    @Test
    fun cleanupCandidates() {
        val edited = build("EMER260923-184436F_0m10s0-0m15s0.mp4", "Movies/MufuDashcam/")!!
        val source = build("EMER260923-184436F.TS", "Movies/MufuDashcam/緊急/")!!
        val other = build("FILE260920-080000F.TS", "Movies/MufuDashcam/")!!
        val photo = build("EMER260923-184436F_0m15s0.jpg", "Pictures/MufuDashcam/")!!
        val all = listOf(edited, source, other, photo)
        assertEquals(listOf(source), LocalLibrary.editedOriginals(all))
        // 只算原始檔，看錄影時間
        assertEquals(listOf(other), LocalLibrary.originalsRecordedBefore(all, LocalDateTime.of(2026, 9, 22, 0, 0)))
    }

    @Test
    fun sortedNewestFirst() {
        val a = build("FILE260922-120000F.TS", "Movies/MufuDashcam/")!!
        val b = build("FILE260924-080000F.TS", "Movies/MufuDashcam/")!!
        val c = build("FILE260923-235959F.TS", "Movies/MufuDashcam/")!!
        assertEquals(listOf(b, c, a), LocalLibrary.sorted(listOf(a, b, c)))
    }
}
