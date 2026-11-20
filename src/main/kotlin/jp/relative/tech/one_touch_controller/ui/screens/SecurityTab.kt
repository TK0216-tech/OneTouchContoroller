package jp.relative.tech.one_touch_controller.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import jp.relative.tech.one_touch_controller.domain.ports.GuardState
import jp.relative.tech.one_touch_controller.domain.security.GuardPassword
import jp.relative.tech.one_touch_controller.ui.theme.LocalOtcColors
import jp.relative.tech.one_touch_controller.ui.theme.OtcCardShape
import jp.relative.tech.one_touch_controller.ui.theme.OtcKeyShape
import jp.relative.tech.one_touch_controller.ui.theme.tr

@Composable
fun SecurityTab(
    guardState: GuardState,
    guardPassword: String,
    onGuardPasswordChange: (String) -> Unit,
    onToggleGuard: () -> Unit,
    onSavePassword: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalOtcColors.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ----- Guard mode: block the PC's own mouse and keyboard while you are away -----
        Surface(
            shape = OtcCardShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("Guard mode"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                guardState.active -> tr("On: the PC's own mouse and keyboard are blocked. Your phone still works.")
                                guardState.hasPassword -> tr("Off. Turn it on from your phone when you leave the PC.")
                                else -> tr("Set a Guard password first.")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (guardState.active) colors.accent else colors.muted
                        )
                    }
                    Button(
                        shape = OtcKeyShape,
                        enabled = guardState.hasPassword || guardState.active,
                        onClick = onToggleGuard
                    ) { Text(if (guardState.active) tr("Turn off") else tr("Turn on")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = guardPassword,
                        onValueChange = { input ->
                            onGuardPasswordChange(input.filter { c -> c.isLetterOrDigit() }.take(GuardPassword.MAX_LENGTH))
                        },
                        label = { Text(if (guardState.hasPassword) tr("New Guard password") else tr("Guard password")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(shape = OtcKeyShape, onClick = onSavePassword) {
                        Text(tr("Save password"))
                    }
                }
                Text(
                    tr("Letters and numbers, 4 to 32, capital letters do not matter. Anyone at the PC must type it to unlock. Ctrl+Alt+Del and holding the power button cannot be blocked by any program."),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.muted
                )
            }
        }
    }
}
