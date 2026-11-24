package tw.com.recora

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.com.recora.data.AppSettings
import tw.com.recora.data.ExportCodec
import tw.com.recora.data.ExportQuality
import tw.com.recora.data.ExportResolution
import tw.com.recora.ui.editor.ExportEstimate
import tw.com.recora.ui.editor.ExportOptions
import tw.com.recora.ui.editor.ExportSource
import tw.com.recora.ui.editor.OutputSpec
import tw.com.recora.ui.editor.SourceRangeBytes

class ExportEstimateTest {
    private val mufu1440 = ExportSource(width = 2560, height = 1440, frameRate = 30f, audioBitrate = -1, container = "TS", averageBitrate = 12_000_000)

    private fun estimate(
        source: ExportSource,
        codec: ExportCodec = ExportCodec.H264,
        resolution: ExportResolution = ExportResolution.Original,
        quality: ExportQuality = ExportQuality.Standard,
        factors: Map<String, Float> = emptyMap(),
        clipMs: Long = 30_000,
        removeAudio: Boolean = false,
    ) = ExportEstimate(
        AppSettings(exportCodec = codec, exportResolution = resolution, exportQuality = quality, exportBitrateFactors = factors),
        codec, source, clipMs, removeAudio,
    )

    @Test
    fun unknownSourceFallsBackTo1080p30() {
        val e = estimate(ExportSource())
        assertEquals(OutputSpec(1920, 1080, null, 30f, ExportCodec.H264), e.spec)
        // 沒有聲音軌
        assertEquals(0, e.audioBitrate)
    }

    @Test
    fun scalesDownKeepingAspectAndCopyKeepsSourceSize() {
        assertEquals(OutputSpec(1920, 1080, 1080, 30f, ExportCodec.H265), estimate(mufu1440, ExportCodec.H265, ExportResolution.P1080).spec)
        // 不重新編碼：不看解析度設定
        assertEquals(OutputSpec(2560, 1440, null, 30f, ExportCodec.Original), estimate(mufu1440, ExportCodec.Original, ExportResolution.P720).spec)
    }

    @Test
    fun requestMatchesSettings() {
        val h265 = estimate(mufu1440, ExportCodec.H265, quality = ExportQuality.High)
        assertEquals(MimeTypes.VIDEO_H265, h265.videoMimeType)
        assertEquals(ExportOptions.bitrate(2560, 1440, 30f, ExportCodec.H265, ExportQuality.High), h265.requestBitrate)
        // 標準畫質 H.264 交給編碼器
        assertNull(estimate(mufu1440).requestBitrate)
        assertNull(estimate(mufu1440, ExportCodec.Original).videoMimeType)
    }

    @Test
    fun audioBitrate() {
        // 沒標位元率：用記錄器 AAC 的估計值
        assertEquals(ExportOptions.DEFAULT_AUDIO_BITRATE, estimate(mufu1440).audioBitrate)
        assertEquals(48_000, estimate(mufu1440.copy(audioBitrate = 48_000)).audioBitrate)
        assertEquals(0, estimate(mufu1440, removeAudio = true).audioBitrate)
    }

    @Test
    fun copyModeUsesMeasuredRangeBytes() {
        val measured = mufu1440.copy(rangeBytes = SourceRangeBytes.Bytes(video = 40_000_000, audio = 120_000))
        assertEquals(ExportOptions.estimatedCopyBytes(40_000_000, 120_000, false), estimate(measured, ExportCodec.Original).estimatedBytes)
        assertEquals(ExportOptions.estimatedCopyBytes(40_000_000, 120_000, true), estimate(measured, ExportCodec.Original, removeAudio = true).estimatedBytes)
        // 還沒量好：用整支的平均位元率估
        val e = estimate(mufu1440, ExportCodec.Original)
        assertEquals(ExportOptions.estimatedBytes(30_000, 12_000_000, ExportOptions.DEFAULT_AUDIO_BITRATE), e.estimatedBytes)
        assertEquals("Original|TS", e.factorKey)
    }

    @Test
    fun complexityOnlyForMp4WithKnownSourceBitrate() {
        val astro = ExportSource(
            width = 2560, height = 1440, frameRate = 30f, audioBitrate = 32_000, container = "MP4",
            averageBitrate = 8_000_000, rangeBytes = SourceRangeBytes.Bytes(video = 15_000_000, audio = 120_000),
        )
        val e = estimate(astro, ExportCodec.H264, ExportResolution.P720)
        assertTrue(e.usesComplexity)
        // 原檔這一段：15 MB ÷ 30 秒
        assertEquals(4_000_000, e.rangeBitrate)
        val pixelRatio = 1280.0 * 720 / (2560 * 1440)
        assertEquals(ExportOptions.complexityBitrate(e.requestedVideoBitrate, 4_000_000, ExportCodec.H264, pixelRatio), e.predictedVideoBitrate)
        assertEquals("H264|720|Standard|MP4/src", e.factorKey)
        // 修正倍數只吸收 2 成
        assertEquals(ExportOptions.mergeCorrection(null, 1.5f), e.mergedFactor(null, 1.5f))

        val ts = estimate(mufu1440, ExportCodec.H264, ExportResolution.P720)
        assertFalse(ts.usesComplexity)
        assertEquals(ts.requestedVideoBitrate, ts.predictedVideoBitrate)
        assertEquals("H264|720|Standard|TS", ts.factorKey)
        assertEquals(ExportOptions.mergeFactor(1f, 1.5f), ts.mergedFactor(1f, 1.5f))
    }

    @Test
    fun learnedFactorScalesEstimate() {
        val e = estimate(mufu1440, factors = mapOf("H264|1440|Standard|TS" to 1.24f))
        assertEquals(
            ExportOptions.estimatedBytes(30_000, e.predictedVideoBitrate, ExportOptions.DEFAULT_AUDIO_BITRATE, 1.24f),
            e.estimatedBytes,
        )
    }

    @Test
    fun qualityBitrateIgnoresSource() {
        val e = estimate(mufu1440, ExportCodec.H265, ExportResolution.P1080)
        assertEquals(ExportOptions.videoBitrate(1920, 1080, 30f, ExportCodec.H265, ExportQuality.Small), e.qualityBitrate(ExportQuality.Small))
    }
}
