package tw.com.recora.ui.editor

import tw.com.recora.data.ExportCodec
import tw.com.recora.data.ExportQuality
import tw.com.recora.data.ExportResolution
import kotlin.math.roundToInt

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
        if (codec == ExportCodec.H264 && quality == ExportQuality.Standard) return null
        val bitsPerPixel = when (quality) {
            ExportQuality.High -> 0.21
            ExportQuality.Standard -> 0.14
            ExportQuality.Small -> 0.05
        }
        val codecFactor = if (codec == ExportCodec.H265) 0.6 else 1.0
        return (width.toDouble() * height * fps * bitsPerPixel * codecFactor).roundToInt()
    }
}
