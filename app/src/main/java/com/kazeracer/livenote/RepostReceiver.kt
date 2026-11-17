package com.kazeracer.livenote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * - Puts the note back after a reboot, an app update, or a swipe-away
 *   (Android 14+ lets users dismiss ongoing notifications).
 * - Ticks a to-do from a notification action button.
 * - Shows the note when its timer goes off.
 * - Shows/hides the note from the widget button.
 */
class RepostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK -> {
                val store = NoteStore(context)
                val line = intent.getIntExtra(EXTRA_LINE, -1)
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                MarkdownCore.toggleTodo(store.body, line, text)?.let { store.body = it }
            }
            ACTION_SHOW -> {
                Scheduler.fire(context)
                return
            }
            ACTION_TOGGLE_PIN -> {
                val store = NoteStore(context)
                Scheduler.cancel(context)
                store.pinned = !store.pinned
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Scheduler.restore(context)
        }
        NoteNotifier.refresh(context)
    }

    companion object {
        const val ACTION_DISMISSED = "com.kazeracer.livenote.DISMISSED"
        const val ACTION_CHECK = "com.kazeracer.livenote.CHECK"
        const val ACTION_SHOW = "com.kazeracer.livenote.SHOW"
        const val ACTION_TOGGLE_PIN = "com.kazeracer.livenote.TOGGLE_PIN"
        const val EXTRA_LINE = "line"
        const val EXTRA_TEXT = "text"
    }
}
