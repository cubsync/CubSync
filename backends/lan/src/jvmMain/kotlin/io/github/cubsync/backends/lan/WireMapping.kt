package io.github.cubsync.backends.lan

import io.github.cubsync.core.protocol.Entry
import io.github.cubsync.core.spi.FileMeta

/** Wire representation of a file's metadata. */
internal fun FileMeta.toEntry(): Entry = Entry(
    relPath = relPath,
    size = size,
    mtimeMs = mtimeMs,
    isDirectory = isDirectory,
    symlink = symlink,
    mode = mode,
)

internal fun Entry.toMeta(): FileMeta = FileMeta(
    relPath = relPath,
    size = size,
    mtimeMs = mtimeMs,
    isDirectory = isDirectory,
    symlink = symlink,
    mode = mode,
)
