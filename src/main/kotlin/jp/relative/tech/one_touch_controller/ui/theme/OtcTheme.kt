package jp.relative.tech.one_touch_controller.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import jp.relative.tech.one_touch_controller.application.I18n
import jp.relative.tech.one_touch_controller.domain.config.AccentSpec
import jp.relative.tech.one_touch_controller.domain.config.AppLanguage
import jp.relative.tech.one_touch_controller.domain.config.AppSettings
import jp.relative.tech.one_touch_controller.domain.config.StringTranslator

@Immutable
data class OtcColors(
    val ink: Color,        // window background
    val panel: Color,      // cards
    val key: Color,        // secondary buttons
    val line: Color,       // borders
    val text: Color,
    val muted: Color,
    val danger: Color,
    val accent: Color,
    val accentInk: Color
)

fun rgbColor(rgb: Int): Color = Color(0xFF000000L or (rgb.toLong() and 0xFFFFFFL))

fun AccentSpec.composeColor(dark: Boolean): Color = rgbColor(if (dark) onDarkRgb else onLightRgb)

fun AccentSpec.composeInkColor(dark: Boolean): Color = if (dark) rgbColor(inkOnDarkRgb) else Color.White

fun otcColors(settings: AppSettings): OtcColors {
    val accentSpec = settings.currentAccent()
    return if (settings.dark) {
        OtcColors(
            ink = Color(0xFF0E1420),
            panel = Color(0xFF161E2D),
            key = Color(0xFF263148),
            line = Color(0xFF2C3850),
            text = Color(0xFFE8EDF7),
            muted = Color(0xFF8E9BB3),
            danger = Color(0xFFFF7A7A),
            accent = accentSpec.composeColor(dark = true),
            accentInk = accentSpec.composeInkColor(dark = true)
        )
    } else {
        OtcColors(
            ink = Color(0xFFF3F5F9),
            panel = Color(0xFFFFFFFF),
            key = Color(0xFFE3E8F1),
            line = Color(0xFFD3DAE6),
            text = Color(0xFF131A26),
            muted = Color(0xFF5D6B85),
            danger = Color(0xFFD83A3A),
            accent = accentSpec.composeColor(dark = false),
            accentInk = accentSpec.composeInkColor(dark = false)
        )
    }
}

fun otcPcColorScheme(settings: AppSettings, colors: OtcColors = otcColors(settings)): ColorScheme {
    val container = colors.accent.copy(alpha = 0.22f).compositeOver(colors.ink)
    return if (settings.dark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.accentInk,
        primaryContainer = container, onPrimaryContainer = colors.accent,
        secondary = colors.accent, onSecondary = colors.accentInk,
        secondaryContainer = colors.key, onSecondaryContainer = colors.text,
        tertiary = colors.accent, onTertiary = colors.accentInk,
        background = colors.ink, onBackground = colors.text,
        surface = colors.ink, onSurface = colors.text,
        surfaceVariant = colors.panel, onSurfaceVariant = colors.text,
        outline = colors.line, outlineVariant = colors.line,
        error = colors.danger, onError = Color(0xFF3A0B0B)
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.accentInk,
        primaryContainer = container, onPrimaryContainer = colors.accent,
        secondary = colors.accent, onSecondary = colors.accentInk,
        secondaryContainer = colors.key, onSecondaryContainer = colors.text,
        tertiary = colors.accent, onTertiary = colors.accentInk,
        background = colors.ink, onBackground = colors.text,
        surface = colors.ink, onSurface = colors.text,
        surfaceVariant = colors.panel, onSurfaceVariant = colors.text,
        outline = colors.line, outlineVariant = colors.line,
        error = colors.danger, onError = Color.White
    )
}

val OtcKeyShape = RoundedCornerShape(12.dp)
val OtcCardShape = RoundedCornerShape(16.dp)

val OtcPcShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

val LocalOtcColors = staticCompositionLocalOf { otcColors(AppSettings()) }
val LocalTranslator = staticCompositionLocalOf<StringTranslator> { I18n.forLanguage(AppLanguage.EN) }

@Composable
fun tr(text: String, vararg values: Any?): String = LocalTranslator.current.tr(text, *values)

@Composable
fun OtcTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val colors = otcColors(settings)
    val translator = I18n.forLanguage(settings.language)
    CompositionLocalProvider(
        LocalOtcColors provides colors,
        LocalTranslator provides translator
    ) {
        MaterialTheme(
            colorScheme = otcPcColorScheme(settings, colors),
            shapes = OtcPcShapes,
            content = content
        )
    }
}
