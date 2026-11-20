package jp.relative.tech.one_touch_controller.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** A small trash can, drawn with lines (so no extra icon library is needed). */
@Composable
fun TrashIcon(color: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawLine(color, Offset(w * 0.15f, h * 0.25f), Offset(w * 0.85f, h * 0.25f), stroke, StrokeCap.Round)   // lid
        drawLine(color, Offset(w * 0.38f, h * 0.12f), Offset(w * 0.62f, h * 0.12f), stroke, StrokeCap.Round)   // handle
        drawLine(color, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.32f, h * 0.9f), stroke, StrokeCap.Round)    // left side
        drawLine(color, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.68f, h * 0.9f), stroke, StrokeCap.Round)    // right side
        drawLine(color, Offset(w * 0.32f, h * 0.9f), Offset(w * 0.68f, h * 0.9f), stroke, StrokeCap.Round)     // bottom
        drawLine(color, Offset(w * 0.43f, h * 0.4f), Offset(w * 0.45f, h * 0.75f), stroke * 0.8f, StrokeCap.Round)
        drawLine(color, Offset(w * 0.57f, h * 0.4f), Offset(w * 0.55f, h * 0.75f), stroke * 0.8f, StrokeCap.Round)
    }
}
