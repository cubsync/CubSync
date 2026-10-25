package io.github.cubsync.testing

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * A screenshot kept in the repository, and a check that the screen still looks like it.
 *
 * The point is not "does this look like the design" — a person answers that better. The
 * point is that changing the transfer screen must not quietly repaint the jobs screen,
 * which is how a round of fixes becomes a round of new bugs. Every image here was
 * rendered by the tests themselves, so a difference means the code changed, not that a
 * designer did.
 *
 * Baselines live under `src/test/resources/screenshots`, filed per operating system:
 * both ends draw text through the platform's rasteriser, and a run on Linux is not the
 * same picture as a run on Windows. A machine with no baseline for its system records
 * one instead of failing, so the check is honest about what it did or did not verify.
 *
 * Set `CUBSYNC_SCREENSHOTS=update` to accept the current rendering as the new baseline.
 * A missing baseline is written rather than failed, so a screen that gains coverage is
 * not a red build on somebody else's machine.
 */
object ScreenshotBaseline {

    /** How far a channel may drift before the pixel counts as changed; anti-aliasing, not a redesign. */
    private const val CHANNEL_TOLERANCE = 10

    /** Share of pixels allowed to differ. One in a thousand is a smudge; a moved control is more. */
    private const val CHANGED_SHARE = 0.001

    private val baselineRoot: Path = Paths.get("src", "test", "resources", "screenshots")
    private val reportRoot: Path = Paths.get("build", "screenshots-diff")

    private val updating: Boolean
        get() = System.getenv("CUBSYNC_SCREENSHOTS")?.equals("update", ignoreCase = true) == true

    /**
     * Whether comparing is worth anything here.
     *
     * A baseline belongs to the machine that drew it: the same Windows build on a runner
     * with a different set of installed fonts moves two percent of the window without a
     * line of code changing. CI therefore records and does not compare — it proves the
     * screens compose, and the comparison stays where the baseline was drawn.
     */
    private val comparing: Boolean
        get() = System.getenv("CI").isNullOrEmpty()

    /**
     * Compares the rendering against the baseline, or records it.
     *
     * Returns a failure message, or null when the screen matches. The test that calls this
     * asserts on the message, so a regression reads as "transfer.png: 4.2% of pixels moved"
     * plus the three images it left behind, rather than as a number nobody can act on.
     */
    fun compare(
        name: String,
        png: ByteArray,
        category: String? = null,
        perOperatingSystem: Boolean = true,
    ): String? {
        val dir = (category?.let { baselineRoot.resolve(it) } ?: baselineRoot)
            .let { if (perOperatingSystem) it.resolve(platform()) else it }
        val baseline = dir.resolve("$name.png")
        Files.createDirectories(dir)

        if (updating || !comparing || !Files.exists(baseline)) {
            Files.write(baseline, png)
            val why = when {
                updating -> "recording"
                !comparing -> "recording (CI: a baseline belongs to the machine that drew it)"
                else -> "first run"
            }
            println("[screenshots] $why $name -> $baseline")
            return null
        }

        val expected = decode(Files.readAllBytes(baseline))
            ?: return "screenshots/$name: the baseline is not a readable PNG"
        val actual = decode(png)
            ?: return "screenshots/$name: the rendering could not be decoded"

        if (expected.width != actual.width || expected.height != actual.height) {
            return "screenshots/$name: size is ${actual.width}x${actual.height}, baseline is " +
                "${expected.width}x${expected.height} — the window or the density changed"
        }

        var changed = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        val diff = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                val a = actual.getRGB(x, y)
                val e = expected.getRGB(x, y)
                val delta = maxOf(
                    kotlin.math.abs((a shr 16 and 0xFF) - (e shr 16 and 0xFF)),
                    kotlin.math.abs((a shr 8 and 0xFF) - (e shr 8 and 0xFF)),
                    kotlin.math.abs((a and 0xFF) - (e and 0xFF)),
                )
                if (delta > CHANNEL_TOLERANCE) {
                    changed++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    diff.setRGB(x, y, 0xFF0000)
                } else {
                    val grey = (a shr 16 and 0xFF) * 3 / 10
                    diff.setRGB(x, y, (grey shl 16) or (grey shl 8) or grey)
                }
            }
        }

        val total = actual.width * actual.height
        val share = changed.toDouble() / total
        if (share <= CHANGED_SHARE) return null

        val out = reportRoot.resolve(name).also { Files.createDirectories(it) }
        Files.write(out.resolve("actual.png"), png)
        Files.copy(baseline, out.resolve("baseline.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        ImageIO.write(diff, "png", out.resolve("diff.png").toFile())
        return "screenshots/$name: ${format(share)} of pixels moved " +
            "($changed of $total, region ${maxX - minX + 1}x${maxY - minY + 1} at $minX,$minY) — " +
            "compare baseline.png / actual.png / diff.png in $out; " +
            "run with CUBSYNC_SCREENSHOTS=update if the change was meant"
    }

    private fun format(share: Double): String = String.format("%.2f%%", share * 100)

    private fun platform(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "macos"
            else -> "linux"
        }
    }

    private fun decode(png: ByteArray): BufferedImage? =
        runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()
}
