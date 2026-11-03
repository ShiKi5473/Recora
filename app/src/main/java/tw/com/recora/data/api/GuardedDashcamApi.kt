package tw.com.recora.data.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tw.com.recora.data.DashcamFile
import tw.com.recora.data.Folder
import tw.com.recora.data.SettingDef
import tw.com.recora.data.net.DashcamAccess
import java.time.ZonedDateTime

/**
 * App 其他地方拿到的記錄器介面（`AppContainer.api`）：每個要跟記錄器講話的呼叫都先經過 [DashcamAccess]
 * （指令排隊、暫停下載），新加的畫面不用自己記得這條規則。
 *
 * 不經過的：連線本身的流程（probe、onConnected…，由 DashcamConnection 直接呼叫實作）、
 * 下載佇列自己的請求、Astro 的模式租約（[holdFiles]／[withFiles]）。
 */
class GuardedDashcamApi(
    private val real: DashcamApi,
    private val access: DashcamAccess,
) : DashcamApi by real {
    override suspend fun syncTime(now: ZonedDateTime): DashcamApi.SetResult = access.command { real.syncTime(now) }

    override fun listFolder(folder: Folder): Flow<List<DashcamFile>> = flow {
        access.command { real.listFolder(folder).collect { emit(it) } }
    }

    override suspend fun listAll(): Map<Folder, List<DashcamFile>>? = access.command { real.listAll() }

    override suspend fun resolveUrl(sdPath: String): String? = access.command { real.resolveUrl(sdPath) }

    override suspend fun delete(sdPath: String): DashcamApi.SetResult = access.command { real.delete(sdPath) }

    override suspend fun startLive(view: DashcamApi.LiveView): DashcamApi.LiveSource = access.command { real.startLive(view) }

    override suspend fun afterLiveStarted() = access.command { real.afterLiveStarted() }

    override suspend fun stopLive() = access.command { real.stopLive() }

    override suspend fun recordingStatus(): DashcamApi.RecordingStatus? = access.command { real.recordingStatus() }

    override suspend fun cardStatus(): String? = access.command { real.cardStatus() }

    override suspend fun storageInfo(): DashcamApi.StorageInfo = access.command { real.storageInfo() }

    override suspend fun readSettings(): DashcamApi.SettingsSnapshot = access.command { real.readSettings() }

    override suspend fun changeSetting(def: SettingDef, value: String): String? = access.command { real.changeSetting(def, value) }

    override suspend fun formatCard(onProgress: (String) -> Unit): String = access.command { real.formatCard(onProgress) }
}
