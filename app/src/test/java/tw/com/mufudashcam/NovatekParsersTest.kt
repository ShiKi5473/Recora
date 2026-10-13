package tw.com.mufudashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.mufudashcam.data.Brand
import tw.com.mufudashcam.data.Camera
import tw.com.mufudashcam.data.Clips
import tw.com.mufudashcam.data.DashcamFile
import tw.com.mufudashcam.data.FileNames
import tw.com.mufudashcam.data.Folder
import tw.com.mufudashcam.data.api.NovatekParsers
import tw.com.mufudashcam.download.QueueStore
import java.time.ZoneId
import java.time.ZonedDateTime

/** 樣本取自官方 App 的 ZyLog（ASTRO_LEO3_API.md）。 */
class NovatekParsersTest {
    private val fileList = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <LIST>
        <ALLFile><File>
        <NAME>20260706083543_000045F.MP4</NAME>
        <FPATH>A:\DCIM\EMERG_F\20260706083543_000045F.MP4</FPATH>
        <SIZE>86136148</SIZE>
        <TIMECODE>1558594709</TIMECODE>
        <TIME>2026/07/06 08:36:42</TIME>
        <ATTR>33</ATTR></File>
        </ALLFile>
        <ALLFile><File>
        <NAME>20260706083543_000045R.MP4</NAME>
        <FPATH>A:\DCIM\EMERG_R\20260706083543_000045R.MP4</FPATH>
        <SIZE>40000000</SIZE>
        <TIMECODE>1558594709</TIMECODE>
        <TIME>2026/07/06 08:36:42</TIME>
        <ATTR>33</ATTR></File>
        </ALLFile>
        <ALLFile><File>
        <NAME>20260813120000_002000F.MP4</NAME>
        <FPATH>A:\DCIM\VIDEO_F\20260813120000_002000F.MP4</FPATH>
        <SIZE>292000000</SIZE>
        <TIMECODE>1</TIMECODE>
        <TIME>2026/08/13 12:01:00</TIME>
        <ATTR>32</ATTR></File>
        </ALLFile>
        <ALLFile><File>
        <NAME>20260813120000_002000R.MP4</NAME>
        <FPATH>A:\DCIM\VIDEO_R\20260813120000_002000R.MP4</FPATH>
        <SIZE>100</SIZE>
        <TIMECODE>1</TIMECODE>
        <TIME>2026/08/13 12:01:00</TIME>
        <ATTR>32</ATTR></File>
        </ALLFile>
        </LIST>
    """.trimIndent()

    @Test
    fun parsesReplyStatusValueAndString() {
        val version = NovatekParsers.parseReply(
            "<?xml version=\"1.0\"?><Function><Cmd>3012</Cmd><Status>0</Status>" +
                "<String>MB:V1.00.11,CameraA:V1.00.01,CameraB:V1.00.01</String></Function>",
        )
        assertEquals(3012, version.cmd)
        assertTrue(version.ok)
        assertEquals("MB:V1.00.11,CameraA:V1.00.01,CameraB:V1.00.01", version.string)

        val time = NovatekParsers.parseReply("<Function><Cmd>2016</Cmd><Status>0</Status><Value>42</Value></Function>")
        assertEquals("42", time.value)

        val rejected = NovatekParsers.parseReply("<Function><Cmd>2001</Cmd><Status>-13</Status></Function>")
        assertFalse(rejected.ok)
        assertEquals(-13, rejected.status)
        assertNull(rejected.value)
    }

    @Test
    fun parsesFileListIntoFrontAndRearWithNormalizedTime() {
        val entries = NovatekParsers.parseFileList(fileList)
        assertEquals(4, entries.size)
        val first = entries[0]
        assertEquals("20260706083543_000045F.MP4", first.name)
        assertEquals("""A:\DCIM\EMERG_F\20260706083543_000045F.MP4""", first.fpath)
        assertEquals(86136148L, first.size)
        assertEquals("2026-07-06 08:36:42", first.time)
        assertEquals(33, first.attr)
        assertEquals(32, entries[2].attr)
    }

    @Test
    fun convertsPathsBothWays() {
        val fpath = """A:\DCIM\VIDEO_F\20260813120000_002000F.MP4"""
        val sd = NovatekParsers.sdPathOf(fpath)
        assertEquals("/DCIM/VIDEO_F/20260813120000_002000F.MP4", sd)
        assertEquals(fpath, NovatekParsers.fpathOf(sd))
    }

    @Test
    fun classifiesFolders() {
        assertEquals(Folder.Event, NovatekParsers.folderOf("/DCIM/EMERG_F/a.MP4"))
        assertEquals(Folder.Normal, NovatekParsers.folderOf("/DCIM/VIDEO_R/a.MP4"))
        assertEquals(Folder.Parking, NovatekParsers.folderOf("/DCIM/PARK_F/a.MP4"))
        assertEquals(Folder.Photo, NovatekParsers.folderOf("/DCIM/VIDEO_F/a.JPG"))
    }

    @Test
    fun parsesStatusPairs() {
        val xml = "<Function><Cmd>3014</Cmd><Status>0</Status></Function>" +
            "<Function><Cmd>2001</Cmd><Status>1</Status></Function>" +
            "<Function><Cmd>2003</Cmd><Status>1</Status></Function>" +
            "<Function><Cmd>8103</Cmd><Status>6</Status></Function>"
        val pairs = NovatekParsers.parseStatusPairs(xml)
        assertEquals("1", pairs["2001"])
        assertEquals("6", pairs["8103"])
        assertEquals("1", pairs["2003"])
    }

    @Test
    fun parsesMenuAndMovieSizes() {
        val menu = NovatekParsers.parseMenu(
            "<MenuList><Item><Cmd>2003</Cmd><Name>MOVIE_CYCLIC_REC</Name><MenuList>" +
                "<Option><Index>0</Index><Id>1MIN</Id></Option>" +
                "<Option><Index>1</Index><Id>3MIN</Id></Option>" +
                "<Option><Index>2</Index><Id>5MIN</Id></Option></MenuList></Item></MenuList>",
        )
        assertEquals(1, menu.size)
        assertEquals("2003", menu[0].cmd)
        assertEquals(listOf("0" to "1MIN", "1" to "3MIN", "2" to "5MIN"), menu[0].options)

        val sizes = NovatekParsers.parseMovieSizes(
            "<List><Item><Name>2560x1440P30</Name><Index>0</Index><Size>2560*1440</Size></Item>" +
                "<Item><Name>2560x1440P30 HDR</Name><Index>1</Index></Item>" +
                "<Item><Name>1920x1080P60</Name><Index>2</Index></Item></List>",
        )
        assertEquals(listOf("0" to "2560x1440P30", "1" to "2560x1440P30 HDR", "2" to "1920x1080P60"), sizes)
    }

    @Test
    fun parsesLiveLink() {
        val xml = "<LIST><MovieLiveViewLink>rtsp://192.168.1.254/xxx.mov</MovieLiveViewLink>" +
            "<PhotoLiveViewLink>rtsp://192.168.1.254/xxx.mov</PhotoLiveViewLink></LIST>"
        assertEquals("rtsp://192.168.1.254/xxx.mov", NovatekParsers.parseLiveLink(xml))
    }

    @Test
    fun syncsTimeAsUtc() {
        // 官方 App 紀錄：手機 20:38:59（UTC+8）送 12:38:59
        val now = ZonedDateTime.of(2026, 10, 13, 20, 38, 59, 0, ZoneId.of("Asia/Taipei"))
        assertEquals("2026-10-13", NovatekParsers.utcDate(now))
        assertEquals("12:38:59", NovatekParsers.utcTime(now))
        // 跨日
        val early = ZonedDateTime.of(2026, 10, 14, 3, 0, 0, 0, ZoneId.of("Asia/Taipei"))
        assertEquals("2026-10-13", NovatekParsers.utcDate(early))
        assertEquals("19:00:00", NovatekParsers.utcTime(early))
    }

    // ---- 檔名、路徑、佇列 ----

    @Test
    fun counterpartAndCameraOfWorkForAstroFolders() {
        val front = "/DCIM/VIDEO_F/20260813120000_002000F.MP4"
        val rear = "/DCIM/VIDEO_R/20260813120000_002000R.MP4"
        assertEquals(rear, FileNames.counterpartPath(front))
        assertEquals(front, FileNames.counterpartPath(rear))
        assertEquals(Camera.Front, FileNames.cameraOf(front))
        assertEquals(Camera.Rear, FileNames.cameraOf(rear))
        assertEquals("/DCIM/EMERG_R/20260706083543_000045R.MP4", FileNames.counterpartPath("/DCIM/EMERG_F/20260706083543_000045F.MP4"))
        // MUFU 照舊
        assertEquals("/mnt/mmc/Video/R/FILE260923-131459R.TS", FileNames.counterpartPath("/mnt/mmc/Video/F/FILE260923-131459F.TS"))
    }

    @Test
    fun groupsAstroFrontAndRearByName() {
        fun f(path: String) = DashcamFile(path.substringAfterLast('/'), path, 100, "", Folder.Normal, brand = Brand.Astro)
        val clips = Clips.group(
            listOf(
                f("/DCIM/VIDEO_F/20260813120000_002000F.MP4"),
                f("/DCIM/VIDEO_R/20260813120000_002000R.MP4"),
                f("/DCIM/VIDEO_F/20260813120300_002001F.MP4"),
            ),
        )
        assertEquals(2, clips.size)
        assertEquals("/DCIM/VIDEO_R/20260813120000_002000R.MP4", clips[1].rear!!.sdPath)
        assertFalse(clips[1].rear!!.guessed)
        assertNull(clips[0].rear)
    }

    @Test
    fun queueStoreKeepsBrandAndReadsOldFormatAsMufu() {
        val astro = DashcamFile(
            "20260813120000_002000F.MP4", "/DCIM/VIDEO_F/20260813120000_002000F.MP4", 292_000_000,
            "2026-08-13 12:01:00", Folder.Normal, brand = Brand.Astro,
        )
        assertEquals(listOf(astro), QueueStore.decode(QueueStore.encode(listOf(astro))))

        val old = "/mnt/mmc/Video/F/FILE260923-131459F.TS\t265000000\t2026-09-23 13:14:59\tNormal\tfalse"
        val decoded = QueueStore.decode(old)
        assertEquals(1, decoded.size)
        assertEquals(Brand.Mufu, decoded[0].brand)
    }
}
