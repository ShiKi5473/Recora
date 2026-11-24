package tw.com.recora

import android.net.Network
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.com.recora.data.Brand
import tw.com.recora.data.Clip
import tw.com.recora.data.DashcamDeleter
import tw.com.recora.data.DashcamFile
import tw.com.recora.data.DashcamFiles
import tw.com.recora.data.Folder
import tw.com.recora.data.SettingDef
import tw.com.recora.data.api.DashcamApi
import java.io.IOException
import java.time.ZonedDateTime

class DashcamDeleterTest {
    /** 只實作列表與刪除；[refuse] = 記錄器回失敗，[broken] = 連線錯誤。 */
    private class FakeApi(
        private val listing: List<DashcamFile>,
        private val refuse: Set<String> = emptySet(),
        private val broken: Set<String> = emptySet(),
    ) : DashcamApi {
        val sent = mutableListOf<String>()
        override val brand = Brand.Mufu
        override val host = "127.0.0.1"
        override val liveViews: List<DashcamApi.LiveView> = emptyList()

        override fun listFolder(folder: Folder): Flow<List<DashcamFile>> = flowOf(listing.filter { it.folder == folder })

        override suspend fun delete(sdPath: String): DashcamApi.SetResult {
            sent += sdPath
            if (sdPath in broken) throw IOException("timeout")
            return if (sdPath in refuse) DashcamApi.SetResult(false, "fail") else DashcamApi.SetResult(true, "0 OK")
        }

        override suspend fun probe(network: Network?) = TODO()
        override suspend fun syncTime(now: ZonedDateTime) = TODO()
        override fun httpUrl(sdPath: String) = TODO()
        override suspend fun resolveUrl(sdPath: String) = TODO()
        override suspend fun startLive(view: DashcamApi.LiveView) = TODO()
        override suspend fun recordingStatus() = TODO()
        override suspend fun cardStatus() = TODO()
        override suspend fun storageInfo() = TODO()
        override suspend fun readSettings() = TODO()
        override suspend fun changeSetting(def: SettingDef, value: String) = TODO()
        override suspend fun formatCard(onProgress: (String) -> Unit) = TODO()
    }

    private fun file(name: String, cam: String, guessed: Boolean = false, readOnly: Boolean = false) =
        DashcamFile(name, "/mnt/mmc/Video/$cam/$name", 100, "", Folder.Normal, guessed = guessed, readOnly = readOnly)

    @Test
    fun deletesClipsAndSummarises() = runTest {
        val f1 = file("FILE260923-131459F.TS", "F")
        val r1 = file("FILE260923-131459R.TS", "R", guessed = true) // 推算的後鏡頭：不存在不算失敗
        val f2 = file("FILE260923-132000F.TS", "F")
        val locked = file("FILE260923-133000F.TS", "F", readOnly = true)
        val api = FakeApi(listOf(f1, f2, locked), refuse = setOf(r1.sdPath, f2.sdPath))
        val files = DashcamFiles({ api }, flowOf(true), backgroundScope)
        files.list(Folder.Normal).toList()
        val deleter = DashcamDeleter({ api }, files)

        val summary = deleter.deleteClips(
            listOf(Clip("1", f1, r1), Clip("2", f2, null), Clip("3", locked, null)),
            includeRear = true,
        )

        assertEquals(DashcamDeleter.Summary(done = 1, failed = 1, protectedFiles = 1, lastError = "fail"), summary)
        // 唯讀的不送出
        assertEquals(listOf(f1.sdPath, r1.sdPath, f2.sdPath), api.sent)
        // 刪掉的從快取移除，失敗的留著
        assertNull(files.find(f1.sdPath))
        assertEquals(f2, files.find(f2.sdPath))
        assertEquals(
            "已刪除 1 段；1 段失敗（fail）；緊急錄影被記錄器鎖定，略過 1 個檔案（要清除只能格式化記憶卡）",
            summary.message,
        )
    }

    @Test
    fun singleDeleteUsesCachedReadOnlyFlagAndReportsErrors() = runTest {
        val locked = file("FILE260923-133000F.TS", "F", readOnly = true)
        val gone = file("FILE260923-134000F.TS", "F")
        val api = FakeApi(listOf(locked, gone), broken = setOf(gone.sdPath))
        val files = DashcamFiles({ api }, flowOf(true), backgroundScope)
        files.list(Folder.Normal).toList()
        val deleter = DashcamDeleter({ api }, files)

        // 播放器只知道路徑：唯讀與否看列表快取
        assertEquals(DashcamDeleter.Result.Protected, deleter.delete(locked.sdPath))
        assertEquals(DashcamDeleter.Result.Failed("timeout"), deleter.delete(gone.sdPath))
        assertEquals(listOf(gone.sdPath), api.sent)
        assertEquals(gone, files.find(gone.sdPath))
    }

    @Test
    fun summaryMessages() {
        assertEquals("已刪除 0 段", DashcamDeleter.Summary(0, 0, 0, null).message)
        assertEquals("已刪除 2 段", DashcamDeleter.Summary(2, 0, 0, null).message)
        assertEquals("1 段失敗（HTTP 500）", DashcamDeleter.Summary(0, 1, 0, "HTTP 500").message)
        assertEquals(
            "緊急錄影被記錄器鎖定，略過 2 個檔案（要清除只能格式化記憶卡）",
            DashcamDeleter.Summary(0, 0, 2, null).message,
        )
    }
}
