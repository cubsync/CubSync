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
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The phone's entry point.
 *
 * Everything that outlives a recomposition is created once here, so a screen cannot
 * quietly start a second run: the screens reach the network and the disk only through
 * the state object they are given.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = AndroidPreferences(applicationContext)
        val log = AppLog()
        val service = SyncService(stateDirectory = AndroidAppState.stateDirectoryIn(filesDir), log = log)
        val server = ServerController(log)

        setContent {
            // The state object is built inside the composition rather than in
            // onCreate, so a configuration change that recreates the activity does not
            // leave a service holding a dead window.
            var state by remember { mutableStateOf<AndroidAppState?>(null) }
            LaunchedEffect(Unit) {
                if (state == null) {
                    state = AndroidAppState(
                        service = service,
                        server = server,
                        preferences = preferences,
                        scope = scope,
                        appDirectory = filesDir,
                    )
                }
            }
            val current = state
            CubAndroidTheme(dark = preferences.darkTheme) {
                if (current == null) LoadingScreen() else CubAndroidApp(current)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

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
