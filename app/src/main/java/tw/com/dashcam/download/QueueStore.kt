package tw.com.dashcam.download

import tw.com.dashcam.data.Brand
import tw.com.dashcam.data.DashcamFile
import tw.com.dashcam.data.FileNames
import tw.com.dashcam.data.Folder
import java.io.File
import java.io.IOException

/**
 * 下載佇列存檔：App 被關掉後重開，還沒下載完的檔案仍在佇列裡，連上記錄器後接著下載
 * （續傳的位置仍靠 MediaStore 的 pending 檔，這裡只記要下載哪些檔案）。
 */
class QueueStore(private val file: File) {
    fun load(): List<DashcamFile> =
        try {
            if (file.exists()) decode(file.readText()) else emptyList()
        } catch (e: IOException) {
            emptyList()
        }

    /** 先寫暫存檔再改名，寫到一半被關掉也不會留下壞掉的檔案。 */
    fun save(files: List<DashcamFile>) {
        try {
            if (files.isEmpty()) {
                file.delete()
                return
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode(files))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: IOException) {
            // 存不起來只是重開後佇列是空的
        }
    }

    companion object {
        /** 一列一個檔案，欄位以 tab 分隔：sdPath、size、time、folder、guessed、brand。 */
        fun encode(files: List<DashcamFile>): String = files.joinToString("\n") { f ->
            listOf(f.sdPath, f.size, f.time.replace(Regex("[\t\n]"), " "), f.folder.name, f.guessed, f.brand.name)
                .joinToString("\t")
        }

        /** 讀不懂的列略過；支援 Astro 之前存的佇列沒有 brand 欄，都是 MUFU 的。 */
        fun decode(text: String): List<DashcamFile> = text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size !in 5..6 || !parts[0].startsWith("/")) return@mapNotNull null
            val size = parts[1].toLongOrNull() ?: return@mapNotNull null
            val folder = Folder.entries.firstOrNull { it.name == parts[3] } ?: return@mapNotNull null
            val brand = if (parts.size == 6) {
                Brand.entries.firstOrNull { it.name == parts[5] } ?: return@mapNotNull null
            } else {
                Brand.Mufu
            }
            DashcamFile(FileNames.basename(parts[0]), parts[0], size, parts[2], folder, guessed = parts[4] == "true", brand = brand)
        }.toList()
    }
}
