package io.github.cubsync.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.cubsync.desktop.ui.CubApp
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders every screen off screen and writes it out as a PNG.
 *
 * This is both a smoke test and the way the interface gets reviewed: composing each
 * screen exercises the real layout, and a crash in a screen fails the build instead
 * of being discovered by whoever opens the window next. The images land in
 * `app/desktop/build/screenshots`.
 */
class ScreenshotTest {

    private val output: Path = Paths.get("build", "screenshots").toAbsolutePath()

    private data class Shot(val name: String, val section: Section, val dark: Boolean)

    private val shots = listOf(
        Shot("01-connect", Section.CONNECT, dark = true),
        Shot("02-sync", Section.SYNC, dark = true),
        Shot("03-jobs", Section.JOBS, dark = true),
        Shot("04-logs", Section.LOGS, dark = true),
        Shot("05-settings", Section.SETTINGS, dark = true),
        Shot("06-connect-light", Section.CONNECT, dark = false),
        Shot("07-sync-light", Section.SYNC, dark = false),
        Shot("08-jobs-light", Section.JOBS, dark = false),
    )

    @Test
    fun every_screen_renders_and_is_written_out() {
        // Rendering off screen needs Skiko's native library. On a machine where it
        // cannot load -- a headless build box with no font manager, say -- the test
        // skips rather than failing, because the thing under test is the layout and
        // not the platform's ability to draw.
        assumeTrue("Skiko cannot render on this machine", rendererAvailable())

        Files.createDirectories(output)
        val written = ArrayList<Path>()
        for (shot in shots) {
            val state = Preview.state(shot.section, shot.dark)
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
                written.add(file)
            } finally {
                Preview.dispose(state)
            }
        }

        assertTrue(written.size == shots.size, "expected ${shots.size} images, wrote ${written.size}")
        for (file in written) {
            assertTrue(Files.size(file) > 4_000, "$file is suspiciously small")
        }
    }

    private fun rendererAvailable(): Boolean = try {
        val scene = ImageComposeScene(width = 64, height = 64, density = Density(1f)) {}
        runCatching { scene.render() }
        scene.close()
        true
    } catch (linkage: LinkageError) {
        println("note: Skiko is unavailable here (${linkage.message}); skipping the render")
        false
    } catch (headless: java.awt.HeadlessException) {
        println("note: this is a headless machine; skipping the render")
        false
    }

    @Test
    fun both_themes_produce_a_readable_contrast() {
        // A theme that fails this is one where a screen would be unreadable, and
        // nothing but a screenshot would have shown it.
        for (palette in listOf(DarkCubColors, LightCubColors)) {
            for (pair in listOf(
                "text on the window" to (palette.text to palette.background),
                "text on a panel" to (palette.text to palette.surface),
                "dim text on a panel" to (palette.textDim to palette.surface),
                "label on a raised panel" to (palette.text to palette.surfaceRaised),
            )) {
                val ratio = contrast(pair.second.first, pair.second.second)
                assertTrue(
                    ratio >= 4.5,
                    "${pair.first} has a contrast ratio of %.2f (dark=${palette.isDark})".format(ratio),
                )
            }
        }
    }

    private fun contrast(foreground: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun relativeLuminance(color: androidx.compose.ui.graphics.Color): Double {
        fun channel(raw: Float): Double {
            val value = raw.toDouble()
            return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 860
    }
}
