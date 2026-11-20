package jp.relative.tech.one_touch_controller.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.relative.tech.one_touch_controller.AppContainer
import jp.relative.tech.one_touch_controller.application.TokenApplyResult
import jp.relative.tech.one_touch_controller.domain.config.MIN_REMOTE_TOKEN_LENGTH
import jp.relative.tech.one_touch_controller.domain.recording.RecEvent
import jp.relative.tech.one_touch_controller.domain.recording.recordingSeconds
import jp.relative.tech.one_touch_controller.domain.routine.Routine
import jp.relative.tech.one_touch_controller.domain.routine.Step
import jp.relative.tech.one_touch_controller.server.localAddresses
import jp.relative.tech.one_touch_controller.ui.components.PcTabBar
import jp.relative.tech.one_touch_controller.ui.components.SettingsDialog
import jp.relative.tech.one_touch_controller.ui.components.rememberAppIcon
import jp.relative.tech.one_touch_controller.ui.theme.LocalOtcColors
import jp.relative.tech.one_touch_controller.ui.theme.LocalTranslator
import jp.relative.tech.one_touch_controller.ui.theme.OtcKeyShape
import jp.relative.tech.one_touch_controller.ui.theme.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServerApp(app: AppContainer) {
    val scope = rememberCoroutineScope()
    val colors = LocalOtcColors.current
    val translator = LocalTranslator.current

    // ----- observed backend states (StateFlow -> Compose state) -----
    val running by app.serverController.running.collectAsState()
    val settings by app.settingsService.settings.collectAsState()
    val serverToken by app.tokenService.token.collectAsState()
    val tunnelState by app.tunnel.state.collectAsState()
    val allowDanger by app.accessGuard.allowDangerousOverInternet.collectAsState()
    val allowScreen by app.accessGuard.allowScreenView.collectAsState()
    val screenViewers by app.screenStreamState.viewers.collectAsState()
    val routineState by app.routineService.state.collectAsState()
    val guardState by app.guardEngine.state.collectAsState()
    val lines by app.logStore.lines.collectAsState()

    // ----- local UI states (kept across tab switches) -----
    var busy by remember { mutableStateOf(false) }
    var addresses by remember { mutableStateOf(localAddresses()) }
    var tokenText by remember { mutableStateOf(serverToken) }
    var qrIndex by remember { mutableStateOf(-1) }                     // which address the QR code uses (-1 = automatic)
    var tunnelBusy by remember { mutableStateOf(false) }               // true while the tunnel is being started/downloaded
    var askDownload by remember { mutableStateOf(false) }              // show the "download cloudflared?" question
    var dialogMessage by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var guardPassword by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }                          // 0 Home, 1 Internet, 2 Routines, 3 Security, 4 Log
    var confirmRoutine by remember { mutableStateOf<Routine?>(null) }  // "run this risky routine?" question
    var deleteAsk by remember { mutableStateOf<Routine?>(null) }       // "delete this routine?" question

    // recording a routine: countdown (3, 2, 1) -> recording -> finished list waiting for a name
    var recordCountdown by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var recordCount by remember { mutableStateOf(0) }
    var recorded by remember { mutableStateOf<List<RecEvent>?>(null) }
    var recordName by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    // Refresh the address list every 5 seconds (it changes when you switch Wi-Fi or turn on the hotspot)
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            addresses = withContext(Dispatchers.IO) { localAddresses() }
        }
    }

    // Scroll the log to the newest line (only while the Log tab is open)
    LaunchedEffect(lines.size, tab) {
        if (tab == 4 && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painter = rememberAppIcon(), contentDescription = null, modifier = Modifier.size(36.dp))
            Column {
                Text("One Touch Controller", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(tr("PC server"), style = MaterialTheme.typography.labelMedium, color = colors.muted)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (running) colors.accent else colors.muted, CircleShape)
            )
            Text(
                if (running) tr("Running (port %1)", app.serverController.port) else tr("Stopped"),
                style = MaterialTheme.typography.labelLarge,
                color = colors.muted
            )
            OutlinedButton(shape = OtcKeyShape, onClick = { showSettings = true }) { Text(tr("Settings")) }
        }
        if (showSettings) {
            SettingsDialog(
                settings = settings,
                settingsService = app.settingsService,
                onClose = { showSettings = false }
            )
        }

        PcTabBar(
            selected = tab,
            onSelect = { tab = it },
            busy = { index ->
                when (index) {
                    1 -> tunnelState.active
                    2 -> routineState.runningIds.isNotEmpty() || recording
                    3 -> guardState.active
                    else -> false
                }
            }
        )

        // one tab at a time; the long ones can be scrolled
        when (tab) {
            0 -> HomeTab(
                running = running,
                busy = busy,
                port = app.serverController.port,
                addresses = addresses,
                qrIndex = qrIndex,
                onQrIndexChange = { qrIndex = it },
                tokenText = tokenText,
                onTokenTextChange = { tokenText = it },
                onToggleServer = {
                    scope.launch {
                        busy = true
                        // start/stop run in the background so the window never freezes
                        val error = app.serverController.toggle()
                        if (error != null) {
                            app.logStore.log("Start failed: $error")
                            dialogMessage = translator.tr(
                                "Could not start the server:\n%1\n\nIs another copy of the server already running on port %2?",
                                error,
                                app.serverController.port
                            )
                        }
                        addresses = withContext(Dispatchers.IO) { localAddresses() }
                        busy = false
                    }
                },
                onGenerateToken = { tokenText = app.tokenService.generate() },
                onApplyToken = {
                    when (val result = app.tokenService.apply(tokenText, tunnelState.active)) {
                        TokenApplyResult.Empty ->
                            dialogMessage = translator.tr("The token cannot be empty.")
                        is TokenApplyResult.TooShortForInternet ->
                            dialogMessage = translator.tr(
                                "Internet access is on, so the token must have at least %1 characters. Turn internet access off first, or press Generate.",
                                result.minLength
                            )
                        TokenApplyResult.Success -> {}
                    }
                },
                modifier = Modifier.weight(1f)
            )

            1 -> InternetTab(
                running = running,
                tunnelState = tunnelState,
                tunnelBusy = tunnelBusy,
                serverTokenLength = serverToken.length,
                allowDangerousOverInternet = allowDanger,
                allowScreenView = allowScreen,
                screenViewers = screenViewers,
                onToggleTunnel = { wantOn ->
                    if (!wantOn) {
                        scope.launch(Dispatchers.IO) { app.tunnel.stop() }
                    } else if (serverToken.length < MIN_REMOTE_TOKEN_LENGTH) {
                        dialogMessage = translator.tr(
                            "Internet access needs an access token of at least %1 characters, because anyone on the internet could try to guess it.\n\nPress \"Generate\", then \"Apply token\", and enter the new token in your phone app / web page.",
                            MIN_REMOTE_TOKEN_LENGTH
                        )
                    } else {
                        scope.launch {
                            tunnelBusy = true
                            try {
                                val exe = withContext(Dispatchers.IO) { app.tunnel.findExecutable() }
                                if (exe == null) askDownload = true
                                else withContext(Dispatchers.IO) { app.tunnel.start(exe) }
                            } catch (e: Exception) {
                                dialogMessage = translator.tr("Could not start the tunnel:\n%1", e.message)
                            }
                            tunnelBusy = false
                        }
                    }
                },
                onCopyPublicUrl = { url -> app.pcInput.copyToClipboard(url) },
                onAllowDangerousChange = { allowed ->
                    app.accessGuard.setAllowDangerousOverInternet(allowed)
                    app.logStore.log(
                        if (allowed) "Shutdown/restart/scripts are now ALLOWED over the internet"
                        else translator.tr("Shutdown/restart/scripts are blocked over the internet")
                    )
                },
                onAllowScreenViewChange = { allowed ->
                    app.accessGuard.setAllowScreenView(allowed)
                    app.logStore.log(if (allowed) "Screen viewing is now ALLOWED" else "Screen viewing is turned off")
                },
                modifier = Modifier.weight(1f)
            )

            2 -> RoutinesTab(
                routineState = routineState,
                recordCountdown = recordCountdown,
                recording = recording,
                recordCount = recordCount,
                hasRecordedResult = recorded != null,
                onEditFile = {
                    try {
                        app.routineService.openRoutinesFile()
                    } catch (e: Exception) {
                        dialogMessage = translator.tr(
                            "Could not open Notepad. The file is here:\n%1",
                            app.routineService.routinesFile.absolutePath
                        )
                    }
                },
                onReload = { app.routineService.reload() },
                onRunRoutine = { routine ->
                    if (app.routineService.isDangerous(routine)) confirmRoutine = routine
                    else app.routineService.start(routine, "PC window")
                },
                onDeleteRoutine = { routine -> deleteAsk = routine },
                onRecordButtonClick = {
                    if (recording) {                   // the Stop button: works even if F9 does not
                        app.recorder.stopRecording(fromButton = true)
                    } else {
                        scope.launch {
                            for (n in 3 downTo 1) {
                                recordCountdown = n
                                delay(1000)
                            }
                            recordCountdown = 0
                            try {
                                recordCount = 0
                                app.recorder.startRecording(
                                    progress = { recordCount = it },
                                    stopped = { events ->
                                        recording = false
                                        recorded = events
                                        recordName = ""
                                    }
                                )
                                recording = true
                                app.logStore.log("Recording started (F9 stops it)")
                            } catch (e: Throwable) {
                                dialogMessage = translator.tr("Could not start recording:\n%1", e.message ?: e.javaClass.simpleName)
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )

            3 -> SecurityTab(
                guardState = guardState,
                guardPassword = guardPassword,
                onGuardPasswordChange = { guardPassword = it },
                onToggleGuard = {
                    try {
                        if (guardState.active) app.guardEngine.turnOff("from the PC window")
                        else app.guardEngine.turnOn("from the PC window")
                    } catch (e: Exception) {
                        dialogMessage = translator.tr(e.message ?: "Guard mode could not start")
                    }
                },
                onSavePassword = {
                    val problem = app.guardEngine.setPassword(guardPassword)
                    if (problem == null) {
                        guardPassword = ""
                        app.logStore.log("Guard password saved")
                        dialogMessage = translator.tr("Guard password saved.")
                    } else {
                        dialogMessage = problem
                    }
                },
                modifier = Modifier.weight(1f)
            )

            else -> LogTab(
                lines = lines,
                listState = listState,
                onClearLog = { app.logStore.clear() },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // ----- "run this routine?" for routines with shutdown / restart / script steps -----
    confirmRoutine?.let { routine ->
        AlertDialog(
            onDismissRequest = { confirmRoutine = null },
            title = { Text(tr("Are you sure?")) },
            text = { Text(tr("Run \"%1\" on this PC? It contains shutdown, restart or script steps.", routine.name)) },
            confirmButton = {
                TextButton(onClick = {
                    app.routineService.start(routine, "PC window")
                    confirmRoutine = null
                }) { Text(tr("Run")) }
            },
            dismissButton = { TextButton(onClick = { confirmRoutine = null }) { Text(tr("Cancel")) } }
        )
    }

    // ----- "delete this routine?" -----
    deleteAsk?.let { routine ->
        val hasRecording = routine.steps.any { it is Step.Replay }
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text(tr("Delete \"%1\"?", routine.name)) },
            text = {
                Text(
                    if (hasRecording) tr("The routine and its recording are deleted. This cannot be undone.")
                    else tr("The routine is removed from the routines file. This cannot be undone.")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteAsk = null
                    app.routineService.delete(routine)?.let { dialogMessage = it }
                }) { Text(tr("Delete"), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text(tr("Cancel")) } }
        )
    }

    // ----- a recording was finished: ask for a name and save it as a routine -----
    recorded?.let { events ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (events.isEmpty()) tr("Nothing was recorded") else tr("Save this routine?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (events.isEmpty()) {
                        Text(tr("No mouse or keyboard action arrived before F9 was pressed."))
                    } else {
                        Text(tr("%1 actions, about %2 seconds. It will appear in the Routines list of the remote apps.", events.size, recordingSeconds(events)))
                        OutlinedTextField(
                            value = recordName,
                            onValueChange = { recordName = it.replace("\n", " ").take(40) },
                            label = { Text(tr("Name of the routine")) },
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                if (events.isNotEmpty()) TextButton(
                    enabled = recordName.isNotBlank(),
                    onClick = {
                        try {
                            app.routineService.saveRecordingAsRoutine(recordName, events)
                        } catch (e: Exception) {
                            dialogMessage = translator.tr("Could not save the routine:\n%1", e.message)
                        }
                        recorded = null
                    }
                ) { Text(tr("Save")) }
            },
            dismissButton = {
                TextButton(onClick = { recorded = null }) { Text(if (events.isEmpty()) tr("OK") else tr("Discard")) }
            }
        )
    }

    // ----- "download cloudflared?" question -----
    if (askDownload) {
        AlertDialog(
            onDismissRequest = { askDownload = false },
            title = { Text(tr("Download cloudflared?")) },
            text = {
                Text(
                    tr("Internet access needs the free program \"cloudflared\" from Cloudflare (about 55 MB).\n\nIt will be downloaded from github.com/cloudflare/cloudflared and saved in your user folder (.onetouch). Download it now?")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askDownload = false
                    scope.launch {
                        tunnelBusy = true
                        try {
                            withContext(Dispatchers.IO) { app.tunnel.start(app.tunnel.download()) }
                        } catch (e: Exception) {
                            dialogMessage = translator.tr("Download failed:\n%1", e.message)
                        }
                        tunnelBusy = false
                    }
                }) { Text(tr("Download")) }
            },
            dismissButton = { TextButton(onClick = { askDownload = false }) { Text(tr("Cancel")) } }
        )
    }

    // ----- message pop-up (errors / notices) -----
    dialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { dialogMessage = null },
            title = { Text(tr("Notice")) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { dialogMessage = null }) { Text(tr("OK")) }
            }
        )
    }
}
