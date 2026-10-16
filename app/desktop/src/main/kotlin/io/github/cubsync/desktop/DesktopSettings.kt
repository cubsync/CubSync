package io.github.cubsync.desktop

import io.github.cubsync.service.SettingsStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The desktop's store for settings.
 *
 * A properties file in the state directory beside the configurations, for the same reason
 * the configurations live there: the whole of CubSync's memory is one folder that can be
 * moved, backed up or deleted.
 *
 * The preference keys themselves are owned by `AppSettings` in the shared layer, and this
 * class only moves strings in and out. That is what keeps a desktop and a phone reading
 * the same key for the same setting. The only keys it owns are the window's geometry,
 * which the phone has no equivalent of.
 */
class DesktopSettings(private val file: Path) : SettingsStorage {

    private val properties = Properties()

    var windowWidth: Int = 1280
    var windowHeight: Int = 860

    init {
        load()
    }

    private fun load() {
        if (!Files.isRegularFile(file)) return
        runCatching { Files.newInputStream(file).use { properties.load(it) } }
        windowWidth = properties.getProperty("window.width")?.toIntOrNull() ?: 1280
        windowHeight = properties.getProperty("window.height")?.toIntOrNull() ?: 860
    }

    /**
     * Written through a temporary file, so a crash halfway through cannot leave a
     * half-parsed properties file that resets everything.
     */
    private fun save() {
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            Files.newOutputStream(temporary).use { properties.store(it, "CubSync desktop settings") }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun saveWindowSize(width: Int, height: Int) {
        windowWidth = width
        windowHeight = height
        properties.setProperty("window.width", width.toString())
        properties.setProperty("window.height", height.toString())
        save()
    }

    // ------------------------------------------------------------ SettingsStorage

    override fun read(): Map<String, String> = properties.entries.associate {
        it.key.toString() to it.value.toString()
    }

    /**
     * Replaces the preference keys and keeps the window's.
     *
     * A blunt replacement rather than a merge: the map that arrives is the whole of the
     * preferences, and merging would keep a key that has been deleted — a setting nobody
     * can see but everybody is still affected by.
     */
    override fun write(values: Map<String, String>) {
        val keep = properties.entries
            .filter { it.key.toString().startsWith("window.") }
            .associate { it.key.toString() to it.value.toString() }
        properties.clear()
        keep.forEach { (key, value) -> properties.setProperty(key, value) }
        values.forEach { (key, value) -> properties.setProperty(key, value) }
        save()
    }

    companion object {
        const val FILE_NAME: String = "desktop.properties"

        fun inStateDirectory(stateDirectory: Path): DesktopSettings =
            DesktopSettings(stateDirectory.resolve(FILE_NAME))

        fun at(stateDirectory: String): DesktopSettings =
            inStateDirectory(Paths.get(stateDirectory).toAbsolutePath().normalize())
    }
}
