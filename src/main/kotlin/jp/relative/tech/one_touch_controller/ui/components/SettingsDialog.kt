package jp.relative.tech.one_touch_controller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import jp.relative.tech.one_touch_controller.application.SettingsService
import jp.relative.tech.one_touch_controller.domain.config.AppLanguage
import jp.relative.tech.one_touch_controller.domain.config.AppSettings
import jp.relative.tech.one_touch_controller.domain.config.DEFAULT_ACCENTS
import jp.relative.tech.one_touch_controller.ui.theme.LocalOtcColors
import jp.relative.tech.one_touch_controller.ui.theme.composeColor
import jp.relative.tech.one_touch_controller.ui.theme.tr

@Composable
fun SettingsDialog(
    settings: AppSettings,
    settingsService: SettingsService,
    onClose: () -> Unit
) {
    val colors = LocalOtcColors.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = colors.panel,
        title = { Text(tr("Settings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Theme"), style = MaterialTheme.typography.labelLarge, color = colors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Choice(tr("Dark"), settings.dark) { settingsService.setDark(true) }
                    Choice(tr("Light"), !settings.dark) { settingsService.setDark(false) }
                }
                Text(tr("Accent color"), style = MaterialTheme.typography.labelLarge, color = colors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DEFAULT_ACCENTS.forEachIndexed { index, choice ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(choice.composeColor(settings.dark))
                                .then(if (index == settings.accentIndex) Modifier.border(3.dp, colors.text, CircleShape) else Modifier)
                                .clickable { settingsService.setAccentIndex(index) }
                        )
                    }
                }
                Text(tr("Language"), style = MaterialTheme.typography.labelLarge, color = colors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Choice("English", settings.language == AppLanguage.EN) { settingsService.setLanguage(AppLanguage.EN) }
                    Choice("日本語", settings.language == AppLanguage.JA) { settingsService.setLanguage(AppLanguage.JA) }
                    Choice("中文", settings.language == AppLanguage.ZH) { settingsService.setLanguage(AppLanguage.ZH) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Done")) } }
    )
}

/** One button of a row of choices; the chosen one is filled. */
@Composable
private fun RowScope.Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val padding = PaddingValues(horizontal = 4.dp)
    if (selected) {
        Button(onClick = onClick, shape = shape, contentPadding = padding, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, shape = shape, contentPadding = padding, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
    }
}
