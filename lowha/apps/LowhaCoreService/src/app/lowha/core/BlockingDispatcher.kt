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

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SuspendDialogInfo
import android.provider.Settings
import android.util.Log

/** Why an app is being blocked. */
enum class BlockReason {
    NOT_BLOCKED,
    TIME_LIMIT_REACHED,
    PERMANENTLY_BLOCKED
}

/**
 * BlockingDispatcher -- turns "the limit is reached" into an app the user
 * genuinely cannot open.
 *
 * Enforcement mechanism
 * ---------------------
 * This class used to poll the foreground task and throw a full-screen block
 * activity over anything it did not like. That approach had two defects that
 * no amount of tuning could fix:
 *
 *   1. A poll interval is a window. At 1.5s the blocked app was visible and
 *      interactive for up to a second and a half on every launch.
 *   2. It only saw launches it happened to observe. Entering an app from
 *      Recents did not always produce the transition the poller keyed on, so
 *      re-entry was silently allowed -- a trivially discoverable bypass.
 *
 * We now use [PackageManager.setPackagesSuspended], the same framework
 * mechanism Digital Wellbeing uses for app timers. Suspension is enforced
 * inside the framework, so there is no window, and every entry point is
 * covered at once. The polling loop remains only to NOTICE the limit being
 * crossed; it no longer enforces anything.
 */
class BlockingDispatcher(private val context: Context) {

    private val usageMonitor = UsageMonitor(context)
    private val packageManager: PackageManager = context.packageManager

    /** Last state we pushed to PackageManager. null = not yet reconciled. */
    private var suspendedState: Boolean? = null

    companion object {
        private const val TAG = "LowhaBlocker"

        /**
         * How often we re-check usage.
         *
         * This is a NOTICING interval, not an enforcement one: suspension does
         * the enforcing. It was 1500ms when the poll had to catch launches;
         * 30s is ample to notice a budget being crossed and costs far less
         * battery.
         */
        const val POLL_MS = 30_000L

        /**
         * Packages that must NEVER be suspended or added to a time limit.
         *
         * A phone that cannot call, text or reach emergency services is not an
         * ethical product, it is a brick. Enforced here in the service so it
         * holds no matter which caller asks -- the UI filter is a convenience,
         * this is the guarantee.
         */
        val PROTECTED_PACKAGES = setOf(
            "com.android.dialer", "com.android.phone", "com.qti.phone",
            "com.android.messaging", "com.android.mms.service",
            "com.android.contacts", "com.android.providers.contacts",
            "com.android.emergency",
            "com.android.settings", "com.android.providers.settings",
            "org.lineageos.lineagesettings", "org.lineageos.settings.device",
            "app.lowha.core", "app.lowha.settings"
        )
    }

    // ------------------------------------------------------------------
    // Suspension: the real enforcement mechanism.
    //
    // PackageManager.setPackagesSuspended() is what Digital Wellbeing uses for
    // app timers. The framework refuses to launch a suspended package AT ALL
    // and shows our dialog instead, so:
    //   * there is no visible-app window to exploit (polling leaked ~1.5s)
    //   * every entry point is covered - launcher, Recents, notifications,
    //     widgets, deep links, `am start`. The Recents bypass disappears as a
    //     consequence of the mechanism, not as a special case.
    //   * the launcher greys the icon, so the limit is visible before tapping
    //   * no polling loop is needed to enforce, only to notice the crossing
    //
    // Requires SUSPEND_APPS (signature|role|verifier). LowhaCoreService is
    // platform-signed and runs as the system UID, so it qualifies.
    // ------------------------------------------------------------------

    /** Apps subject to suspension: time-limited plus permanently blocked. */
    private fun suspendableApps(): Array<String> {
        val optional = (Settings.System.getString(
            context.contentResolver, UsageMonitor.SETTING_OPTIONAL_BLOCKED) ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return (UsageMonitor.HAZARDOUS_APPS + optional)
            .filter { it !in PROTECTED_PACKAGES }
            .distinct()
            .toTypedArray()
    }

    /** Branded dialog shown by the framework when a suspended app is tapped. */
    private fun lowhaDialog(): SuspendDialogInfo =
        SuspendDialogInfo.Builder()
            .setIcon(R.drawable.lowha_logo)
            .setTitle(R.string.suspend_title)
            .setMessage(context.getString(R.string.suspend_message))
            // MORE_DETAILS routes into LowhaSettings.
            // Deliberately NOT BUTTON_ACTION_UNSUSPEND: that would give the
            // user a one-tap escape from their own commitment.
            .setNeutralButtonText(R.string.suspend_button)
            .setNeutralButtonAction(SuspendDialogInfo.BUTTON_ACTION_MORE_DETAILS)
            .build()

    /**
     * Push the desired suspension state to PackageManager.
     *
     * Only acts on a CHANGE. Suspension is persistent state held by the
     * framework, so re-applying it every check would be pure waste.
     *
     * @param force ignore the cached state (used by boot reconciliation, where
     *              our cache is meaningless because the process is new)
     */
    fun applySuspension(shouldSuspend: Boolean, force: Boolean = false) {
        if (!force && suspendedState == shouldSuspend) return
        val apps = suspendableApps()
        if (apps.isEmpty()) return
        try {
            packageManager.setPackagesSuspended(apps, shouldSuspend, null, null, lowhaDialog())
            suspendedState = shouldSuspend
            Log.i(TAG, "setPackagesSuspended($shouldSuspend) applied to ${apps.size} package(s)")
        } catch (e: Exception) {
            // Loud: if this fails there is NO enforcement, and the user would
            // have no way to tell from the UI.
            Log.e(TAG, "setPackagesSuspended FAILED - apps are NOT blocked", e)
        }
    }

    /**
     * Recompute the correct state and apply it. Called on boot and whenever the
     * limit or app list changes.
     *
     * Boot reconciliation is mandatory, not cosmetic: suspension outlives our
     * process. If the service died while apps were suspended they would stay
     * suspended forever with no path back.
     */
    fun reconcileSuspension() {
        val shouldSuspend = usageMonitor.isLimitReached()
        applySuspension(shouldSuspend, force = true)
        Log.i(TAG, "reconciled suspension: shouldSuspend=$shouldSuspend")
    }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    /**
     * Why this package would be blocked right now.
     *
     * Kept for the settings UI and for logging. Enforcement no longer consults
     * it -- the framework does that via suspension.
     */
    fun shouldBlockApp(packageName: String): BlockReason {
        if (packageName in PROTECTED_PACKAGES) return BlockReason.NOT_BLOCKED
        if (packageName in usageMonitor.getPermanentlyBlockedApps()) {
            return BlockReason.PERMANENTLY_BLOCKED
        }
        if (isTimeLimitedApp(packageName) && usageMonitor.isLimitReached()) {
            return BlockReason.TIME_LIMIT_REACHED
        }
        return BlockReason.NOT_BLOCKED
    }

    private fun isTimeLimitedApp(packageName: String): Boolean =
        packageName in UsageMonitor.HAZARDOUS_APPS ||
                packageName in usageMonitor.getOptInBlockedApps()
}
