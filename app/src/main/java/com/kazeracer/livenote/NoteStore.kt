package com.kazeracer.livenote

import android.content.Context
import android.content.SharedPreferences

/** The one note this app keeps. */
class NoteStore(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("note", Context.MODE_PRIVATE)

    var title: String
        get() = prefs.getString("title", "") ?: ""
        set(v) = prefs.edit().putString("title", v).apply()

    var body: String
        get() = prefs.getString("body", "") ?: ""
        set(v) = prefs.edit().putString("body", v).apply()

    /** Line indices (in [body]) of toggles that are closed. */
    var collapsed: Set<Int>
        get() = (prefs.getString("collapsed", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = prefs.edit().putString("collapsed", v.sorted().joinToString(",")).apply()

    var pinned: Boolean
        get() = prefs.getBoolean("pinned", true)
        set(v) = prefs.edit().putBoolean("pinned", v).apply()

    val isEmpty: Boolean get() = title.isBlank() && body.isBlank()

    fun save(title: String, body: String, collapsed: Set<Int>) {
        prefs.edit()
            .putString("title", title)
            .putString("body", body)
            .putString("collapsed", collapsed.sorted().joinToString(","))
            .apply()
    }
}
