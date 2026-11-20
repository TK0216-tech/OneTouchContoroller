package jp.relative.tech.one_touch_controller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.relative.tech.one_touch_controller.ui.components.QrCode
import jp.relative.tech.one_touch_controller.ui.theme.LocalOtcColors
import jp.relative.tech.one_touch_controller.ui.theme.OtcCardShape
import jp.relative.tech.one_touch_controller.ui.theme.OtcKeyShape
import jp.relative.tech.one_touch_controller.ui.theme.tr

@Composable
fun HomeTab(
    running: Boolean,
    busy: Boolean,
    port: Int,
    addresses: List<String>,
    qrIndex: Int,
    onQrIndexChange: (Int) -> Unit,
    tokenText: String,
    onTokenTextChange: (String) -> Unit,
    onToggleServer: () -> Unit,
    onGenerateToken: () -> Unit,
    onApplyToken: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalOtcColors.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ----- status + start/stop button -----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().background(colors.panel, OtcCardShape).padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(if (running) colors.accent else colors.muted, CircleShape)
            )
            Text(
                if (running) tr("Running (port %1)", port) else tr("Stopped"),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.weight(1f))
            Button(
                shape = OtcKeyShape,
                enabled = !busy,
                onClick = onToggleServer
            ) {
                Text(if (running) tr("Stop server") else tr("Start server"))
            }
        }

        // ----- PC address -----
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().background(colors.panel, OtcCardShape).padding(14.dp)
        ) {
            Text(
                tr("Type this IP in the phone app (or tap Find PC):"),
                style = MaterialTheme.typography.bodyMedium
            )
            if (addresses.isEmpty()) {
                Text(
                    tr("No network found. Connect to Wi-Fi or turn on the Mobile hotspot."),
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                addresses.forEach { address ->
                    Text(address, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ----- web version (iPhone / any browser) -----
        val ips = addresses.map { it.substringBefore(" ") }   // just the IP, without the adapter name
        if (ips.isNotEmpty()) {
            // automatic choice: the Windows Mobile Hotspot address (192.168.137.x) if there is one, else the first
            val autoIndex = ips.indexOfFirst { it.startsWith("192.168.137.") }.let { if (it >= 0) it else 0 }
            val chosen = if (qrIndex in ips.indices) qrIndex else autoIndex
            val webUrl = "http://${ips[chosen]}:$port/app"
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(colors.panel, OtcCardShape).padding(14.dp)
            ) {
                QrCode(webUrl, Modifier.size(110.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("iPhone / any browser: scan the code or open"), style = MaterialTheme.typography.bodyMedium)
                    Text(webUrl, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(
                        tr("In Safari: Share > Add to Home Screen. You still need the access token below."),
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (ips.size > 1) {
                        TextButton(onClick = { onQrIndexChange((chosen + 1) % ips.size) }) {
                            Text(tr("Wrong address? Use the next one (%1/%2)", chosen + 1, ips.size))
                        }
                    }
                }
            }
        }

        // ----- token -----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = tokenText,
                onValueChange = onTokenTextChange,
                label = { Text(tr("Access token")) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(shape = OtcKeyShape, onClick = onGenerateToken) {
                Text(tr("Generate"))
            }
            Button(shape = OtcKeyShape, onClick = onApplyToken) {
                Text(tr("Apply token"))
            }
        }
    }
}
