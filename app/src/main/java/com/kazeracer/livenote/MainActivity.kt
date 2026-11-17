package com.kazeracer.livenote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.TextView

/** A floating card over the wallpaper, looking like the note in the shade. */
class MainActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var title: EditText
    private lateinit var body: MarkdownEditor
    private lateinit var pin: TextView
    private lateinit var timer: TextView
    private lateinit var banner: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val saveNow = Runnable { save() }
    private var dirty = false

    /** The note can change from the notification (ticking a to-do): show it. */
    private val external = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "pinned" || key == "scheduledAt") updatePin()
        if (key == "body" && !dirty && store.body != body.getMarkdown()) {
            val sel = body.selectionStart
            body.setMarkdown(store.body, store.collapsed)
            body.setSelection(sel.coerceIn(0, body.text.length))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = NoteStore(this)

        title = findViewById(R.id.title)
        body = findViewById(R.id.body)
        pin = findViewById(R.id.pin)
        timer = findViewById(R.id.timer)
        banner = findViewById(R.id.banner)

        setupWindow()

        // First launch: a little to-do to start with.
        if (!store.prefs.contains("title")) {
            store.save(getString(R.string.welcome_title), getString(R.string.welcome_body), emptySet())
        }

        title.setText(store.title)
        body.setMarkdown(store.body, store.collapsed)
        updatePin()

        title.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable) {
                // Enter in the title jumps to the body, like Notion.
                val nl = e.indexOf('\n')
                if (nl >= 0) {
                    e.delete(nl, nl + 1)
                    body.requestFocus()
                    body.setSelection(0)
                    return
                }
                        scheduleSave()
            }
        })
        body.onChanged = { scheduleSave() }

        pin.setOnClickListener {
            Scheduler.cancel(this)
            store.pinned = !store.pinned
            save()
            updatePin()
        }
        timer.setOnClickListener { showTimer() }

        // Tap outside the card closes it.
        findViewById<View>(R.id.root).setOnClickListener { finish() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        if (title.text.isBlank()) {
            title.requestFocus()
        } else {
            body.requestFocus()
            body.setSelection(body.text.length)
        }
        store.prefs.registerOnSharedPreferenceChangeListener(external)
    }

    override fun onDestroy() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(external)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        NoteNotifier.refresh(this)
        updateBanner()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(saveNow)
        save()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        NoteNotifier.refresh(this)
        updateBanner()
    }

    private fun scheduleSave() {
        dirty = true
        handler.removeCallbacks(saveNow)
        handler.postDelayed(saveNow, 400)
    }

    private fun save() {
        if (dirty) {
            store.save(title.text.toString(), body.getMarkdown(), body.getCollapsedLines())
            dirty = false
        }
        NoteNotifier.refresh(this)
    }



    private fun updatePin() {
        pin.setText(if (store.pinned) R.string.pinned else R.string.unpinned)
        pin.setTextColor(getColor(if (store.pinned) R.color.text else R.color.text_secondary))
        // Filled bell = in the notifications, outline = not.
        pin.setCompoundDrawablesRelativeWithIntrinsicBounds(
            getDrawable(if (store.pinned) R.drawable.ic_bell_small else R.drawable.ic_bell_outline_small), null, null, null,
        )
        val at = store.scheduledAt
        timer.text = if (at > 0) Scheduler.label(this, at) else ""
        timer.compoundDrawablePadding = if (at > 0) (4 * resources.displayMetrics.density).toInt() else 0
    }

    /** Minimal timer: hide the note now, show it again at the chosen time. */
    private fun showTimer() {
        val at = store.scheduledAt
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(label: String, action: () -> Unit) { labels.add(label); actions.add(action) }
        fun set(time: Long) { save(); Scheduler.schedule(this, time); updatePin() }

        if (at > 0) item(getString(R.string.timer_cancel, Scheduler.label(this, at))) {
            Scheduler.cancel(this); store.pinned = true; NoteNotifier.refresh(this); updatePin()
        }
        item(getString(R.string.timer_30m)) { set(Scheduler.inMinutes(30)) }
        item(getString(R.string.timer_1h)) { set(Scheduler.inMinutes(60)) }
        item(getString(R.string.timer_3h)) { set(Scheduler.inMinutes(180)) }
        if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 20) {
            item(getString(R.string.timer_evening)) { set(Scheduler.nextAt(20, 0)) }
        }
        item(getString(R.string.timer_tomorrow)) {
            val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
            c.set(java.util.Calendar.HOUR_OF_DAY, 9); c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            set(c.timeInMillis)
        }
        item(getString(R.string.timer_pick)) {
            val now = java.util.Calendar.getInstance()
            TimePickerDialog(this, { _, h, m -> set(Scheduler.nextAt(h, m)) },
                now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
        }
        if (!Scheduler.canBeExact(this)) item(getString(R.string.timer_exact)) {
            open(Scheduler.exactSettingsIntent(this))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.timer_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun updateBanner() {
        val promote = NoteNotifier.canPromote(this)
        when {
            !NoteNotifier.canNotify(this) -> {
                banner.setText(R.string.banner_permission)
                banner.setOnClickListener {
                    if (Build.VERSION.SDK_INT >= 33 && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    } else {
                        open(NoteNotifier.notificationSettingsIntent(this))
                    }
                }
                banner.visibility = View.VISIBLE
            }
            promote == false -> {
                banner.setText(R.string.banner_promoted)
                banner.setOnClickListener {
                    try {
                        startActivity(NoteNotifier.promotionSettingsIntent(this))
                    } catch (_: ActivityNotFoundException) {
                        open(NoteNotifier.notificationSettingsIntent(this))
                    }
                }
                banner.visibility = View.VISIBLE
            }
            else -> banner.visibility = View.GONE
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT < 30) return
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        // Light icons: they sit on the dimmed wallpaper.
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(0, light)
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
    }
}
