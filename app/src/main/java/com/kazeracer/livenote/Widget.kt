package com.kazeracer.livenote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Home-screen widget: the note, scrollable; a tap opens it. */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context, id))
        manager.notifyAppWidgetViewDataChanged(ids, R.id.list)
    }

    companion object {
        /** Called whenever the note changes. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) manager.updateAppWidget(id, views(context, id))
            manager.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        @Suppress("DEPRECATION")
        private fun views(context: Context, id: Int): RemoteViews {
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val tap = PendingIntent.getActivity(
                context, 10, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // List rows fill in this template, so it has to be mutable on Android 12+.
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val template = PendingIntent.getActivity(
                context, 11, open, mutable or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val adapter = Intent(context, WidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))

            return RemoteViews(context.packageName, R.layout.widget).apply {
                setRemoteAdapter(R.id.list, adapter)
                setEmptyView(R.id.list, R.id.empty)
                setPendingIntentTemplate(R.id.list, template)
                setOnClickPendingIntent(android.R.id.background, tap)
                setOnClickPendingIntent(R.id.empty, tap)

                val store = NoteStore(context)
                setImageViewResource(R.id.pin_toggle, if (store.pinned) R.drawable.ic_bell else R.drawable.ic_bell_outline)
                setContentDescription(R.id.pin_toggle, context.getString(if (store.pinned) R.string.pinned else R.string.unpinned))
                if (store.scheduledAt > 0) {
                    setTextViewText(R.id.pin_time, Scheduler.label(context, store.scheduledAt))
                    setViewVisibility(R.id.pin_time, View.VISIBLE)
                } else {
                    setViewVisibility(R.id.pin_time, View.GONE)
                }
                val toggle = PendingIntent.getBroadcast(
                    context, 12,
                    Intent(context, RepostReceiver::class.java).setAction(RepostReceiver.ACTION_TOGGLE_PIN),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                setOnClickPendingIntent(R.id.pin_toggle, toggle)
            }
        }
    }
}

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val context: Context) : RemoteViewsFactory {
        private var rows: List<CharSequence> = emptyList()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            val store = NoteStore(context)
            if (store.isEmpty) {
                rows = emptyList()
                return
            }
            val title = store.title.trim().ifEmpty { context.getString(R.string.title_hint) }
            val body = Markdown.toNotification(store.body, store.collapsed, Int.MAX_VALUE / 2)
            val lines = ArrayList<CharSequence>()
            lines.add(title)
            if (store.body.isNotBlank()) {
                var start = 0
                for (i in 0..body.length) {
                    if (i == body.length || body[i] == '\n') {
                        lines.add(body.subSequence(start, i))
                        start = i + 1
                    }
                }
            }
            rows = lines
        }

        override fun getCount() = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val layout = if (position == 0) R.layout.widget_title else R.layout.widget_line
            return RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.row, rows.getOrNull(position) ?: "")
                setOnClickFillInIntent(R.id.row, Intent())
            }
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
