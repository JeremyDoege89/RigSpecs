package dev.rigspecs.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// High-contrast dark theme for a dim apparatus bay: near-black surfaces, white text, and one
// strong accent. Contrast of every text/background pair here is above WCAG AA (most above AAA).
private val Accent = Color(0xFFFF6B3D)
private val AccentDim = Color(0xFF4A1E10)
private val Black = Color(0xFF0B0B0C)
private val Surface1 = Color(0xFF161618)
private val Surface2 = Color(0xFF202023)
private val Surface3 = Color(0xFF2B2B2F)
private val TextHigh = Color(0xFFF5F5F5)
private val TextMid = Color(0xFFC4C4C8)
private val Outline = Color(0xFF6E6E74)
private val Danger = Color(0xFFFF8A80)
private val Warn = Color(0xFFFFD54F)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Black,
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFF9CCBFF),
    onSecondary = Black,
    secondaryContainer = Color(0xFF1C3550),
    onSecondaryContainer = Color(0xFFD3E6FF),
    tertiary = Warn,
    onTertiary = Black,
    background = Black,
    onBackground = TextHigh,
    surface = Black,
    onSurface = TextHigh,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface3,
    outline = Outline,
    outlineVariant = Color(0xFF3A3A3F),
    error = Danger,
    onError = Black,
)

/** Amber used for "verify against the manual" on safety-critical specs. */
val WarnColor = Warn

/** Minimum height of anything tappable: generous for gloves. */
val TouchTarget = 56.dp

private val Base = Typography()
private val AppTypography = Typography(
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 17.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 15.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

/** The big number on a spec row. */
val ValueStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)

@Composable
fun RigSpecsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
