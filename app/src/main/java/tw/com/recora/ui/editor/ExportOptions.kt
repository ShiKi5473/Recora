package tw.com.recora.ui.editor

import tw.com.recora.data.ExportCodec
import tw.com.recora.data.ExportQuality
import tw.com.recora.data.ExportResolution
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 匯出設定換算成 Transformer 的參數（純函式，方便測試）。 */
object ExportOptions {
    /** 輸出高度：只縮小、不放大，不用縮放時回 null。[sourceHeight] 未知（0）時照設定縮。 */
    fun outputHeight(sourceHeight: Int, resolution: ExportResolution): Int? =
        resolution.height?.takeIf { sourceHeight <= 0 || it < sourceHeight }

    /**
     * 要求編碼器使用的位元率（bps），null = 交給編碼器（Media3 預設約每像素每格 0.14 bit）。
     * 標準畫質的 H.264 維持預設；H.265 壓縮率較好，同畫質約 6 成位元率就夠，檔案才會真的比較小。
     */
    fun bitrate(width: Int, height: Int, fps: Float, codec: ExportCodec, quality: ExportQuality): Int? {
        if (codec == ExportCodec.Original) return null
        if (codec == ExportCodec.H264 && quality == ExportQuality.Standard) return null
        val bitsPerPixel = when (quality) {
            ExportQuality.High -> 0.21
            ExportQuality.Standard -> DEFAULT_BITS_PER_PIXEL
            ExportQuality.Small -> 0.05
        }
        val codecFactor = if (codec == ExportCodec.H265) 0.6 else 1.0
        return (width.toDouble() * height * fps * bitsPerPixel * codecFactor).roundToInt()
    }

    /**
     * 預估用的影像位元率（bps）：[bitrate] 指定的，或編碼器的預設。
     * 不重新編碼時是來源 TS 的平均位元率 [sourceBitrate]（含子串流與聲音，由學到的倍數校正成只剩主串流）；
     * 讀不到來源大小時，以記錄器 H.265 標準畫質估。
     */
    fun videoBitrate(width: Int, height: Int, fps: Float, codec: ExportCodec, quality: ExportQuality, sourceBitrate: Int? = null): Int =
        if (codec == ExportCodec.Original) {
            sourceBitrate ?: videoBitrate(width, height, fps, ExportCodec.H265, ExportQuality.Standard)
        } else {
            bitrate(width, height, fps, codec, quality) ?: (width.toDouble() * height * fps * DEFAULT_BITS_PER_PIXEL).roundToInt()
        }

    /** 來源檔的平均位元率（bps）：總大小 ÷ 總長度；長度未知時回 null。 */
    fun averageBitrate(totalBytes: Long, totalDurationMs: Long): Int? =
        if (totalBytes <= 0 || totalDurationMs <= 0) null else (totalBytes * 8_000.0 / totalDurationMs).roundToInt()

    /**
     * 預估匯出檔大小（bytes）=（影像位元率 × [factor] + 聲音位元率）× 長度，加上 MP4 容器約 1% 的額外負擔。
     * [factor] = 這支手機的編碼器實際輸出／要求的位元率（[measuredFactor] 從之前的匯出學來的，沒有就是 1）；
     * [audioBitrate] = 0 表示沒有聲音。編碼器是變動位元率，畫面變化少（例如停車）會比預估小。
     */
    fun estimatedBytes(durationMs: Long, videoBitrate: Int, audioBitrate: Int, factor: Float = 1f): Long =
        ((videoBitrate * factor.toDouble() + audioBitrate) * durationMs.coerceAtLeast(0) / 8_000.0 * CONTAINER_OVERHEAD).roundToLong()

    /**
     * 從一次匯出的結果算出編碼器實際輸出／要求的影像位元率。實測小米的 H.264 編碼器固定多出約 24%，
     * 只靠公式會低估。太短的片段誤差大，不採用（回 null）。
     */
    fun measuredFactor(fileBytes: Long, durationMs: Long, videoBitrate: Int, audioBitrate: Int): Float? {
        if (durationMs < MIN_LEARN_MS || videoBitrate <= 0 || fileBytes <= 0) return null
        val totalBps = fileBytes * 8_000.0 / durationMs / CONTAINER_OVERHEAD
        return ((totalBps - audioBitrate) / videoBitrate).toFloat().coerceIn(0.3f, 3f)
    }

    /** 新的實測倍數併進之前的（各占一半，平均掉畫面內容的差異）；之前沒有就直接用新的。 */
    fun mergeFactor(old: Float?, measured: Float): Float = if (old == null) measured else (old + measured) / 2

    /** 來源的聲音沒標位元率時的估計值：記錄器的 16 kHz AAC 實測約 32 kbps，匯出時直接沿用、不重新編碼。 */
    const val DEFAULT_AUDIO_BITRATE = 32_000

    private const val MIN_LEARN_MS = 5_000L

    /** 沒指定位元率時 Media3 編碼器的預設（1.11.1 的 DefaultEncoderFactory：寬 × 高 × fps × 0.07 × 2）。 */
    private const val DEFAULT_BITS_PER_PIXEL = 0.14

    private const val CONTAINER_OVERHEAD = 1.01
}
