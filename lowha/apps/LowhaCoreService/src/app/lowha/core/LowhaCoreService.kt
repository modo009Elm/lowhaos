/*
 * Copyright (C) 2026 The LowhaOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lowha.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * LowhaCoreService -- the system service that owns LowhaOS policy.
 *
 * Runs as the system UID (see android:sharedUserId in the manifest) and is
 * platform-signed, which is what allows it to hold SUSPEND_APPS and to read
 * usage statistics.
 */
class LowhaCoreService : Service() {

    private lateinit var usageMonitor: UsageMonitor
    private lateinit var blockingDispatcher: BlockingDispatcher
    private lateinit var commitmentTimer: CommitmentTimer

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "LowhaCoreService"

        /** Sent by SetupWizard when the user commits to their limit during onboarding. */
        const val ACTION_COMMIT = "app.lowha.core.action.COMMIT"
        const val EXTRA_LIMIT_MINUTES = "limit_minutes"
    }

    /**
     * Run [block] with the system identity.
     *
     * A binder transaction executes on the SERVICE's thread but with the
     * CALLER's UID. Without clearing it, UsageStatsManager and Settings writes
     * are evaluated against an ordinary app's permissions and throw
     * SecurityException -- even though this service itself is privileged.
     */
    private inline fun <T> asSystem(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun onCreate() {
        super.onCreate()
        usageMonitor = UsageMonitor(this)
        blockingDispatcher = BlockingDispatcher(this)
        commitmentTimer = CommitmentTimer(this)

        usageMonitor.checkDailyReset()
        usageMonitor.scheduleMidnightReset()

        // Suspension outlives our process, so the first thing we do on every
        // start is make the framework's state agree with ours again.
        blockingDispatcher.reconcileSuspension()

        startPolling()
        Log.i(TAG, "LowhaCoreService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_COMMIT) {
            val minutes = intent.getIntExtra(EXTRA_LIMIT_MINUTES, UsageMonitor.HARD_MAXIMUM_MINUTES)
            asSystem {
                val clamped = minutes.coerceIn(0, UsageMonitor.HARD_MAXIMUM_MINUTES)
                Settings.System.putInt(contentResolver, UsageMonitor.SETTING_TIME_LIMIT, clamped)
                Settings.System.putInt(contentResolver, UsageMonitor.SETTING_ONBOARDING_DONE, 1)
                commitmentTimer.startCommitment()
                blockingDispatcher.reconcileSuspension()
                Log.i(TAG, "Onboarding committed at $clamped minutes")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The noticing loop.
     *
     * Enforcement is done by suspension, not here. This only exists to spot
     * the moment usage crosses the budget so the suspension state can be
     * updated; hence the long interval.
     */
    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    usageMonitor.checkDailyReset()
                    blockingDispatcher.applySuspension(usageMonitor.isLimitReached())
                } catch (e: Exception) {
                    Log.e(TAG, "poll failed", e)
                }
                handler.postDelayed(this, BlockingDispatcher.POLL_MS)
            }
        }, BlockingDispatcher.POLL_MS)
    }

    private val binder = object : ILowhaCoreService.Stub() {

        // ---- commitment ----

        override fun isCommitmentActive(): Boolean = asSystem {
            commitmentTimer.isCommitmentActive()
        }

        override fun getRemainingCommitmentMs(): Long = asSystem {
            commitmentTimer.getRemainingCommitmentMs()
        }

        override fun isSettingsWindowOpen(): Boolean = asSystem {
            commitmentTimer.isSettingsWindowOpen()
        }

        override fun getSettingsWindowRemainingMs(): Long = asSystem {
            commitmentTimer.getSettingsWindowRemainingMs()
        }

        // ---- daily budget ----

        override fun getTimeLimitMinutes(): Int = asSystem {
            usageMonitor.getTimeLimitMinutes()
        }

        /**
         * Tightening is always allowed; loosening needs the settings window.
         *
         * Note that the write happens HERE and only here. An earlier version
         * delegated to CommitmentTimer.applySettingsChange() in both branches,
         * which re-applied its own gate and returned Unit -- so a tightening
         * change was silently dropped and the slider snapped back. Gate once.
         *
         * Tightening also deliberately does NOT restart the commitment clock.
         * Choosing to use the phone less should never extend a lockout.
         */
        override fun setTimeLimitMinutes(minutes: Int): Boolean = asSystem {
            val clamped = minutes.coerceIn(0, UsageMonitor.HARD_MAXIMUM_MINUTES)
            val current = usageMonitor.getTimeLimitMinutes()

            if (clamped > current && !commitmentTimer.isSettingsWindowOpen()) {
                Log.i(TAG, "Refused raise $current -> $clamped: settings window closed")
                return@asSystem false
            }

            Settings.System.putInt(contentResolver, UsageMonitor.SETTING_TIME_LIMIT, clamped)
            if (clamped > current) {
                // Loosening restarts the commitment: the user must live with a
                // larger budget for the full period before loosening again.
                commitmentTimer.startCommitment()
            }
            blockingDispatcher.reconcileSuspension()
            Log.i(TAG, "Time limit $current -> $clamped")
            true
        }

        override fun getRemainingTimeMinutes(): Int = asSystem {
            usageMonitor.getRemainingMinutes()
        }

        // ---- usage ----

        override fun getTrackedPackages(): Array<String> = asSystem {
            usageMonitor.trackedPackages().toTypedArray()
        }

        override fun getUsageAppPackages(): Array<String> = asSystem {
            usageMonitor.getUsageMinutesPerApp().first
        }

        override fun getUsageAppMinutes(): IntArray = asSystem {
            usageMonitor.getUsageMinutesPerApp().second
        }

        // ---- app lists ----

        override fun getHazardousApps(): Array<String> =
            UsageMonitor.HAZARDOUS_APPS.toTypedArray()

        override fun getOptionalBlockedApps(): Array<String> = asSystem {
            usageMonitor.getOptInBlockedApps().toTypedArray()
        }

        /**
         * Protected packages are filtered here, in the service.
         *
         * The settings UI also hides them from the picker, but that is a
         * convenience. This is the guarantee: no caller, however privileged,
         * can put the dialer on a timer through this interface.
         */
        override fun setOptionalBlockedApps(packages: Array<String>?): Boolean = asSystem {
            val cleaned = (packages ?: emptyArray())
                .map { it.trim() }
                .filter { it.isNotEmpty() && it !in BlockingDispatcher.PROTECTED_PACKAGES }
                .distinct()
            Settings.System.putString(
                contentResolver,
                UsageMonitor.SETTING_OPTIONAL_BLOCKED,
                cleaned.joinToString(",")
            )
            blockingDispatcher.reconcileSuspension()
            Log.i(TAG, "Optional blocked apps set to ${cleaned.size} package(s)")
            true
        }

        override fun getProtectedPackages(): Array<String> =
            BlockingDispatcher.PROTECTED_PACKAGES.toTypedArray()

        // ---- misc ----

        override fun isAppCurrentlyBlocked(packageName: String?): Boolean = asSystem {
            packageName != null &&
                    blockingDispatcher.shouldBlockApp(packageName) != BlockReason.NOT_BLOCKED
        }

        override fun getBlocklistVersion(): String = asSystem {
            Settings.System.getString(contentResolver, "lowha_blocklist_version") ?: "unknown"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
