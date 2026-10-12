package tw.com.mufudashcam.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import tw.com.mufudashcam.data.Clips
import tw.com.mufudashcam.data.DashcamFiles
import tw.com.mufudashcam.data.Folder
import tw.com.mufudashcam.data.SettingsStore

/**
 * 備份緊急錄影：把記錄器上還沒下載到手機的緊急錄影全部加入下載佇列，
 * 依「預設下載後鏡頭」決定要不要後鏡頭。列表用與「檔案」頁共用的快取。
 */
class EventBackup(
    private val files: DashcamFiles,
    private val sink: MediaStoreSink,
    private val downloads: DownloadRepository,
    private val settings: SettingsStore,
) {
    /** @return 加入佇列的檔案數；讀不到列表時丟出 IOException。 */
    suspend fun run(): Int {
        val listed = files.list(Folder.Event).lastOrNull().orEmpty()
        val downloaded = withContext(Dispatchers.IO) { sink.downloaded() }
        val todo = Clips.notDownloaded(Clips.group(listed), settings.current().includeRear, downloaded)
        return downloads.enqueue(todo)
    }
}
