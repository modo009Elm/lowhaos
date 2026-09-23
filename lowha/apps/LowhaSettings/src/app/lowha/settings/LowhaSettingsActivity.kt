/*
 * Copyright (C) 2026 The LowhaOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package app.lowha.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import app.lowha.core.ILowhaCoreService

/**
 * The LowhaOS Protection screen, reached from the Settings homepage.
 *
 * All state lives in the core service; this screen is a view onto it and does
 * not persist anything itself.
 */
class LowhaSettingsActivity : Activity() {

    private var service: ILowhaCoreService? = null

    // Cached once in onCreate.
    //
    // These were originally looked up with findViewById() inside the SeekBar
    // listener, which returned null during layout and crashed the moment the
    // slider was touched. Resolve once, guard on null, never look up in a
    // callback.
    private lateinit var limitValue: TextView
    private lateinit var limitSlider: SeekBar
    private lateinit var saveButton: Button
    private lateinit var windowStatus: TextView

    /** The limit currently shown by the slider, before saving. */
    private var pendingMinutes = 0

    /** The limit the service currently holds. */
    private var savedMinutes = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ILowhaCoreService.Stub.asInterface(binder)
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lowha_settings)

        limitValue = findViewById(R.id.limit_value)
        limitSlider = findViewById(R.id.limit_slider)
        saveButton = findViewById(R.id.save_button)
        windowStatus = findViewById(R.id.window_status)

        // One-minute granularity. Coarser steps meant a user who wanted
        // "about twenty minutes" was pushed to a number they had not chosen.
        limitSlider.max = 90

        limitSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                pendingMinutes = progress
                limitValue.text = limitLabel(progress)
                saveButton.isEnabled = progress != savedMinutes
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        saveButton.setOnClickListener { confirmAndSave() }

        findViewById<View>(R.id.apps_row).setOnClickListener {
            startActivity(Intent(this, LowhaAppsActivity::class.java))
        }

        bindService(
            Intent("app.lowha.core.ILowhaCoreService").apply {
                setPackage("app.lowha.core")
            },
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    /** "Off" reads better than "0 minutes" and states what actually happens. */
    private fun limitLabel(minutes: Int): String = when (minutes) {
        0 -> getString(R.string.limit_off)
        1 -> getString(R.string.limit_one_minute)
        else -> getString(R.string.limit_minutes, minutes)
    }

    private fun refresh() {
        val svc = service ?: return
        savedMinutes = svc.timeLimitMinutes
        pendingMinutes = savedMinutes

        limitSlider.progress = savedMinutes
        limitValue.text = limitLabel(savedMinutes)
        saveButton.isEnabled = false

        // At zero the slider has nothing left to express -- the apps are simply
        // blocked. Showing a dead control invites people to drag it and
        // wonder why nothing happens.
        limitSlider.visibility = if (savedMinutes == 0) View.GONE else View.VISIBLE

        val open = svc.isSettingsWindowOpen
        windowStatus.text = if (open) {
            getString(R.string.window_open)
        } else {
            getString(R.string.window_closed)
        }
    }

    /**
     * Confirm before saving.
     *
     * Two different warnings, because the two directions have very different
     * consequences and a single generic prompt taught the user nothing.
     */
    private fun confirmAndSave() {
        val svc = service ?: return
        val lowering = pendingMinutes < savedMinutes

        val message = if (lowering) {
            // Lowering below today's usage locks the apps immediately, for the
            // rest of the day. People genuinely did not expect this.
            getString(R.string.confirm_lower_message, limitLabel(pendingMinutes))
        } else {
            getString(R.string.confirm_raise_message, limitLabel(pendingMinutes))
        }

        AlertDialog.Builder(this)
            .setTitle(
                if (lowering) R.string.confirm_lower_title else R.string.confirm_raise_title
            )
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.confirm_save) { _, _ ->
                if (svc.setTimeLimitMinutes(pendingMinutes)) {
                    refresh()
                } else {
                    // The service refused: the window is shut. Say so rather
                    // than letting the slider silently spring back.
                    AlertDialog.Builder(this)
                        .setTitle(R.string.refused_title)
                        .setMessage(R.string.refused_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    refresh()
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (service != null) refresh()
    }

    override fun onDestroy() {
        if (service != null) unbindService(connection)
        super.onDestroy()
    }
}
