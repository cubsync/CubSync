package io.github.cubsync.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.cubsync.desktop.ui.CubApp
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Every screen, composed off screen and written to a PNG.
 *
 * This is the check that a layout actually lays out. A screen that throws while composing
 * fails this test rather than being discovered by whoever opens the window next, and the
 * images double as the thing a reviewer looks at when the question is "does it look like
 * the design" — a question no assertion answers well.
 */
class ScreenshotTest {

    private val output = Paths.get("build", "screenshots")

    @Test
    fun every_screen_renders_and_is_written_out() {
        // Off-screen rendering needs a graphics backend. Where there is none — a bare
        // container, a headless runner — skipping is honest; pretending to have checked
        // would not be.
        assumeTrue("no graphics backend for off-screen rendering", graphicsAvailable())

        Files.createDirectories(output)
        val written = ArrayList<String>()

        for (shot in Preview.shots) {
            val state = Preview.state(shot.section, shot.dark, shot.jobDetail)
            try {
                val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                    CubTheme(dark = shot.dark) { CubApp(state) }
                }
                val image = try {
                    scene.render()
                } finally {
                    scene.close()
                }
                val data = requireNotNull(image.encodeToData()) { "could not encode ${shot.name}" }
                val file = output.resolve("${shot.name}.png")
                Files.write(file, data.bytes)
                written += file.toString()
            } finally {
                Preview.dispose(state)
            }
        }

        assertTrue(written.size == Preview.shots.size, "expected ${Preview.shots.size} images, wrote ${written.size}")
        for (path in written) {
            assertTrue(Files.size(Paths.get(path)) > 4_000, "$path is suspiciously small")
        }
    }

    private fun graphicsAvailable(): Boolean = runCatching {
        val scene = ImageComposeScene(width = 32, height = 32, density = Density(1f)) {}
        try {
            scene.render()
        } finally {
            scene.close()
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 860
    }
}
