package tw.com.mufudashcam.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 記錄器上的一個檔案。size = -1 表示未知（由前鏡頭推算出來、尚未確認存在的後鏡頭檔）。 */
data class DashcamFile(
    val name: String,
    val sdPath: String,
    val size: Long,
    val time: String,
    val folder: Folder,
    val guessed: Boolean = false,
) {
    val info: FileNames.Info? = FileNames.parse(name)
    val camera: Camera? get() = FileNames.cameraOf(sdPath)
    val isImage: Boolean get() = FileNames.isImage(name)
}

/** 同一時間點的前、後鏡頭檔案。 */
data class Clip(val key: String, val front: DashcamFile?, val rear: DashcamFile?) {
    val primary: DashcamFile get() = front ?: rear!!
    val files: List<DashcamFile> get() = listOfNotNull(front, rear)
    val dateTime: LocalDateTime? = primary.info?.dateTime ?: parseXmlTime(primary.time)
    val date: LocalDate? get() = dateTime?.toLocalDate()

    private companion object {
        val XML_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        fun parseXmlTime(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s.trim(), XML_TIME) }.getOrNull()
    }
}

object Clips {
    /**
     * 把列表檔案依時間點配對成 [Clip]，最新的排前面。
     * 如果整個列表都沒有後鏡頭檔（記錄器可能只列前鏡頭），就用 [FileNames.rearPathOf]
     * 推算後鏡頭路徑，標成 guessed，下載時才確認是否存在。
     */
    fun group(files: List<DashcamFile>): List<Clip> {
        val listsRear = files.any { it.camera == Camera.Rear }
        val byKey = LinkedHashMap<String, MutableList<DashcamFile>>()
        for (f in files) byKey.getOrPut(f.info?.key ?: f.name) { mutableListOf() } += f

        return byKey.map { (key, list) ->
            val front = list.firstOrNull { it.camera != Camera.Rear }
            var rear = list.firstOrNull { it.camera == Camera.Rear }
            if (rear == null && !listsRear && front != null) {
                rear = FileNames.rearPathOf(front.sdPath)?.let { p ->
                    DashcamFile(FileNames.basename(p), p, -1, front.time, front.folder, guessed = true)
                }
            }
            Clip(key, front, rear)
        }.sortedWith(compareByDescending<Clip> { it.dateTime }.thenByDescending { it.key })
    }
}
