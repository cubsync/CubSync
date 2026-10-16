package io.github.cubsync.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.cubsync.desktop.ui.CubApp
import io.github.cubsync.service.AppLog
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.SyncService
import io.github.cubsync.service.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The window.
 *
 * Everything that outlives a recomposition is created once here: the service, the server,
 * the settings and the scope they run in. Screens receive them through [AppState] and have
 * no way to reach the network or the disk other than through it, which is what keeps a
 * screen from quietly starting a second job.
 */
fun main(arguments: Array<String>) {
    val requested = argument(arguments, "--state-dir") ?: AppState.defaultStateDirectory()
    val stateDirectory = Paths.get(requested).toAbsolutePath().normalize()
    runCatching { Files.createDirectories(stateDirectory) }

    val log = AppLog()
    val desktop = DesktopSettings.inStateDirectory(stateDirectory)
    val service = SyncService(stateDirectory = stateDirectory, log = log, settingsStorage = desktop)
    val server = ServerController(log)
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    application {
        val state = remember { AppState(service, server, desktop, scope) }
        val windowState = rememberWindowState(
            width = desktop.windowWidth.dp,
            height = desktop.windowHeight.dp,
        )
        Window(
            onCloseRequest = {
                desktop.saveWindowSize(windowState.size.width.value.toInt(), windowState.size.height.value.toInt())
                state.close()
                scope.cancel()
                exitApplication()
            },
            title = "CubSync",
            state = windowState,
        ) {
            LaunchedEffect(Unit) {
                // Below this the two-column screens stop being usable, and the alternative
                // — a layout that reflows into one column — is not one this window has.
                window.minimumSize = java.awt.Dimension(1040, 700)
            }
            // The revision is read so that switching the theme redraws immediately rather
            // than at the next recomposition that happens to come along.
            val revision = state.settingsRevision
            val mode = if (revision >= 0) state.liveSettings.theme else ThemeMode.DARK
            CubTheme(dark = mode != ThemeMode.LIGHT, accentHex = state.liveSettings.accent) {
                CubApp(state)
            }
        }
    }
}

/** `--flag value` and `--flag=value`, because both are typed in practice. */
private fun argument(arguments: Array<String>, name: String): String? {
    for (index in arguments.indices) {
        val current = arguments[index]
        if (current == name) return arguments.getOrNull(index + 1)
        if (current.startsWith("$name=")) return current.substringAfter('=')
    }
    return null
}
