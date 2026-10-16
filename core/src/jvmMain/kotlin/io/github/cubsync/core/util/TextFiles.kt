package io.github.cubsync.core.util

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Reading and writing a whole text file, using only what Android also has.
 *
 * `Files.readString` and `Files.writeString` are Java 11 additions and are *not* in
 * Android's `android.jar`, even at API 37. They compile here, because this module is
 * built against a JDK, and then fail at runtime on a phone with a
 * `NoSuchMethodError` -- which is exactly the kind of bug that only shows up when
 * somebody tries to save their first configuration. Streams have been there since
 * Java 7 and are present on both.
 */
object TextFiles {

    fun read(path: Path): String =
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { it.readText() }

    fun write(path: Path, text: String) {
        Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { it.write(text) }
    }
}
