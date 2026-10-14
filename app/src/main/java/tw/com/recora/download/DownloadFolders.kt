package tw.com.recora.download

import tw.com.recora.data.DashcamFile
import tw.com.recora.data.SubfolderMode

/** 下載的檔案放在 Movies/Recora（照片 Pictures/Recora）底下的哪個子資料夾。 */
object DownloadFolders {
    private val XML_DATE = Regex("""^\d{4}-\d{2}-\d{2}""")

    /** 子資料夾名稱，null = 直接放在 App 的資料夾裡。依類型時照片不再分（本來就在 Pictures）。 */
    fun subdir(file: DashcamFile, mode: SubfolderMode): String? = when (mode) {
        SubfolderMode.None -> null
        SubfolderMode.ByFolder -> if (file.isImage) null else file.folder.label
        // 2026-09-23：檔名讀不出時間就用列表的時間
        SubfolderMode.ByDate -> file.info?.dateTime?.toLocalDate()?.toString() ?: XML_DATE.find(file.time.trim())?.value
    }
}
