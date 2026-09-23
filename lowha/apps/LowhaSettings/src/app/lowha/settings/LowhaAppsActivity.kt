/*
 * Copyright (C) 2026 The LowhaOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package app.lowha.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.GridView
import android.widget.ListView
import app.lowha.core.ILowhaCoreService

/**
 * "Daily time limit apps" -- shows what the budget applies to, and lets the
 * user add more.
 *
 * Apps can be ADDED but the built-in set cannot be removed. Removal would make
 * the product's central promise meaningless, so the UI simply does not offer
 * it rather than offering it and refusing.
 */
class LowhaAppsActivity : Activity() {

    private var service: ILowhaCoreService? = null
    private lateinit var grid: GridView

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
        setContentView(R.layout.activity_lowha_apps)
        grid = findViewById(R.id.app_grid)

        findViewById<android.view.View>(R.id.add_app_button).setOnClickListener {
            showPicker()
        }

        bindService(
            Intent("app.lowha.core.ILowhaCoreService").apply {
                setPackage("app.lowha.core")
            },
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun refresh() {
        val svc = service ?: return
        val tracked = svc.trackedPackages.toList()
        grid.adapter = AppIconAdapter(this, renderable(tracked))
    }

    /**
     * Keep only packages we can actually draw.
     *
     * A tracked app may not be installed on this device. Asking PackageManager
     * for its icon then throws, and the grid renders a gap that looks like a
     * bug. Filter to what resolves.
     */
    private fun renderable(packages: List<String>): List<String> {
        val pm = packageManager
        return packages.filter { pkg ->
            try {
                pm.getApplicationIcon(pkg); true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * Offer installed, launchable apps that are not already tracked.
     *
     * Protected packages are excluded here so the user is never offered the
     * dialer. The service filters them again on write -- this is the
     * courtesy, that is the guarantee.
     */
    private fun showPicker() {
        val svc = service ?: return
        val pm = packageManager
        val tracked = svc.trackedPackages.toSet()
        val protectedPkgs = svc.protectedPackages.toSet()

        val candidates = pm.getInstalledApplications(0)
            .filter { info ->
                info.packageName !in tracked &&
                        info.packageName !in protectedPkgs &&
                        pm.getLaunchIntentForPackage(info.packageName) != null &&
                        (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val labels = candidates.map { pm.getApplicationLabel(it).toString() }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.picker_title)
            .setAdapter(
                ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
            ) { _, which ->
                val chosen = candidates[which].packageName
                val updated = (svc.optionalBlockedApps.toList() + chosen).distinct()
                svc.setOptionalBlockedApps(updated.toTypedArray())
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        if (service != null) unbindService(connection)
        super.onDestroy()
    }
}
