package com.kazeracer.livenote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * - Puts the note back after a reboot, an app update, or a swipe-away
 *   (Android 14+ lets users dismiss ongoing notifications).
 * - Ticks a to-do from a notification action button.
 */
class RepostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CHECK) {
            val store = NoteStore(context)
            val line = intent.getIntExtra(EXTRA_LINE, -1)
            val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
            MarkdownCore.toggleTodo(store.body, line, text)?.let { store.body = it }
        }
        NoteNotifier.refresh(context)
    }

    companion object {
        const val ACTION_DISMISSED = "com.kazeracer.livenote.DISMISSED"
        const val ACTION_CHECK = "com.kazeracer.livenote.CHECK"
        const val EXTRA_LINE = "line"
        const val EXTRA_TEXT = "text"
    }
}
