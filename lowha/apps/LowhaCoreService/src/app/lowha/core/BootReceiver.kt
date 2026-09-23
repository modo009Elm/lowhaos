/*
 * Copyright (C) 2026 The LowhaOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package app.lowha.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the core service at boot and handles the midnight rollover.
 *
 * Starting at boot is not optional. App suspension is persistent framework
 * state: if the device rebooted while apps were suspended and nothing
 * reconciled afterwards, they would stay suspended indefinitely.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LowhaBootReceiver"
        const val ACTION_MIDNIGHT_RESET = "app.lowha.core.action.MIDNIGHT_RESET"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                context.startService(Intent(context, LowhaCoreService::class.java))
                Log.i(TAG, "Boot completed; core service started")
            }
            ACTION_MIDNIGHT_RESET -> {
                UsageMonitor(context).checkDailyReset()
                // Waking the service re-reconciles suspension, which is what
                // actually lifts a block at midnight.
                context.startService(Intent(context, LowhaCoreService::class.java))
                Log.i(TAG, "Midnight reset fired")
            }
        }
    }
}
