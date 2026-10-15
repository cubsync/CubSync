package io.github.cubsync.cli

/** Minimal option parser: enough for a handful of commands, no dependencies. */
class Args(private val values: Map<String, List<String>>, val positional: List<String>) {

    fun string(name: String, default: String? = null): String? =
        values[name]?.lastOrNull() ?: default

    fun stringList(name: String): List<String> = values[name] ?: emptyList()

    fun int(name: String, default: Int): Int = values[name]?.lastOrNull()?.toIntOrNull() ?: default

    fun long(name: String, default: Long): Long = values[name]?.lastOrNull()?.toLongOrNull() ?: default

    fun flag(name: String): Boolean = values.containsKey(name)

    fun requireString(name: String): String = string(name)
        ?: throw UsageException("--$name is required")

    companion object {
        /**
         * Parses `--name value`, `--name=value` and boolean `--flag`.
         *
         * Values are never taken from a following token when that token starts with
         * `--`, so a forgotten value produces a clear error instead of silently
         * consuming the next flag.
         */
        fun parse(arguments: List<String>): Args {
            val values = LinkedHashMap<String, MutableList<String>>()
            val positional = mutableListOf<String>()
            var index = 0
            while (index < arguments.size) {
                val token = arguments[index]
                when {
                    token.startsWith("--") -> {
                        val body = token.removePrefix("--")
                        val equals = body.indexOf('=')
                        if (equals >= 0) {
                            values.getOrPut(body.substring(0, equals)) { mutableListOf() }
                                .add(body.substring(equals + 1))
                        } else {
                            val next = arguments.getOrNull(index + 1)
                            if (next != null && !next.startsWith("--")) {
                                values.getOrPut(body) { mutableListOf() }.add(next)
                                index++
                            } else {
                                values.getOrPut(body) { mutableListOf() }
                            }
                        }
                    }

                    else -> positional.add(token)
                }
                index++
            }
            return Args(values, positional)
        }
    }
}

class UsageException(message: String) : Exception(message)

/** Thrown when a command cannot do what was asked; the message is the whole story. */
class CommandFailedException(message: String, val exitCode: Int = 1) : Exception(message)
