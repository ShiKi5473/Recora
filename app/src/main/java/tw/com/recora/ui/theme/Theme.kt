package tw.com.recora.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val LocalRecoraColors = staticCompositionLocalOf { LightRecoraColors }

/** 語意色（成功、錄影紅點），用法同 `MaterialTheme.colorScheme`。 */
val MaterialTheme.recora: RecoraColors
    @Composable get() = LocalRecoraColors.current

/**
 * [dark] 與 [dynamicColor]（Android 12 以上依桌布配色）由「App 設定」決定；
 * 沒有開動態色彩時用錄刻的品牌色。
 */
@Composable
fun RecoraTheme(dark: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalRecoraColors provides if (dark) DarkRecoraColors else LightRecoraColors) {
        MaterialTheme(colorScheme = scheme, typography = RecoraTypography, shapes = RecoraShapes, content = content)
    }
}
