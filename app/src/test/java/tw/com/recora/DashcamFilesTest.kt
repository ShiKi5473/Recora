package tw.com.recora

import android.net.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.com.recora.data.Brand
import tw.com.recora.data.DashcamFile
import tw.com.recora.data.DashcamFiles
import tw.com.recora.data.Folder
import tw.com.recora.data.SettingDef
import tw.com.recora.data.api.DashcamApi
import java.time.ZonedDateTime

class DashcamFilesTest {
    private class FakeApi : DashcamApi {
        var listCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        var pages: List<List<DashcamFile>> = emptyList()

        override val brand = Brand.Mufu
        override val host = "127.0.0.1"
        override val liveViews: List<DashcamApi.LiveView> = emptyList()

        override fun listFolder(folder: Folder) = flow {
            listCalls++
            gate?.await()
            for (p in pages) emit(p)
        }

        override suspend fun probe(network: Network?) = TODO()
        override suspend fun syncTime(now: ZonedDateTime) = TODO()
        override fun httpUrl(sdPath: String) = TODO()
        override suspend fun resolveUrl(sdPath: String) = TODO()
        override suspend fun delete(sdPath: String) = TODO()
        override suspend fun startLive(view: DashcamApi.LiveView) = TODO()
        override suspend fun recordingStatus() = TODO()
        override suspend fun cardStatus() = TODO()
        override suspend fun storageInfo() = TODO()
        override suspend fun readSettings() = TODO()
        override suspend fun changeSetting(def: SettingDef, value: String) = TODO()
        override suspend fun formatCard(onProgress: (String) -> Unit) = TODO()
    }

    private fun file(name: String) =
        DashcamFile(name, "/mnt/mmc/Video/F/$name", 100, "", Folder.Normal)

    @Test
    fun cachesFolderAndClearsOnDisconnectAndForget() = runTest {
        val connected = MutableStateFlow(true)
        val f1 = file("FILE260923-131459F.TS")
        val f2 = file("FILE260923-131559F.TS")
        val api = FakeApi().apply { pages = listOf(listOf(f1), listOf(f1, f2)) }
        val files = DashcamFiles({ api }, connected, backgroundScope)

        val first = files.list(Folder.Normal).toList()
        assertEquals(listOf(listOf(f1), listOf(f1, f2)), first)
        assertEquals(1, api.listCalls)

        // 快取命中：不重送請求
        assertEquals(listOf(listOf(f1, f2)), files.list(Folder.Normal).toList())
        assertEquals(1, api.listCalls)

        // forget 移除已刪檔案
        files.forget(listOf(f1.sdPath))
        assertEquals(listOf(f2), files.cached(Folder.Normal))
        assertNull(files.find(f1.sdPath))

        // 斷線時清空快取
        connected.value = false
        testScheduler.runCurrent()
        assertNull(files.cached(Folder.Normal))
    }

    @Test
    fun staleListingFromDisconnectedGenerationDoesNotPolluteCache() = runTest {
        val connected = MutableStateFlow(true)
        val gate = CompletableDeferred<Unit>()
        val stale = file("OLD.TS")
        val api = FakeApi().apply {
            this.gate = gate
            pages = listOf(listOf(stale))
        }
        val files = DashcamFiles({ api }, connected, backgroundScope)

        val job = launch { files.list(Folder.Normal).toList() }
        testScheduler.runCurrent()

        // 讀取進行中突然斷線並重新連上：generation 已遞增
        connected.value = false
        testScheduler.runCurrent()
        connected.value = true
        testScheduler.runCurrent()

        gate.complete(Unit)
        job.join()

        assertNull(files.cached(Folder.Normal))
    }
}
