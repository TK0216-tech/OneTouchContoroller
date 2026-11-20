package jp.relative.tech.one_touch_controller

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import jp.relative.tech.one_touch_controller.application.I18n
import jp.relative.tech.one_touch_controller.ui.components.rememberAppIcon
import jp.relative.tech.one_touch_controller.ui.screens.ServerApp
import jp.relative.tech.one_touch_controller.ui.theme.OtcTheme
import jp.relative.tech.one_touch_controller.ui.tray.AppTray
import kotlinx.coroutines.launch

fun main() {
    System.setProperty("java.awt.headless", "false") // needed so Robot and the window work
    val app = AppContainer()
    app.initialize()

    // The server starts automatically. You can still stop it with the button or the tray menu.
    try {
        app.serverController.start()
    } catch (e: Exception) {
        app.logStore.log("Could not start automatically: ${e.message}")
    }

    application {
        val scope = rememberCoroutineScope()
        val trayState = rememberTrayState()
        val settings by app.settingsService.settings.collectAsState()
        val running by app.serverController.running.collectAsState()
        val translator = remember(settings.language) { I18n.forLanguage(settings.language) }
        var windowVisible by remember { mutableStateOf(true) }
        var hintShown by remember { mutableStateOf(false) }

        fun exitProgram() {
            app.shutdown()
            exitApplication()
        }

        // the same icon in the window title, the taskbar and the tray (follows the accent colour)
        val icon = rememberAppIcon(settings)

        // ----- system tray icon (next to the clock) -----
        if (isTraySupported) {
            AppTray(
                icon = icon,
                trayState = trayState,
                running = running,
                translator = translator,
                onOpenWindow = { windowVisible = true },
                onToggleServer = {
                    scope.launch {
                        val error = app.serverController.toggle()
                        if (error != null) app.logStore.log("Start failed: $error")
                    }
                },
                onExit = { exitProgram() }
            )
        }

        // ----- the window -----
        Window(
            onCloseRequest = {
                if (isTraySupported) {
                    windowVisible = false      // closing the window only hides it; the server keeps running
                    if (!hintShown) {
                        hintShown = true
                        trayState.sendNotification(
                            Notification(
                                "One Touch Controller",
                                translator.tr("Still running in the system tray. Right-click the icon to exit.")
                            )
                        )
                    }
                } else {
                    exitProgram()
                }
            },
            visible = windowVisible,
            title = "One Touch Controller - PC Server",
            icon = icon,
            state = rememberWindowState(width = 720.dp, height = 780.dp)
        ) {
            OtcTheme(settings) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ServerApp(app)
                }
            }
        }
    }
}
