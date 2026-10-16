package io.github.cubsync.android

import android.content.Context
import io.github.cubsync.service.SettingsStorage

/**
 * The phone's store for settings.
 *
 * `SharedPreferences` under the same keys the desktop uses, because the keys are owned by
 * `AppSettings` in the shared layer and this class only moves strings in and out. That is
 * what makes "the same setting" the same setting on both ends rather than two settings
 * that happen to have similar names.
 */
class AndroidSettings(context: Context) : SettingsStorage {

    private val store = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): Map<String, String> =
        store.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    /**
     * Replaces the whole preference map.
     *
     * Cleared first rather than merged: the map that arrives is the whole of the
     * preferences, and merging would keep a key that has been deleted — a setting nobody
     * can see and everybody is still affected by.
     */
    override fun write(values: Map<String, String>) {
        val editor = store.edit().clear()
        for ((key, value) in values) editor.putString(key, value)
        editor.apply()
    }

    private companion object {
        const val PREFERENCES = "cubsync"
    }
}
