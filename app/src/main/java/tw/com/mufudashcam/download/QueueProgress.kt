package tw.com.mufudashcam.download

/**
 * 下載頁標題下的整體進度：還沒結束的檔案（等待中＋下載中）、剩下的容量與預估時間。
 * [bytes] 不含大小未知的檔案（推算出來的後鏡頭），這時 [allSizesKnown] 為 false。
 * [etaSeconds] 用下載中那個檔案的速度估計；沒在下載（等待連線、串流播放時暫停）為 null。
 */
data class QueueProgress(val files: Int, val bytes: Long, val allSizesKnown: Boolean, val etaSeconds: Long?) {
    companion object {
        /** 佇列裡沒有還沒結束的檔案時回 null。 */
        fun of(tasks: List<DownloadTask>): QueueProgress? {
            val active = tasks.filter { it.isActive }
            if (active.isEmpty()) return null
            val sized = active.filter { it.total > 0 }
            val bytes = sized.sumOf { (it.total - it.bytes).coerceAtLeast(0) }
            val speed = active.firstOrNull { it.status == TaskStatus.Running }?.speedBps ?: 0
            return QueueProgress(
                files = active.size,
                bytes = bytes,
                allSizesKnown = sized.size == active.size,
                etaSeconds = if (speed > 0) bytes / speed else null,
            )
        }
    }
}
