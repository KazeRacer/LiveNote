package com.kazeracer.livenote

import android.Manifest
import android.app.Activity
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
    private lateinit var banner: TextView
    private lateinit var crumb: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val saveNow = Runnable { save() }
    private var dirty = false

    /** The note can change from the notification (ticking a to-do): show it. */
    private val external = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
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
        banner = findViewById(R.id.banner)
        crumb = findViewById(R.id.crumb)

        setupWindow()

        title.setText(store.title)
        body.setMarkdown(store.body, store.collapsed)
        updateCrumb()
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
                updateCrumb()
                scheduleSave()
            }
        })
        body.onChanged = { scheduleSave() }

        pin.setOnClickListener {
            store.pinned = !store.pinned
            save()
            updatePin()
        }

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

    private fun updateCrumb() {
        val t = title.text.toString().trim()
        crumb.text = if (t.isEmpty()) getString(R.string.app_name) else "${getString(R.string.app_name)}  ·  $t"
    }

    private fun updatePin() {
        pin.setText(if (store.pinned) R.string.pinned else R.string.unpinned)
        pin.setTextColor(getColor(if (store.pinned) R.color.text else R.color.text_secondary))
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
