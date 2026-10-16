package io.github.cubsync.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.cubsync.service.AppLog
import io.github.cubsync.service.AppSettings
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.SyncService
import io.github.cubsync.service.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Path

/**
 * The phone's entry point.
 *
 * Everything that outlives a recomposition is created once here, so a screen cannot
 * quietly start a second run: the screens reach the network and the disk only through the
 * state object they are given.
 *
 * The settings store is the one thing that genuinely differs from the desktop —
 * SharedPreferences against a properties file — and it is passed in rather than chosen
 * here, so that everything reading those settings is the same code on both ends.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val storage = AndroidSettings(applicationContext)
        val log = AppLog()
        val service = SyncService(
            stateDirectory = stateDirectoryIn(filesDir),
            log = log,
            settingsStorage = storage,
        )
        val server = ServerController(log)

        setContent {
            // Built inside the composition rather than in onCreate, so a configuration
            // change does not leave a service holding a dead window.
            var state by remember { mutableStateOf<AndroidAppState?>(null) }
            LaunchedEffect(Unit) {
                if (state == null) {
                    state = AndroidAppState(
                        service = service,
                        server = server,
                        scope = scope,
                        appDirectory = filesDir,
                    )
                }
            }
            val current = state
            CubAndroidTheme(
                mode = current?.liveSettings?.theme ?: ThemeMode.DARK,
                accentHex = current?.liveSettings?.accent ?: AppSettings.ACCENT_DEFAULT,
            ) {
                if (current == null) LoadingScreen() else CubAndroidApp(current)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** The state directory the application owns: one folder under its own storage. */
fun stateDirectoryIn(filesDir: File): Path = filesDir.toPath().resolve("state")

/** Shown for the single frame the state object takes to be built. */
@androidx.compose.runtime.Composable
private fun LoadingScreen() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("CubSync", style = CubType.sectionTitle)
    }
}
