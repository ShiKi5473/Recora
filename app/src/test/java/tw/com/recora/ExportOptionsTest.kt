package tw.com.recora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.com.recora.data.ExportCodec
import tw.com.recora.data.ExportQuality
import tw.com.recora.data.ExportResolution
import tw.com.recora.data.ThemeMode
import tw.com.recora.data.enumOr
import tw.com.recora.data.migrateFactorsV1
import tw.com.recora.data.parseFactors
import tw.com.recora.ui.editor.ExportOptions

class ExportOptionsTest {
    @Test
    fun outputHeightOnlyShrinks() {
        assertEquals(1080, ExportOptions.outputHeight(1440, ExportResolution.P1080))
        assertEquals(720, ExportOptions.outputHeight(1440, ExportResolution.P720))
        // 不放大、同高度也不必縮
        assertNull(ExportOptions.outputHeight(1080, ExportResolution.P1080))
        assertNull(ExportOptions.outputHeight(720, ExportResolution.P1080))
        assertNull(ExportOptions.outputHeight(1440, ExportResolution.Original))
        // 來源高度未知：照設定縮
        assertEquals(720, ExportOptions.outputHeight(0, ExportResolution.P720))
    }

    @Test
    fun standardH264KeepsEncoderDefault() {
        assertNull(ExportOptions.bitrate(2560, 1440, 30f, ExportCodec.H264, ExportQuality.Standard))
    }

    @Test
    fun bitrateScalesWithPixelsQualityAndCodec() {
        // 2560×1440×30 = 110,592,000 像素／秒
        assertEquals(23_224_320, ExportOptions.bitrate(2560, 1440, 30f, ExportCodec.H264, ExportQuality.High))
        assertEquals(5_529_600, ExportOptions.bitrate(2560, 1440, 30f, ExportCodec.H264, ExportQuality.Small))
        // H.265 同畫質約 6 成位元率；標準畫質也明確指定，檔案才會比 H.264 小
        assertEquals(9_289_728, ExportOptions.bitrate(2560, 1440, 30f, ExportCodec.H265, ExportQuality.Standard))
        assertEquals(829_440, ExportOptions.bitrate(1280, 720, 30f, ExportCodec.H265, ExportQuality.Small))
    }

    @Test
    fun videoBitrateFallsBackToEncoderDefault() {
        // 標準畫質的 H.264 交給編碼器：一樣用每像素每格 0.14 bit 估
        assertEquals(15_482_880, ExportOptions.videoBitrate(2560, 1440, 30f, ExportCodec.H264, ExportQuality.Standard))
        assertEquals(23_224_320, ExportOptions.videoBitrate(2560, 1440, 30f, ExportCodec.H264, ExportQuality.High))
    }

    @Test
    fun estimatedBytesFromBitrateAndDuration() {
        // (8 Mbps + 128 kbps) × 30 秒 ÷ 8 = 30,480,000 bytes，加 1% 容器負擔
        assertEquals(30_784_800L, ExportOptions.estimatedBytes(30_000, 8_000_000, 128_000))
        assertEquals(30_300_000L, ExportOptions.estimatedBytes(30_000, 8_000_000, 0))
        assertEquals(0L, ExportOptions.estimatedBytes(-5, 8_000_000, 0))
    }

    @Test
    fun factorLearnedFromRealExportFixesEstimate() {
        // 實測：小米手機 H.264 標準畫質 2560×1440、66.733 秒、去除聲音，檔案 160,205,096 bytes（要求 15.48 Mbps，實際約 19.2）
        val requested = ExportOptions.videoBitrate(2560, 1440, 30f, ExportCodec.H264, ExportQuality.Standard)
        val factor = ExportOptions.measuredFactor(160_205_096, 66_733, requested, 0)!!
        assertEquals(1.23f, factor, 0.01f)
        assertEquals(160_205_096.0, ExportOptions.estimatedBytes(66_733, requested, 0, factor).toDouble(), 100_000.0)
        // 太短的片段不採用
        assertNull(ExportOptions.measuredFactor(10_000_000, 3_000, requested, 0))
        assertEquals(1.2f, ExportOptions.mergeFactor(null, 1.2f), 0f)
        assertEquals(1.1f, ExportOptions.mergeFactor(1.0f, 1.2f), 0.0001f)
    }

    @Test
    fun originalUsesSourceBitrate() {
        // 不重新編碼：不要求編碼器位元率，預估用來源 TS 的平均位元率
        assertNull(ExportOptions.bitrate(2560, 1440, 30f, ExportCodec.Original, ExportQuality.High))
        // 實測來源：92,182,792 bytes、63.308 秒 ≈ 11.65 Mbps
        val source = ExportOptions.averageBitrate(92_182_792, 63_308)!!
        assertEquals(11_648_802, source)
        assertEquals(source, ExportOptions.videoBitrate(2560, 1440, 30f, ExportCodec.Original, ExportQuality.Small, source))
        // 讀不到來源大小：以 H.265 標準畫質估
        assertEquals(9_289_728, ExportOptions.videoBitrate(2560, 1440, 30f, ExportCodec.Original, ExportQuality.High))
        assertNull(ExportOptions.averageBitrate(0, 63_308))
        assertNull(ExportOptions.averageBitrate(92_182_792, 0))
    }

    @Test
    fun factorsAreKeptPerSetting() {
        assertEquals("H264|1440|Standard|TS", ExportOptions.factorKey(ExportCodec.H264, 1440, ExportQuality.Standard, "TS"))
        assertEquals("Original|MP4", ExportOptions.factorKey(ExportCodec.Original, 1440, ExportQuality.Small, "MP4"))
        // 實測：同一個 H.264 編碼器，1080p 省空間超出得比 1440p 標準多很多，不能共用
        val factors = mapOf(
            "H264|1440|Standard|TS" to 1.24f, "H264|1080|Small|TS" to 1.93f, "H264|1440|Small|TS" to 1.25f, "Original|TS" to 0.85f,
        )
        assertEquals(1.24f, ExportOptions.lookupFactor(factors, "H264|1440|Standard|TS")!!, 0f)
        // 沒匯出過的解析度：借用同編碼、同畫質、同來源容器的平均
        assertEquals(1.59f, ExportOptions.lookupFactor(factors, "H264|720|Small|TS")!!, 0.001f)
        // 不同畫質、不同編碼、不同來源容器都不借用：Mufu 的 TS 多一路子串流，Astro 的 MP4 沒有
        assertNull(ExportOptions.lookupFactor(factors, "H264|720|High|TS"))
        assertNull(ExportOptions.lookupFactor(factors, "H265|1440|Standard|TS"))
        assertNull(ExportOptions.lookupFactor(factors, "H264|1440|Standard|MP4"))
        assertNull(ExportOptions.lookupFactor(factors, "Original|MP4"))
    }

    @Test
    fun storedFactorsParse() {
        assertEquals(
            mapOf("H264|1440|Standard|TS" to 1.24f, "Original|TS" to 0.86f),
            parseFactors(setOf("H264|1440|Standard|TS=1.24", "Original|TS=0.86", "broken", "=1.0", "x=abc")),
        )
    }

    @Test
    fun copyEstimateUsesMeasuredSourceBytes() {
        // 不重新編碼：範圍內來源樣本的大小 + 約 1% 的 MP4 容器負擔；去除聲音時不算聲音
        assertEquals(25_250_000L, ExportOptions.estimatedCopyBytes(24_800_000, 200_000, removeAudio = false))
        assertEquals(25_048_000L, ExportOptions.estimatedCopyBytes(24_800_000, 200_000, removeAudio = true))
    }

    @Test
    fun factorsWithoutContainerBecomeTs() {
        // 還沒分來源容器時只匯出過 Mufu 的 TS
        assertEquals(
            mapOf("H264|1440|Standard|TS" to 1.21f, "Original|TS" to 0.85f),
            migrateFactorsV1(mapOf("H264|1440|Standard" to 1.21f, "Original" to 0.85f)),
        )
    }

    @Test
    fun unknownStoredEnumFallsBackToDefault() {
        assertEquals(ThemeMode.Dark, enumOr("Dark", ThemeMode.System))
        assertEquals(ThemeMode.System, enumOr("Purple", ThemeMode.System))
        assertEquals(ExportQuality.Standard, enumOr(null, ExportQuality.Standard))
    }
}
