package tw.com.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.com.dashcam.data.ExportCodec
import tw.com.dashcam.data.ExportQuality
import tw.com.dashcam.data.ExportResolution
import tw.com.dashcam.data.ThemeMode
import tw.com.dashcam.data.enumOr
import tw.com.dashcam.ui.editor.ExportOptions

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
    fun unknownStoredEnumFallsBackToDefault() {
        assertEquals(ThemeMode.Dark, enumOr("Dark", ThemeMode.System))
        assertEquals(ThemeMode.System, enumOr("Purple", ThemeMode.System))
        assertEquals(ExportQuality.Standard, enumOr(null, ExportQuality.Standard))
    }
}
