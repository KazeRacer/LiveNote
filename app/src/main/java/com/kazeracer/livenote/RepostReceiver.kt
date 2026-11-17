package com.kazeracer.livenote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Puts the note back: after a reboot, after an app update, and when the user
 * swipes the notification away (Android 14+ lets users dismiss ongoing notifications).
 */
class RepostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NoteNotifier.refresh(context)
    }

    companion object {
        const val ACTION_DISMISSED = "com.kazeracer.livenote.DISMISSED"
    }
}
