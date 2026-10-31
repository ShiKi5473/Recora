package tw.com.recora.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 記錄器上的一個檔案。size = -1 表示未知（由前鏡頭推算出來、尚未確認存在的後鏡頭檔）。
 * [brand] = 哪一種記錄器上的檔案：下載佇列只在連著同一種記錄器時才下載它。
 */
data class DashcamFile(
    val name: String,
    val sdPath: String,
    val size: Long,
    val time: String,
    val folder: Folder,
    val guessed: Boolean = false,
    val brand: Brand = Brand.Mufu,
    /** 記錄器標成唯讀（Astro 的緊急錄影 ATTR 33）：記錄器拒絕刪除（4003 回 Status -4），本 App 不送。 */
    val readOnly: Boolean = false,
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

/** 下載哪個鏡頭。 */
enum class DownloadChoice(val label: String) {
    Both("下載前+後鏡頭"),
    FrontOnly("只下載前鏡頭"),
    RearOnly("只下載後鏡頭"),
}

object Clips {
    /** 這一段要下載的檔案；沒有前鏡頭時「只下載前鏡頭」改下載後鏡頭，「只下載後鏡頭」則不退而求其次。 */
    fun filesFor(clip: Clip, choice: DownloadChoice): List<DashcamFile> = when (choice) {
        DownloadChoice.Both -> clip.files
        DownloadChoice.FrontOnly -> listOfNotNull(clip.front ?: clip.rear)
        DownloadChoice.RearOnly -> listOfNotNull(clip.rear)
    }

    /** 依「預設下載後鏡頭」設定，這一段要下載的檔案。 */
    fun filesFor(clip: Clip, includeRear: Boolean): List<DashcamFile> =
        filesFor(clip, if (includeRear) DownloadChoice.Both else DownloadChoice.FrontOnly)

    /** 從記錄器刪除這一段時要刪的檔案：前鏡頭，加上後鏡頭（[includeRear]，或這一段只有後鏡頭時）。 */
    fun filesToDelete(clip: Clip, includeRear: Boolean): List<DashcamFile> =
        listOfNotNull(clip.front, clip.rear?.takeIf { includeRear || clip.front == null })

    /** 已經下載到手機（[downloaded]：大寫檔名 → 大小）且大小相符；記錄器上大小未知時只看檔名。 */
    fun isDownloaded(file: DashcamFile, downloaded: Map<String, Long>): Boolean {
        val local = downloaded[file.name.uppercase()] ?: return false
        return file.size <= 0 || local == file.size
    }

    /** 這一段還有檔案沒下載（推算出來的後鏡頭不算，不一定存在）。 */
    fun isMissing(clip: Clip, includeRear: Boolean, downloaded: Map<String, Long>): Boolean =
        filesFor(clip, includeRear).any { !it.guessed && !isDownloaded(it, downloaded) }

    /** 記錄器上這一段的檔案（推算出來的後鏡頭除外）全都下載到手機了：可以放心從記錄器刪除。 */
    fun isBackedUp(clip: Clip, downloaded: Map<String, Long>): Boolean {
        val listed = clip.files.filter { !it.guessed }
        return listed.isNotEmpty() && listed.all { isDownloaded(it, downloaded) }
    }

    /** 備份用：還沒下載的檔案（推算出來的後鏡頭不算，免得每次都加入一個下載時才發現不存在的檔案）。 */
    fun notDownloaded(clips: List<Clip>, includeRear: Boolean, downloaded: Map<String, Long>): List<DashcamFile> =
        clips.flatMap { filesFor(it, includeRear) }.filter { !it.guessed && !isDownloaded(it, downloaded) }

    /**
     * 把列表檔案依時間點配對成 [Clip]，最新的排前面。
     * 如果整個列表都沒有後鏡頭檔（reardir 還沒讀到或沒有回應），就用 [FileNames.rearPathOf]
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
                    DashcamFile(FileNames.basename(p), p, -1, front.time, front.folder, guessed = true, brand = front.brand)
                }
            }
            Clip(key, front, rear)
        }.sortedWith(compareByDescending<Clip> { it.dateTime }.thenByDescending { it.key })
    }
}
