package io.github.cubsync.service

import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * One line of the application log.
 *
 * [area] groups entries the way a person thinks about them (a connection, a run, a
 * folder pair), so a front end can filter without parsing the message.
 */
data class LogEntry(
    val sequence: Long,
    val atMs: Long,
    val level: LogLevel,
    val area: String,
    val message: String,
    val detail: String = "",
)

/**
 * The application's own log, kept in memory and streamed to whoever is listening.
 *
 * A file log is what a headless daemon needs; an in-memory ring buffer is what a
 * window needs, because the useful question in a GUI is "what happened just now"
 * rather than "what happened last Tuesday". Both front ends share this one, so the
 * CLI can print it and the desktop app can render it without either knowing how the
 * other works.
 */
class AppLog(private val limit: Int = DEFAULT_LIMIT) {

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(minOf(limit, 256))
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()
    private var sequence = 0L
    private var dropped = 0

    /** How many entries were evicted because the buffer is bounded. */
    val droppedCount: Int get() = synchronized(lock) { dropped }

    fun debug(area: String, message: String, detail: String = "") = write(LogLevel.DEBUG, area, message, detail)

    fun info(area: String, message: String, detail: String = "") = write(LogLevel.INFO, area, message, detail)

    fun warn(area: String, message: String, detail: String = "") = write(LogLevel.WARN, area, message, detail)

    fun error(area: String, message: String, detail: String = "") = write(LogLevel.ERROR, area, message, detail)

    fun write(level: LogLevel, area: String, message: String, detail: String = ""): LogEntry {
        val entry = synchronized(lock) {
            val created = LogEntry(++sequence, System.currentTimeMillis(), level, area, message, detail)
            buffer.addLast(created)
            while (buffer.size > limit) {
                buffer.removeFirst()
                dropped++
            }
            created
        }
        for (listener in listeners) {
            // A listener that throws must not take the logging call down with it.
            runCatching { listener(entry) }
        }
        return entry
    }

    /** A snapshot, oldest first. */
    fun entries(minimumLevel: LogLevel = LogLevel.DEBUG): List<LogEntry> = synchronized(lock) {
        if (minimumLevel == LogLevel.DEBUG) buffer.toList() else buffer.filter { it.level >= minimumLevel }
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        dropped = 0
    }

    /** The listener runs on whichever thread logged the entry, so it must not block. */
    fun subscribe(listener: (LogEntry) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 2_000
    }
}
