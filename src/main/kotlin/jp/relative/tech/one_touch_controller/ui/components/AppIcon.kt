package jp.relative.tech.one_touch_controller.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import jp.relative.tech.one_touch_controller.domain.config.AppSettings
import jp.relative.tech.one_touch_controller.ui.theme.LocalOtcColors
import jp.relative.tech.one_touch_controller.ui.theme.otcColors

// Icon drawn with code, used for the window, the taskbar and the tray.

/** A tile in [tile] colour with a power symbol in [ink] colour. */
fun appIconPainter(tile: Color, ink: Color): Painter = object : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val d = size.minDimension
        val w = d / 11f
        drawRoundRect(tile, cornerRadius = CornerRadius(d / 4.2f))
        drawArc(
            color = ink, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(d * 0.26f, d * 0.28f), size = Size(d * 0.48f, d * 0.48f),
            style = Stroke(width = w, cap = StrokeCap.Round)
        )
        drawLine(ink, Offset(d / 2f, d * 0.2f), Offset(d / 2f, d * 0.5f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** The icon in the accent colour chosen in Settings. Drawn again when the colour or the theme changes. */
@Composable
fun rememberAppIcon(settings: AppSettings): Painter {
    val colors = otcColors(settings)
    return remember(colors.accent, colors.accentInk) { appIconPainter(colors.accent, colors.accentInk) }
}

@Composable
fun rememberAppIcon(): Painter {
    val colors = LocalOtcColors.current
    return remember(colors.accent, colors.accentInk) { appIconPainter(colors.accent, colors.accentInk) }
}
