package jp.relative.tech.one_touch_controller.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.relative.tech.one_touch_controller.ui.theme.OtcCardShape
import jp.relative.tech.one_touch_controller.ui.theme.OtcKeyShape
import jp.relative.tech.one_touch_controller.ui.theme.tr

@Composable
fun LogTab(
    lines: List<String>,
    listState: LazyListState,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(tr("Activity log"), style = MaterialTheme.typography.titleSmall)
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = OtcCardShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(8.dp)
            ) {
                items(lines.size) { index ->
                    Text(
                        lines[index],
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }

        OutlinedButton(
            shape = OtcKeyShape,
            onClick = onClearLog,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(tr("Clear log"))
        }
    }
}
