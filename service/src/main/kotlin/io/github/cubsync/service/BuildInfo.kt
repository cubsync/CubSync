package io.github.cubsync.service

import io.github.cubsync.core.protocol.Protocol

/**
 * Build facts that every front end reports: the version printed in a window title,
 * the version a peer is told during a handshake, and the platform string.
 *
 * The version is read from a resource the build generates out of
 * `gradle.properties`. There is no constant to keep in step, because the last time
 * this project had one it drifted.
 */
object BuildInfo {

    /** Falls back to a value that is obviously not a release. */
    const val UNKNOWN_VERSION: String = "0.0.0-dev"

    private const val RESOURCE: String = "/cubsync-build.properties"

    val VERSION: String = readVersion()

    val PROTOCOL_VERSION: Int = Protocol.VERSION

    val PLATFORM: String =
        "${System.getProperty("os.name") ?: "unknown"} ${System.getProperty("os.arch") ?: ""}".trim()

    /** Short form for a window title or an about box. */
    val versionLine: String get() = "CubSync $VERSION"

    private fun readVersion(): String {
        val fromResource = runCatching {
            BuildInfo::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
                stream.reader().readText().lineSequence()
                    .firstOrNull { it.startsWith("version=") }
                    ?.removePrefix("version=")
                    ?.trim()
            }
        }.getOrNull()
        if (!fromResource.isNullOrEmpty()) return fromResource

        // A repackaged jar may not carry the resource; the manifest is the next
        // best place, and only then does the unknown sentinel appear.
        return BuildInfo::class.java.`package`?.implementationVersion ?: UNKNOWN_VERSION
    }
}
