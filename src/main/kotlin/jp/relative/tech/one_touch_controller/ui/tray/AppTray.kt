package jp.relative.tech.one_touch_controller.ui.tray

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import jp.relative.tech.one_touch_controller.domain.config.StringTranslator

@Composable
fun ApplicationScope.AppTray(
    icon: Painter,
    trayState: TrayState,
    running: Boolean,
    translator: StringTranslator,
    onOpenWindow: () -> Unit,
    onToggleServer: () -> Unit,
    onExit: () -> Unit
) {
    Tray(
        icon = icon,
        state = trayState,
        tooltip = if (running) translator.tr("One Touch Controller - running") else translator.tr("One Touch Controller - stopped"),
        onAction = onOpenWindow,   // click on the icon = open the window
        menu = {
            Item(translator.tr("Open window"), onClick = onOpenWindow)
            Item(
                if (running) translator.tr("Stop server") else translator.tr("Start server"),
                onClick = onToggleServer
            )
            Separator()
            Item(translator.tr("Exit"), onClick = onExit)
        }
    )
}
