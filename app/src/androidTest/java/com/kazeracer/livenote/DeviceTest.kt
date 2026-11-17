package com.kazeracer.livenote

import android.app.NotificationManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Android emulator in CI. Hosts the actual widget, taps the bell,
 * and checks both the bell state and the notification.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val store = NoteStore(ctx)
    private lateinit var host: AppWidgetHost
    private var widgetId = -1

    @Before fun setUp() {
        Scheduler.cancel(ctx)
        store.save("Spesa", "[] Latte\n[] Pane\n[x] Uova", emptySet())
        store.pinned = true
        NoteNotifier.refresh(ctx)
        inst.runOnMainSync {
            host = AppWidgetHost(ctx, 4242)
            host.startListening()
        }
    }

    @After fun tearDown() {
        inst.runOnMainSync {
            if (widgetId >= 0) host.deleteAppWidgetId(widgetId)
            host.stopListening()
        }
    }

    private fun noteShown() = nm.activeNotifications.firstOrNull { it.id == 1 }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            inst.runOnMainSync { ok = cond() }
            if (ok) return
            SystemClock.sleep(150)
        }
        var state = ""
        inst.runOnMainSync { state = debugState() }
        throw AssertionError("timed out waiting for: $what | $state")
    }

    private fun bell(view: AppWidgetHostView): ImageView? = view.findViewById(R.id.pin_toggle)

    private var hostView: AppWidgetHostView? = null

    private fun debugState(): String {
        val v = hostView
        return "pinned=${store.pinned} scheduledAt=${store.scheduledAt} shown=${noteShown() != null} " +
            "bellDesc=${v?.let { bell(it)?.contentDescription }} hostChild=${v?.getChildAt(0)?.javaClass?.simpleName}"
    }

    @Test fun widgetBellTogglesTheNotification() {
        val mgr = AppWidgetManager.getInstance(ctx)
        widgetId = host.allocateAppWidgetId()
        assertTrue(
            "bind refused (run: adb shell appwidget grantbind --package ${ctx.packageName})",
            mgr.bindAppWidgetIdIfAllowed(widgetId, ComponentName(ctx, WidgetProvider::class.java)),
        )
        lateinit var view: AppWidgetHostView
        inst.runOnMainSync { view = host.createView(ctx, widgetId, mgr.getAppWidgetInfo(widgetId)) }
        hostView = view
        WidgetProvider.update(ctx)

        val on = ctx.getString(R.string.pinned)
        val off = ctx.getString(R.string.unpinned)
        waitFor("widget shows filled bell") { bell(view)?.contentDescription == on }
        assertNotNull(noteShown())

        // Tap: note leaves the shade, bell becomes the outline one.
        inst.runOnMainSync { bell(view)!!.performClick() }
        waitFor("pinned=false after tap") { !store.pinned }
        waitFor("outline bell") { bell(view)?.contentDescription == off }
        waitFor("notification removed") { noteShown() == null }

        // Tap again: back.
        inst.runOnMainSync { bell(view)!!.performClick() }
        waitFor("pinned=true after 2nd tap") { store.pinned }
        waitFor("filled bell again") { bell(view)?.contentDescription == on }
        waitFor("notification back") { noteShown() != null }

        // A tap also cancels a running timer.
        inst.runOnMainSync { Scheduler.schedule(ctx, Scheduler.inMinutes(60)) }
        waitFor("outline bell with timer") { bell(view)?.contentDescription == off }
        val time = view.findViewById<View>(R.id.pin_time)
        waitFor("time label visible") { time.visibility == View.VISIBLE }
        inst.runOnMainSync { bell(view)!!.performClick() }
        waitFor("timer cancelled, note shown") { store.scheduledAt == 0L && store.pinned && noteShown() != null }
        waitFor("time label hidden") { time.visibility == View.GONE }
    }

    @Test fun timerBringsTheNoteBackAndVibrates() {
        Scheduler.schedule(ctx, Scheduler.inMinutes(30))
        assertEquals(false, store.pinned)
        waitFor("hidden while timer runs") { noteShown() == null }

        Scheduler.fire(ctx)
        waitFor("note back after timer") { noteShown() != null }
        val sbn = noteShown()!!
        assertEquals(NoteNotifier.CHANNEL_ALERT, sbn.notification.channelId)
        assertTrue("alert channel must vibrate", nm.getNotificationChannel(NoteNotifier.CHANNEL_ALERT).shouldVibrate())
        assertEquals(0L, store.scheduledAt)

        // Editing afterwards goes back to the silent channel.
        NoteNotifier.refresh(ctx)
        waitFor("silent channel after edit") { noteShown()?.notification?.channelId == NoteNotifier.CHANNEL }
        Log.i("LiveNoteTest", "flags=0x" + Integer.toHexString(noteShown()!!.notification.flags))
    }

    @Test fun tickFromNotificationAction() {
        val n = noteShown()!!.notification
        assertEquals(2, n.actions.size) // Latte, Pane (Uova is done)
        n.actions[0].actionIntent.send()
        waitFor("Latte ticked") { store.body.startsWith("[x] Latte") }
        waitFor("one action left") { noteShown()?.notification?.actions?.size == 1 }
    }

    @Test fun emptyNoteShowsNothing() {
        store.save("", "", emptySet())
        NoteNotifier.refresh(ctx)
        waitFor("no notification for empty note") { noteShown() == null }
        assertNull(noteShown())
    }
}
