package jp.relative.tech.one_touch_controller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.relative.tech.one_touch_controller.ui.theme.LocalOtcColors
import jp.relative.tech.one_touch_controller.ui.theme.tr

// The tabs at the top of the PC window (the same style as the section bar of the phone apps).
// busy(index) = true shows a small dot: internet on, a routine running, Guard mode on.
@Composable
fun PcTabBar(selected: Int, onSelect: (Int) -> Unit, busy: (Int) -> Boolean) {
    val colors = LocalOtcColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panel)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("Home", "Internet", "Routines", "Security", "Log").forEachIndexed { index, name ->
            val on = index == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tr(name),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) colors.accent else colors.muted,
                    maxLines = 1
                )
                if (busy(index)) {
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.size(7.dp).background(colors.accent, CircleShape))
                }
            }
        }
    }
}
