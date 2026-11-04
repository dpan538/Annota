package io.github.dpan538.annota

import android.content.Context

/** Shared opt-in state for bundled semantic processing; ordinary offline lookup stays available. */
class Prefs(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var semanticDictionary: Boolean
        get() = preferences.getBoolean(SEMANTIC_DICTIONARY, true)
        set(value) {
            preferences.edit().putBoolean(SEMANTIC_DICTIONARY, value).apply()
        }

    companion object {
        const val SEMANTIC_DICTIONARY = "semanticDictionary"
    }
}
