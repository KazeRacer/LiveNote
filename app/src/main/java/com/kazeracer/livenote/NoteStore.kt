package com.kazeracer.livenote

import android.content.Context

/** The one note this app keeps. */
class NoteStore(context: Context) {
    private val prefs = context.getSharedPreferences("note", Context.MODE_PRIVATE)

    var title: String
        get() = prefs.getString("title", "") ?: ""
        set(v) = prefs.edit().putString("title", v).apply()

    var body: String
        get() = prefs.getString("body", "") ?: ""
        set(v) = prefs.edit().putString("body", v).apply()

    var pinned: Boolean
        get() = prefs.getBoolean("pinned", true)
        set(v) = prefs.edit().putBoolean("pinned", v).apply()

    val isEmpty: Boolean get() = title.isBlank() && body.isBlank()
}
