package tw.com.recora.data

import tw.com.recora.data.api.DashcamApi
import java.io.IOException

/**
 * 從記錄器刪除檔案的規則，「檔案」頁多選刪除與播放器刪正在播的那段共用：
 * 唯讀的緊急錄影不送（Astro 的 ATTR 33，記錄器一律回 Status -4）；刪掉的從列表快取移除（不必重讀整個資料夾）。
 */
class DashcamDeleter(
    /** 目前的記錄器（每次使用時才取，見 AppContainer.api）。 */
    private val api: () -> DashcamApi,
    private val files: DashcamFiles,
) {
    sealed interface Result {
        data object Deleted : Result

        /** 被記錄器鎖定的緊急錄影：沒有送出。 */
        data object Protected : Result

        /** 記錄器回報失敗或連線錯誤；[reason] 是記錄器的回應或錯誤訊息。 */
        data class Failed(val reason: String) : Result
    }

    suspend fun delete(file: DashcamFile): Result = delete(file.sdPath, file.readOnly)

    /** 刪除記錄器上的 [sdPath]；[readOnly] 預設看列表快取（播放器只知道路徑）。 */
    suspend fun delete(sdPath: String, readOnly: Boolean = files.find(sdPath)?.readOnly == true): Result {
        if (readOnly) return Result.Protected
        val result = try {
            api().delete(sdPath)
        } catch (e: IOException) {
            return Result.Failed(e.message.orEmpty())
        }
        if (!result.ok) return Result.Failed(result.body)
        files.forget(listOf(sdPath))
        return Result.Deleted
    }

    /** 多選刪除的結果：[done]／[failed] 是幾「段」，[protectedFiles] 是略過的唯讀檔案數。 */
    data class Summary(val done: Int, val failed: Int, val protectedFiles: Int, val lastError: String?) {
        /** 給使用者看的結果。 */
        val message: String
            get() {
                val skipped = if (protectedFiles > 0) "緊急錄影被記錄器鎖定，略過 $protectedFiles 個檔案（要清除只能格式化記憶卡）" else null
                return listOfNotNull(
                    "已刪除 $done 段".takeIf { done > 0 || failed == 0 && skipped == null },
                    "$failed 段失敗（$lastError）".takeIf { failed > 0 },
                    skipped,
                ).joinToString("；")
            }
    }

    /**
     * 刪除幾段（[Clips.filesToDelete]：前鏡頭，加上後鏡頭 [includeRear]）。
     * 推算出來的後鏡頭檔不一定存在，刪除失敗不算錯。
     */
    suspend fun deleteClips(clips: List<Clip>, includeRear: Boolean): Summary {
        var done = 0
        var failed = 0
        var protectedFiles = 0
        var lastError: String? = null
        for (clip in clips) {
            var clipOk = true
            var clipDeleted = false
            for (f in Clips.filesToDelete(clip, includeRear)) {
                when (val r = delete(f)) {
                    Result.Deleted -> clipDeleted = true
                    Result.Protected -> protectedFiles++
                    is Result.Failed -> if (!f.guessed) {
                        clipOk = false
                        lastError = r.reason
                    }
                }
            }
            if (!clipOk) failed++ else if (clipDeleted) done++
        }
        return Summary(done, failed, protectedFiles, lastError)
    }
}
