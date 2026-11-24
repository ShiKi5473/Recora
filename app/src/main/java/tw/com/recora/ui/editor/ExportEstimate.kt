package tw.com.recora.ui.editor

import androidx.media3.common.MimeTypes
import tw.com.recora.data.AppSettings
import tw.com.recora.data.ExportCodec
import tw.com.recora.data.ExportQuality

/**
 * 預覽播放器讀到的來源資訊（剪輯畫面在播放器讀到影音軌、量完選取範圍時更新），算匯出規格與預估大小用。
 * 讀不到的欄位留 0／null。
 */
data class ExportSource(
    val width: Int = 0,
    val height: Int = 0,
    /** 影格率；讀不到為 0 或負數。 */
    val frameRate: Float = 0f,
    /** 來源聲音軌標的位元率（沒標為 0 或負數）；null = 沒有聲音軌（例如後鏡頭）。 */
    val audioBitrate: Int? = null,
    /** 第一段的副檔名（TS、MP4）：接起來的段都是同一台記錄器。 */
    val container: String = "",
    /** 所有段的平均位元率（總大小 ÷ 總長度）。 */
    val averageBitrate: Int? = null,
    /** 選取範圍內來源的實際資料量（[SourceRangeBytes]）；還沒量好為 null。 */
    val rangeBytes: SourceRangeBytes.Bytes? = null,
)

/** 匯出的輸出規格；[scaledHeight] = null 表示維持原始解析度（不縮放）。 */
data class OutputSpec(val width: Int, val height: Int, val scaledHeight: Int?, val fps: Float, val codec: ExportCodec)

/**
 * 依「App 設定」的匯出設定與來源，算出輸出規格、要求編碼器的位元率與預估檔案大小（純計算，方便測試；公式見 [ExportOptions]）。
 * [codec] = 實際要用的編碼（手機沒有 H.265 編碼器時已換成 H.264），[clipMs] = 選取範圍的長度。
 * 讀不到來源的解析度、影格率時以 1920×1080、30 fps 計算；不重新編碼時解析度跟原檔一樣，不看解析度設定。
 */
class ExportEstimate(
    private val settings: AppSettings,
    codec: ExportCodec,
    private val source: ExportSource,
    private val clipMs: Long,
    private val removeAudio: Boolean,
) {
    val spec: OutputSpec = run {
        val srcW = source.width.takeIf { it > 0 } ?: 1920
        val srcH = source.height.takeIf { it > 0 } ?: 1080
        val scaledHeight = if (codec == ExportCodec.Original) null else ExportOptions.outputHeight(srcH, settings.exportResolution)
        val outW = if (scaledHeight != null) srcW * scaledHeight / srcH else srcW
        OutputSpec(outW, scaledHeight ?: srcH, scaledHeight, source.frameRate.takeIf { it > 0 } ?: 30f, codec)
    }

    /** 匯出請求的影像編碼；null = 不重新編碼。 */
    val videoMimeType: String? = when (codec) {
        ExportCodec.H264 -> MimeTypes.VIDEO_H264
        ExportCodec.H265 -> MimeTypes.VIDEO_H265
        ExportCodec.Original -> null
    }

    /** 匯出請求的位元率（[ExportOptions.bitrate]）；null = 交給編碼器。 */
    val requestBitrate: Int? = ExportOptions.bitrate(spec.width, spec.height, spec.fps, spec.codec, settings.exportQuality)

    /** 要求編碼器的影像位元率（見 [ExportOptions.videoBitrate]）；不重新編碼時是來源的平均位元率。 */
    val requestedVideoBitrate: Int =
        ExportOptions.videoBitrate(spec.width, spec.height, spec.fps, spec.codec, settings.exportQuality, source.averageBitrate)

    /** 原檔這一段的影像位元率；還沒量好時用整支的平均，都沒有為 null。 */
    val rangeBitrate: Int? = source.rangeBytes?.let { ExportOptions.averageBitrate(it.video, clipMs) } ?: source.averageBitrate

    /** 重新編碼 MP4（Astro 的 H.264）時用畫面複雜度預估（見 [ExportOptions.complexityBitrate]）。 */
    val usesComplexity: Boolean = source.container == "MP4" && codec != ExportCodec.Original && rangeBitrate != null

    /** 預估用的影像位元率：要求的位元率，或依畫面複雜度預估的實際輸出。 */
    val predictedVideoBitrate: Int = run {
        val range = rangeBitrate
        if (!usesComplexity || range == null) return@run requestedVideoBitrate
        val pixelRatio = if (source.width > 0 && source.height > 0) {
            spec.width.toDouble() * spec.height / (source.width * source.height)
        } else {
            1.0
        }
        ExportOptions.complexityBitrate(requestedVideoBitrate, range, spec.codec, pixelRatio)
    }

    /** 學到的倍數的 key（見 [ExportOptions.factorKey]）。 */
    val factorKey: String = ExportOptions.factorKey(spec.codec, spec.height, settings.exportQuality, source.container, usesComplexity)

    /** 匯出檔的聲音位元率；去除聲音或來源沒有聲音時是 0，沒標位元率時用估計值。 */
    val audioBitrate: Int = source.audioBitrate.let { bps ->
        if (removeAudio || bps == null) 0 else bps.takeIf { it > 0 } ?: ExportOptions.DEFAULT_AUDIO_BITRATE
    }

    /** 預估匯出一支的檔案大小，用之前匯出學到的編碼器倍數校正；不重新編碼且量好了範圍時直接用量到的資料量。 */
    val estimatedBytes: Long = run {
        val measured = source.rangeBytes
        if (spec.codec == ExportCodec.Original && measured != null) {
            return@run ExportOptions.estimatedCopyBytes(measured.video, measured.audio, removeAudio)
        }
        val factor = ExportOptions.lookupFactor(settings.exportBitrateFactors, factorKey)
        ExportOptions.estimatedBytes(clipMs, predictedVideoBitrate, audioBitrate, factor ?: 1f)
    }

    /** 匯出設定裡各畫質的位元率說明用（不看來源的位元率）。 */
    fun qualityBitrate(quality: ExportQuality): Int =
        ExportOptions.videoBitrate(spec.width, spec.height, spec.fps, spec.codec, quality)

    /** 匯出後實測的倍數 [measured] 併進之前的 [old]：用畫面複雜度預估時記的是修正倍數，合併方式不同。 */
    fun mergedFactor(old: Float?, measured: Float): Float =
        if (usesComplexity) ExportOptions.mergeCorrection(old, measured) else ExportOptions.mergeFactor(old, measured)
}
