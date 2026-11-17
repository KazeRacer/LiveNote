package com.kazeracer.livenote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.util.Calendar
import java.util.Date

/** "Show the note at…": hides it now, pins it back at the chosen time. */
object Scheduler {

    fun schedule(context: Context, at: Long) {
        val store = NoteStore(context)
        store.scheduledAt = at
        store.pinned = false
        arm(context, at)
        NoteNotifier.refresh(context)
    }

    fun cancel(context: Context) {
        NoteStore(context).scheduledAt = 0L
        alarms(context).cancel(intent(context))
    }

    /** The alarm went off (or its time passed while the phone was off). */
    fun fire(context: Context) {
        val store = NoteStore(context)
        store.scheduledAt = 0L
        store.pinned = true
        NoteNotifier.refresh(context)
    }

    /** Alarms don't survive a reboot: re-arm, or fire if the time already passed. */
    fun restore(context: Context) {
        val at = NoteStore(context).scheduledAt
        if (at == 0L) return
        if (at <= System.currentTimeMillis()) fire(context) else arm(context, at)
    }

    fun canBeExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || alarms(context).canScheduleExactAlarms()

    fun exactSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(android.net.Uri.parse("package:" + context.packageName))

    private fun arm(context: Context, at: Long) {
        val am = alarms(context)
        val pi = intent(context)
        if (canBeExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 20,
        Intent(context, RepostReceiver::class.java).setAction(RepostReceiver.ACTION_SHOW),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ---- times ----

    fun inMinutes(min: Int) = System.currentTimeMillis() + min * 60_000L

    /** Next occurrence of hh:mm (today if still ahead, else tomorrow). */
    fun nextAt(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun label(context: Context, at: Long): String {
        val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(at))
        val day = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.DAY_OF_YEAR)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return if (day == today) time else context.getString(R.string.tomorrow_at, time)
    }
}
