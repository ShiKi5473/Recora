package tw.com.recora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()

/** 以 M3 預設為底：中文行高拉到約 1.5 倍，標題用 Medium 字重。 */
internal val RecoraTypography = Typography(
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 21.sp),
    bodySmall = Base.bodySmall.copy(lineHeight = 18.sp),
)

/** 等寬數字：時間、容量、進度、百分比跳動時文字不會左右晃。 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
