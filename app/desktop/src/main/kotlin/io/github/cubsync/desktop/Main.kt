package io.github.cubsync.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.cubsync.desktop.ui.CubApp
import io.github.cubsync.service.AppLog
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The window.
 *
 * Everything that outlives a recomposition is created once here: the service, the
 * server, the settings and the scope they run in. Screens receive them through
 * [AppState] and have no way to reach the network or the disk other than through it,
 * which is what keeps a screen from quietly starting a second job.
 */
fun main(arguments: Array<String>) {
    val requested = argument(arguments, "--state-dir") ?: AppState.defaultStateDirectory()
    val stateDirectory = Paths.get(requested).toAbsolutePath().normalize()
    runCatching { Files.createDirectories(stateDirectory) }

    val log = AppLog()
    val service = SyncService(stateDirectory = stateDirectory, log = log)
    val server = ServerController(log)
    val settings = DesktopSettings.inStateDirectory(stateDirectory)
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    application {
        val state = remember { AppState(service, server, settings, scope) }
        Window(
            onCloseRequest = {
                state.close()
                scope.cancel()
                exitApplication()
            },
            title = "CubSync",
            state = rememberWindowState(width = 1280.dp, height = 860.dp),
        ) {
            LaunchedEffect(Unit) {
                // Below this the two-column screens stop being usable, and the
                // alternative -- a layout that reflows -- is not one this window has.
                window.minimumSize = java.awt.Dimension(1040, 700)
            }
            CubTheme(dark = state.settings.darkTheme) {
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
