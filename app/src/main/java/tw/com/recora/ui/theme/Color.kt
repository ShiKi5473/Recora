package tw.com.recora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 品牌色（依 docs/logo.png）：米白紙感底、炭灰線條、酒紅錄影點。
 * 淺色的酒紅比 logo 的 #9D4B51 略深，白字才有足夠的對比；深色改用柔和的粉紅酒紅。
 */
internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF8E3B44),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9DB),
    onPrimaryContainer = Color(0xFF3A0A12),
    inversePrimary = Color(0xFFFFB3B8),
    secondary = Color(0xFF5E5A56),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E1D9),
    onSecondaryContainer = Color(0xFF1F1B18),
    tertiary = Color(0xFF6A5E3A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E4BC),
    onTertiaryContainer = Color(0xFF231B00),
    background = Color(0xFFF7F2EC),
    onBackground = Color(0xFF1F1B18),
    surface = Color(0xFFF7F2EC),
    onSurface = Color(0xFF1F1B18),
    surfaceVariant = Color(0xFFE8E1D8),
    onSurfaceVariant = Color(0xFF4D4640),
    surfaceTint = Color(0xFF8E3B44),
    inverseSurface = Color(0xFF34302C),
    inverseOnSurface = Color(0xFFF6EFE8),
    // error 偏橘紅，免得和酒紅的 primary 混在一起
    error = Color(0xFFB3351A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD2),
    onErrorContainer = Color(0xFF410A00),
    outline = Color(0xFF7F776F),
    outlineVariant = Color(0xFFD0C8BE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F2EC),
    surfaceDim = Color(0xFFDAD3CA),
    surfaceContainerLowest = Color(0xFFFFFBF6),
    surfaceContainerLow = Color(0xFFF2ECE4),
    surfaceContainer = Color(0xFFEDE6DD),
    surfaceContainerHigh = Color(0xFFE7E0D7),
    surfaceContainerHighest = Color(0xFFE1DAD1),
)

internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFE8A0A6),
    onPrimary = Color(0xFF4A1219),
    primaryContainer = Color(0xFF6C2630),
    onPrimaryContainer = Color(0xFFFFD9DB),
    inversePrimary = Color(0xFF8E3B44),
    secondary = Color(0xFFCFC8BF),
    onSecondary = Color(0xFF34302C),
    secondaryContainer = Color(0xFF4A4540),
    onSecondaryContainer = Color(0xFFEBE4DB),
    tertiary = Color(0xFFD6C48F),
    onTertiary = Color(0xFF3A2F0B),
    tertiaryContainer = Color(0xFF52461F),
    onTertiaryContainer = Color(0xFFF3E4BC),
    background = Color(0xFF1A1816),
    onBackground = Color(0xFFE8E2DA),
    surface = Color(0xFF1A1816),
    onSurface = Color(0xFFE8E2DA),
    surfaceVariant = Color(0xFF4A453F),
    onSurfaceVariant = Color(0xFFCDC5BB),
    surfaceTint = Color(0xFFE8A0A6),
    inverseSurface = Color(0xFFE8E2DA),
    inverseOnSurface = Color(0xFF34302C),
    error = Color(0xFFFFB4A0),
    onError = Color(0xFF5C1500),
    errorContainer = Color(0xFF7A2308),
    onErrorContainer = Color(0xFFFFDAD2),
    outline = Color(0xFF978F85),
    outlineVariant = Color(0xFF4A453F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF403C38),
    surfaceDim = Color(0xFF1A1816),
    surfaceContainerLowest = Color(0xFF141210),
    surfaceContainerLow = Color(0xFF211E1B),
    surfaceContainer = Color(0xFF262320),
    surfaceContainerHigh = Color(0xFF312D2A),
    surfaceContainerHighest = Color(0xFF3C3835),
)

/** Material 色票沒有的語意色：成功（已下載）與錄影中的紅點。 */
@Immutable
data class RecoraColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val recordDot: Color,
)

internal val LightRecoraColors = RecoraColors(
    success = Color(0xFF2E6B3F),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFC9EBCF),
    onSuccessContainer = Color(0xFF00210B),
    recordDot = Color(0xFFC62828),
)

internal val DarkRecoraColors = RecoraColors(
    success = Color(0xFF8FCB9B),
    onSuccess = Color(0xFF00391A),
    successContainer = Color(0xFF1F5130),
    onSuccessContainer = Color(0xFFC9EBCF),
    recordDot = Color(0xFFFF6B6B),
)

/** 疊在影片、照片上的遮罩（取代各處零散的 alpha 值）：[light] 墊在小標籤、按鈕後面，[strong] 墊在需要讀字的地方。 */
object Scrim {
    val light = Color.Black.copy(alpha = 0.4f)
    val strong = Color.Black.copy(alpha = 0.7f)
}
