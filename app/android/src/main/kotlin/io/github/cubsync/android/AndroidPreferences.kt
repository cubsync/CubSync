package io.github.cubsync.android

import android.content.Context
import android.content.SharedPreferences
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.SyncDirection

/**
 * What the phone remembers between launches.
 *
 * Shared preferences rather than a file in the state directory, because that is what
 * Android backs up and what a person expects the system's own settings screen to be
 * able to clear.
 */
interface Preferences {
    var darkTheme: Boolean
    var defaultDirection: SyncDirection
    var defaultCompare: CompareMode
    var defaultConflict: ConflictPolicy
    var defaultDelete: DeletePolicy
    var sharedRoots: List<String>
    var sharePort: Int
    var lastPeer: String
}

class AndroidPreferences(context: Context) : Preferences {

    private val store: SharedPreferences =
        context.getSharedPreferences("cubsync", Context.MODE_PRIVATE)

    override var darkTheme: Boolean
        get() = store.getBoolean("theme.dark", true)
        set(value) = store.edit().putBoolean("theme.dark", value).apply()

    override var defaultDirection: SyncDirection
        get() = enumOr("default.direction", SyncDirection.TWO_WAY)
        set(value) = store.edit().putString("default.direction", value.name).apply()

    override var defaultCompare: CompareMode
        get() = enumOr("default.compare", CompareMode.MTIME_AND_SIZE)
        set(value) = store.edit().putString("default.compare", value.name).apply()

    override var defaultConflict: ConflictPolicy
        get() = enumOr("default.conflict", ConflictPolicy.ASK)
        set(value) = store.edit().putString("default.conflict", value.name).apply()

    override var defaultDelete: DeletePolicy
        get() = enumOr("default.delete", DeletePolicy.RECYCLE)
        set(value) = store.edit().putString("default.delete", value.name).apply()

    override var sharedRoots: List<String>
        get() = store.getString("server.roots", "").orEmpty().split('|').filter { it.isNotBlank() }
        set(value) = store.edit().putString("server.roots", value.joinToString("|")).apply()

    override var sharePort: Int
        get() = store.getInt("server.port", 0)
        set(value) = store.edit().putInt("server.port", value).apply()

    override var lastPeer: String
        get() = store.getString("peer.last", "").orEmpty()
        set(value) = store.edit().putString("peer.last", value).apply()

    private inline fun <reified T : Enum<T>> enumOr(key: String, fallback: T): T {
        val raw = store.getString(key, null) ?: return fallback
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }
}
