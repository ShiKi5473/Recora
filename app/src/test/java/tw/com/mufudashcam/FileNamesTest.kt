package tw.com.mufudashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.com.mufudashcam.data.Camera
import tw.com.mufudashcam.data.FileNames
import tw.com.mufudashcam.data.Folder
import tw.com.mufudashcam.data.PathStyle
import java.time.LocalDateTime

class FileNamesTest {
    @Test
    fun parsesFrontAndRearNames() {
        val f = FileNames.parse("FILE260923-131459F.TS")!!
        assertEquals("FILE", f.prefix)
        assertEquals(Camera.Front, f.camera)
        assertEquals("TS", f.ext)
        assertEquals(LocalDateTime.of(2026, 9, 23, 13, 14, 59), f.dateTime)

        val r = FileNames.parse("FILE260923-131459R.TS")!!
        assertEquals(Camera.Rear, r.camera)
        assertEquals(f.key, r.key)
    }

    @Test
    fun parsesEmergencyPrefix() {
        val e = FileNames.parse("EMER260923-131459F.TS")!!
        assertEquals("EMER", e.prefix)
        assertEquals("EMER260923-131459", e.key)
    }

    @Test
    fun unknownNameReturnsNull() {
        assertNull(FileNames.parse("readme.txt"))
    }

    @Test
    fun sdPathFromListing() {
        // 實測：列表回傳完整路徑
        assertEquals(
            "/mnt/mmc/Video/F/FILE260923-131459F.TS",
            FileNames.sdPath("/mnt/mmc/Video/F/FILE260923-131459F.TS", Folder.Normal),
        )
        // $ 分隔
        assertEquals(
            "/mnt/mmc/Emergency/F/EMER260923-131459F.TS",
            FileNames.sdPath("\$mnt\$mmc\$Emergency\$F\$EMER260923-131459F.TS", Folder.Event),
        )
        // 只有檔名：同 dashcam_download.py 補成前鏡頭目錄
        assertEquals(
            "/mnt/mmc/Picture/F/IMG260923-131459F.JPG",
            FileNames.sdPath("IMG260923-131459F.JPG", Folder.Photo),
        )
    }

    @Test
    fun rearPathMatchesPythonRearOf() {
        assertEquals(
            "/mnt/mmc/Video/R/FILE260923-131459R.TS",
            FileNames.rearPathOf("/mnt/mmc/Video/F/FILE260923-131459F.TS"),
        )
        assertNull(FileNames.rearPathOf("/mnt/mmc/Video/R/FILE260923-131459R.TS"))
    }

    @Test
    fun counterpartPathBothWays() {
        assertEquals(
            "/mnt/mmc/Video/R/FILE260923-131459R.TS",
            FileNames.counterpartPath("/mnt/mmc/Video/F/FILE260923-131459F.TS"),
        )
        assertEquals(
            "/mnt/mmc/Emergency/F/EMER260923-131459F.TS",
            FileNames.counterpartPath("/mnt/mmc/Emergency/R/EMER260923-131459R.TS"),
        )
        assertNull(FileNames.counterpartPath("/mnt/mmc/Video/FILE260923-131459.TS"))
    }

    @Test
    fun counterpartNameKeepsTheRest() {
        assertEquals("FILE260923-131459R.TS", FileNames.counterpartName("FILE260923-131459F.TS"))
        assertEquals("FILE260923-131459F.TS", FileNames.counterpartName("FILE260923-131459R.TS"))
        // 剪輯檔、截圖檔：換鏡頭，片段與位置不變
        assertEquals(
            "EMER260923-184436R_0m10s0-0m15s0_靜音.mp4",
            FileNames.counterpartName("EMER260923-184436F_0m10s0-0m15s0_靜音.mp4"),
        )
        assertEquals("FILE260923-131459f_0m15s0.jpg", FileNames.counterpartName("FILE260923-131459r_0m15s0.jpg"))
        assertNull(FileNames.counterpartName("my-clip.mp4"))
        assertNull(FileNames.counterpartName("FILE260923-131459.TS"))
    }

    @Test
    fun cameraOfNameIncludingEditedNames() {
        assertEquals(Camera.Front, FileNames.cameraOfName("FILE260923-131459F.TS"))
        assertEquals(Camera.Rear, FileNames.cameraOfName("EMER260923-184436R_0m10s0-0m15s0.mp4"))
        assertNull(FileNames.cameraOfName("my-clip.mp4"))
    }

    @Test
    fun cameraFromPath() {
        assertEquals(Camera.Rear, FileNames.cameraOf("/mnt/mmc/Video/R/FILE260923-131459R.TS"))
        assertEquals(Camera.Front, FileNames.cameraOf("/mnt/mmc/Video/F/FILE260923-131459F.TS"))
    }

    @Test
    fun bothHttpPathStyles() {
        val p = "/mnt/mmc/Video/F/FILE260923-131459F.TS"
        assertEquals(p, FileNames.httpPath(p, PathStyle.Mnt))
        assertEquals("/Video/F/FILE260923-131459F.TS", FileNames.httpPath(p, PathStyle.Root))
    }

    @Test
    fun deleteProperty() {
        assertEquals(
            "\$mnt\$mmc\$Video\$F\$FILE260923-131459F.TS",
            FileNames.deleteProperty("/mnt/mmc/Video/F/FILE260923-131459F.TS"),
        )
    }

    @Test
    fun timeSettingsValueAndEncoding() {
        val v = FileNames.timeSettingsValue(LocalDateTime.of(2026, 9, 23, 14, 30, 0))
        assertEquals("2026\$09\$23\$14\$30\$00", v)
        // 實測：記錄器不解 %24，必須送原始的 $
        assertEquals(v, FileNames.encodeQueryValue(v))
    }

    @Test
    fun encodingKeepsWildcard() {
        assertEquals("Camera.Menu.*", FileNames.encodeQueryValue("Camera.Menu.*"))
        assertEquals("\$mnt\$mmc\$Video", FileNames.encodeQueryValue("\$mnt\$mmc\$Video"))
        assertEquals("a%2Fb%20c", FileNames.encodeQueryValue("a/b c"))
    }

    @Test
    fun mimeTypes() {
        assertEquals("video/mp2t", FileNames.mimeType("FILE260923-131459F.TS"))
        assertEquals("image/jpeg", FileNames.mimeType("IMG260923-131459F.JPG"))
    }
}
