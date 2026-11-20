package jp.relative.tech.one_touch_controller.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.relative.tech.one_touch_controller.domain.config.MIN_REMOTE_TOKEN_LENGTH
import jp.relative.tech.one_touch_controller.domain.ports.TunnelState
import jp.relative.tech.one_touch_controller.ui.components.QrCode
import jp.relative.tech.one_touch_controller.ui.theme.OtcCardShape
import jp.relative.tech.one_touch_controller.ui.theme.OtcKeyShape
import jp.relative.tech.one_touch_controller.ui.theme.tr

@Composable
fun InternetTab(
    running: Boolean,
    tunnelState: TunnelState,
    tunnelBusy: Boolean,
    serverTokenLength: Int,
    allowDangerousOverInternet: Boolean,
    allowScreenView: Boolean,
    screenViewers: Int,
    onToggleTunnel: (Boolean) -> Unit,
    onCopyPublicUrl: (String) -> Unit,
    onAllowDangerousChange: (Boolean) -> Unit,
    onAllowScreenViewChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ----- internet access (Cloudflare tunnel) -----
        Surface(
            shape = OtcCardShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("Internet access (Cloudflare tunnel)"), style = MaterialTheme.typography.titleSmall)
                        Text(tr(tunnelState.status), style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = tunnelState.active,
                        enabled = !tunnelBusy && running,
                        onCheckedChange = onToggleTunnel
                    )
                }

                if (!running) {
                    Text(tr("Start the server first."), style = MaterialTheme.typography.labelMedium)
                } else if (serverTokenLength < MIN_REMOTE_TOKEN_LENGTH) {
                    Text(
                        tr("Needs an access token with at least %1 characters (press Generate, then Apply token).", MIN_REMOTE_TOKEN_LENGTH),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                val publicUrl = tunnelState.url
                if (publicUrl != null) {
                    val appUrl = "$publicUrl/app"
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QrCode(appUrl, Modifier.size(100.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(tr("Open on any phone, any network:"), style = MaterialTheme.typography.bodyMedium)
                            Text(appUrl, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                tr("Android app: type %1 in the IP box. You still need the access token.", publicUrl),
                                style = MaterialTheme.typography.labelMedium
                            )
                            OutlinedButton(shape = OtcKeyShape, onClick = { onCopyPublicUrl(publicUrl) }) {
                                Text(tr("Copy address"))
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = allowDangerousOverInternet,
                        onCheckedChange = onAllowDangerousChange
                    )
                    Text(tr("Allow shutdown, restart and scripts over the internet"), style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = allowScreenView,
                        onCheckedChange = onAllowScreenViewChange
                    )
                    Text(tr("Allow viewing this PC's screen from a phone"), style = MaterialTheme.typography.bodyMedium)
                }
                if (screenViewers > 0) {
                    Text(
                        tr("A phone is viewing this PC's screen right now"),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Text(
                    tr("Anyone who has the address AND the token can control this PC. The address changes every time you turn this on."),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
