package io.github.cubsync.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.cubsync.desktop.ui.CubApp
import io.github.cubsync.desktop.ui.WindowChrome
import io.github.cubsync.service.AppLog
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.SyncService
import io.github.cubsync.service.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Point
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JFrame

/**
 * The window.
 *
 * Undecorated on purpose: the prototype draws its own title bar (`.win-title`) with the
 * mark, the device chip and the three controls, and a native title bar cannot be themed —
 * which is why the previous release showed a white strip above a dark window and no amount
 * of colour work on the content could fix it. `UndecoratedWindowDecoration` keeps the
 * resize borders, so what is given up is the frame, not the window.
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
        var maximised by remember { mutableStateOf(false) }

        Window(
            onCloseRequest = {
                desktop.saveWindowSize(windowState.size.width.value.toInt(), windowState.size.height.value.toInt())
                state.close()
                scope.cancel()
                exitApplication()
            },
            title = "CubSync",
            state = windowState,
            icon = painterResource("cubsync-512.png"),
            undecorated = true,
        ) {
            LaunchedEffect(Unit) {
                // Below this the two-column screens stop being usable, and the alternative
                // — a layout that reflows into one column — is not one this window has.
                window.minimumSize = Dimension(1040, 700)
            }

            fun toggleMaximise() {
                maximised = !maximised
                window.extendedState = if (maximised) JFrame.MAXIMIZED_BOTH else JFrame.NORMAL
            }

            // The revision is read so that switching the theme redraws immediately rather
            // than at the next recomposition that happens to come along.
            val revision = state.settingsRevision
            val mode = if (revision >= 0) state.liveSettings.theme else ThemeMode.DARK

            // Dragging reads the pointer's *screen* position rather than the drag delta.
            //
            // The delta is measured inside this composable, so the moment the window moves
            // the pointer's local coordinates change by the same amount the window did —
            // which feeds the movement back in and walks the window off the screen. The
            // screen position does not move when the window does, so it has no such loop.
            val drag = Modifier
                .pointerInput(Unit) {
                    var grabbedAt: java.awt.Point? = null
                    var windowAt: java.awt.Point? = null
                    detectDragGestures(
                        onDragStart = {
                            grabbedAt = MouseInfo.getPointerInfo()?.location
                            windowAt = window.location
                        },
                        onDragEnd = { grabbedAt = null; windowAt = null },
                        onDragCancel = { grabbedAt = null; windowAt = null },
                    ) { change, _ ->
                        change.consume()
                        val pointer = MouseInfo.getPointerInfo()?.location
                        val from = windowAt
                        val start = grabbedAt
                        if (pointer != null && from != null && start != null) {
                            window.location = Point(
                                from.x + pointer.x - start.x,
                                from.y + pointer.y - start.y,
                            )
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { toggleMaximise() })
                }

            val chrome = WindowChrome(
                minimise = { window.extendedState = JFrame.ICONIFIED },
                toggleMaximise = { toggleMaximise() },
                close = {
                    desktop.saveWindowSize(
                        windowState.size.width.value.toInt(),
                        windowState.size.height.value.toInt(),
                    )
                    state.close()
                    scope.cancel()
                    exitApplication()
                },
                maximised = maximised,
                dragModifier = drag,
            )

            CubTheme(dark = mode != ThemeMode.LIGHT, accentHex = state.liveSettings.accent) {
                CubApp(state, chrome)
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
