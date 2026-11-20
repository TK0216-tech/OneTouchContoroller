package jp.relative.tech.one_touch_controller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.relative.tech.one_touch_controller.application.RoutineState
import jp.relative.tech.one_touch_controller.domain.routine.Routine
import jp.relative.tech.one_touch_controller.domain.routine.describeStep
import jp.relative.tech.one_touch_controller.ui.components.TrashIcon
import jp.relative.tech.one_touch_controller.ui.theme.LocalOtcColors
import jp.relative.tech.one_touch_controller.ui.theme.OtcCardShape
import jp.relative.tech.one_touch_controller.ui.theme.OtcKeyShape
import jp.relative.tech.one_touch_controller.ui.theme.tr

@Composable
fun RoutinesTab(
    routineState: RoutineState,
    recordCountdown: Int,
    recording: Boolean,
    recordCount: Int,
    hasRecordedResult: Boolean,
    onEditFile: () -> Unit,
    onReload: () -> Unit,
    onRunRoutine: (Routine) -> Unit,
    onDeleteRoutine: (Routine) -> Unit,
    onRecordButtonClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalOtcColors.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ----- the routines, each with its own Run button -----
        Surface(
            shape = OtcCardShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("Automation routines"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("Run a routine here or from your phone."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted
                        )
                    }
                    OutlinedButton(shape = OtcKeyShape, onClick = onEditFile) { Text(tr("Edit file")) }
                    OutlinedButton(shape = OtcKeyShape, onClick = onReload) { Text(tr("Reload")) }
                }
                if (routineState.routines.isEmpty()) {
                    Text(
                        tr("No routines yet. Press \"Edit file\" to write one, or record a task below."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted
                    )
                }
                routineState.routines.forEach { routine ->
                    val isRunning = routine.id in routineState.runningIds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.ink, OtcKeyShape)
                            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(routine.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                tr("%1 steps", routine.steps.size) + ": " +
                                    routine.steps.take(3).joinToString(", ") { describeStep(it) } +
                                    (if (routine.steps.size > 3) ", ..." else ""),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.muted,
                                maxLines = 1
                            )
                        }
                        Button(
                            shape = OtcKeyShape,
                            enabled = !isRunning,
                            onClick = { onRunRoutine(routine) }
                        ) { Text(if (isRunning) tr("Running...") else tr("Run")) }
                        // delete (trash can)
                        IconButton(enabled = !isRunning, onClick = { onDeleteRoutine(routine) }) {
                            TrashIcon(if (isRunning) colors.muted.copy(alpha = 0.4f) else colors.danger)
                        }
                    }
                }
                routineState.problems.take(3).forEach {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // ----- record a routine: do the task once by hand, the PC repeats it later -----
        Surface(
            shape = OtcCardShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Record a task"), style = MaterialTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        when {
                            recording -> tr("Recording: %1 actions so far. Press F9 or Stop when the task is finished.", recordCount)
                            recordCountdown > 0 -> tr("Recording starts in %1...", recordCountdown)
                            else -> tr("Record a task once (mouse and keyboard) and run it again from here or from your phone.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (recording) colors.accent else colors.muted,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        shape = OtcKeyShape,
                        enabled = recording || (recordCountdown == 0 && !hasRecordedResult),
                        onClick = onRecordButtonClick
                    ) { Text(if (recording) tr("Stop") else tr("Record")) }
                }
            }
        }
    }
}
