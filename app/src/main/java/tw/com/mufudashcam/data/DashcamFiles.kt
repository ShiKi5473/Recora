package tw.com.mufudashcam.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.com.mufudashcam.data.api.DashcamApi
import tw.com.mufudashcam.data.net.ConnState
import tw.com.mufudashcam.data.net.DashcamConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * 記錄器檔案列表的快取，「檔案」頁與「裝置」頁共用。
 *
 * 手機連線期間記錄器停在 Standby、不會錄影，整段連線期間列表都不會變，所以每個資料夾只讀一次：
 * 斷線時清空，刪除時直接從快取移除，格式化後 [invalidateAll]。
 * 記錄器一次只服務一個連線，同一時間只讀一個資料夾，其他人排隊等（等到時多半已經有快取）。
 */
class DashcamFiles(
    private val api: DashcamApi,
    connection: DashcamConnection,
    scope: CoroutineScope,
) {
    private val cache = ConcurrentHashMap<Folder, List<DashcamFile>>()
    private val mutex = Mutex()

    /** 每次清空快取加一：讀到一半斷線的舊列表不會寫進下一次連線的快取。 */
    @Volatile
    private var generation = 0

    init {
        scope.launch {
            connection.state.map { it is ConnState.Connected }.distinctUntilChanged().collect { connected ->
                if (!connected) invalidateAll()
            }
        }
    }

    /**
     * 讀取資料夾：有快取就直接送出；否則逐頁讀取，每讀到一頁就送出目前累積的完整清單。
     * [refresh] = 不管快取，重新讀取。
     */
    fun list(folder: Folder, refresh: Boolean = false): Flow<List<DashcamFile>> = flow {
        if (!refresh) cache[folder]?.let { emit(it); return@flow }
        mutex.withLock {
            // 排隊期間別人可能已經讀完了
            val ready = if (refresh) null else cache[folder]
            if (ready != null) {
                emit(ready)
                return@withLock
            }
            val gen = generation
            var all = emptyList<DashcamFile>()
            api.listFolder(folder).collect {
                all = it
                emit(it)
            }
            if (gen == generation) cache[folder] = all
        }
    }

    /** 從快取找記錄器上的檔案（例如在播放畫面加入下載）；那個資料夾還沒讀過就回 null。 */
    fun find(sdPath: String): DashcamFile? =
        cache.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.sdPath == sdPath } }

    /** 從快取移除已刪除的檔案，不必重新讀整個資料夾。 */
    fun forget(sdPaths: Collection<String>) {
        if (sdPaths.isEmpty()) return
        val gone = sdPaths.toSet()
        for (folder in cache.keys) {
            cache.computeIfPresent(folder) { _, list -> list.filterNot { it.sdPath in gone } }
        }
    }

    fun invalidateAll() {
        generation++
        cache.clear()
    }
}
