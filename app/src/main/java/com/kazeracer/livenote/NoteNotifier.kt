package com.kazeracer.livenote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings

object NoteNotifier {
    private const val CHANNEL = "note"
    private const val ID = 1

    // Rough rows the expanded notification can show before the system cuts it.
    private const val BUDGET_ROWS = 8
    private const val BUDGET_ROWS_WITH_ACTIONS = 6

    // Android 16 "Live Updates" (promoted ongoing notifications). Used through their
    // raw names / reflection so the app also builds and runs on older platforms.
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val ACTION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"

    fun refresh(context: Context) {
        WidgetProvider.update(context)
        val store = NoteStore(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!store.pinned || store.isEmpty || !canNotify(context)) {
            nm.cancel(ID)
            return
        }
        ensureChannel(context)
        nm.notify(ID, build(context, store.title, store.body, store.collapsed))
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    /** null = the platform has no Live Updates (before Android 16). */
    fun canPromote(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.javaClass.getMethod("canPostPromotedNotifications").invoke(nm) as Boolean
        } catch (_: Throwable) {
            null
        }
    }

    fun promotionSettingsIntent(context: Context): Intent =
        Intent(ACTION_PROMOTION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        // Not MIN/LOW: Live Updates need a channel that is allowed on the lock screen
        // and status bar. Silent, no vibration: the note should just sit there.
        val ch = NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = context.getString(R.string.channel_desc)
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.enableLights(false)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    private fun build(context: Context, rawTitle: String, body: String, collapsed: Set<Int>): Notification {
        val title = rawTitle.trim().ifEmpty { context.getString(R.string.title_hint) }
        // Up to 3 buttons that tick the next open to-dos, right from the shade.
        val todos = MarkdownCore.openTodos(body, collapsed).take(3)
        val rendered = Markdown.toNotification(body, collapsed, if (todos.isEmpty()) BUDGET_ROWS else BUDGET_ROWS_WITH_ACTIONS) { n ->
            context.resources.getQuantityString(R.plurals.more_lines, n, n)
        }
        val firstLine = Markdown.firstLine(body)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismissed = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, RepostReceiver::class.java).setAction(RepostReceiver.ACTION_DISMISSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // What the lock screen / always-on display shows: the title only.
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(firstLine)
            .setStyle(Notification.BigTextStyle().bigText(rendered))
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPublicVersion(public)
            .setColorized(false)

        for ((i, t) in todos.withIndex()) {
            val pi = PendingIntent.getBroadcast(
                context, 100 + i,
                Intent(context, RepostReceiver::class.java)
                    .setAction(RepostReceiver.ACTION_CHECK)
                    .putExtra(RepostReceiver.EXTRA_LINE, t.line)
                    .putExtra(RepostReceiver.EXTRA_TEXT, t.text),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val label = "☐ " + if (t.text.length > 18) t.text.take(17) + "…" else t.text
            b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, pi).build())
        }

        if (Build.VERSION.SDK_INT >= 36) {
            // Short text for the status-bar / AOD chip of a Live Update.
            try {
                b.javaClass.getMethod("setShortCriticalText", String::class.java)
                    .invoke(b, title.take(12))
            } catch (_: Throwable) {
            }
        }

        // Ask the system to show this as a Live Update (status-bar chip, lock screen, AOD).
        b.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                b.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    .invoke(b, true)
            } catch (_: Throwable) {
            }
        }
        return b.build()
    }
}
