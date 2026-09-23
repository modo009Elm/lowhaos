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

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/**
 * UsageMonitor -- measures cumulative foreground time across the tracked apps
 * and decides whether today's limit has been reached.
 *
 * Counting model
 * --------------
 * We ask [UsageStatsManager] for INTERVAL_DAILY buckets and sum
 * `totalTimeInForeground` for every tracked package. The framework is the
 * source of truth; we deliberately keep no running total of our own, so a
 * crash or reboot cannot lose or double-count time.
 *
 * Reset model
 * -----------
 * The counter resets at local midnight. Because the daily bucket is keyed on
 * the device's own calendar day, moving the clock forward would otherwise hand
 * the user a free reset -- see [checkDailyReset] for how that is detected.
 */
class UsageMonitor(private val context: Context) {

    companion object {
        /**
         * The tracked set, fixed at compile time.
         *
         * This list is deliberately NOT user-editable. The product promise is
         * that these specific apps are governed by a daily budget; letting the
         * list be edited at runtime would make that promise meaningless. Users
         * may ADD apps (see SETTING_OPTIONAL_BLOCKED), never remove these.
         */
        val HAZARDOUS_APPS: List<String> = listOf(
            "com.instagram.android",           // Instagram
            "com.zhiliaoapp.musically",        // TikTok
            "com.snapchat.android",            // Snapchat
            "com.facebook.katana",             // Facebook
            "com.google.android.youtube",      // YouTube
            "com.twitter.android",             // Twitter / X
            "com.kuaishou.nebula",             // Kuaishou
            "com.reddit.frontpage",            // Reddit
            "com.threads.app",                 // Threads
            "com.tumblr",                      // Tumblr
            "com.pinterest",                   // Pinterest
            "com.twitch.android.app",          // Twitch
            "com.linkedin.android"             // LinkedIn
        )

        /** Ceiling for the daily budget. Compile-time constant, not configurable. */
        const val HARD_MAXIMUM_MINUTES = 90

        // ---- persisted state (Settings.System) ----
        const val SETTING_TIME_LIMIT = "lowha_time_limit_minutes"
        const val SETTING_OPTIONAL_BLOCKED = "lowha_optional_blocked_apps"
        const val SETTING_PERMANENTLY_BLOCKED = "lowha_permanently_blocked_apps"
        const val SETTING_LAST_RESET_DAY = "lowha_daily_reset_day"
        const val SETTING_ONBOARDING_DONE = "lowha_onboarding_complete"

        /**
         * Wall-clock and monotonic stamps taken at the same instant on the last
         * reset. Comparing how far each has advanced is what exposes a clock
         * that has been moved -- see [checkDailyReset].
         */
        const val SETTING_LAST_RESET_WALL = "lowha_last_reset_wall"
        const val SETTING_LAST_RESET_MONO = "lowha_last_reset_mono"

        /**
         * How far wall-clock time may run ahead of monotonic time before we
         * treat it as tampering rather than drift.
         *
         * Four hours is generous on purpose: it absorbs any real timezone
         * change, DST, and NTP correction, while still catching the "jump to
         * tomorrow for a free reset" move, which needs a much larger jump.
         */
        const val CLOCK_TAMPER_SLACK_MS = 4L * 60 * 60 * 1000

        private const val TAG = "LowhaUsageMonitor"
        private const val MIDNIGHT_REQUEST_CODE = 0x10_11
    }

    val contentResolver: ContentResolver = context.contentResolver

    private val usageStatsManager: UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    // ------------------------------------------------------------------
    // Limit
    // ------------------------------------------------------------------

    /** Today's budget in minutes, clamped into range. */
    fun getTimeLimitMinutes(): Int =
        Settings.System.getInt(contentResolver, SETTING_TIME_LIMIT, HARD_MAXIMUM_MINUTES)
            .coerceIn(0, HARD_MAXIMUM_MINUTES)

    /** Every package subject to the daily budget: the fixed set plus opt-ins. */
    fun trackedPackages(): Set<String> =
        (HAZARDOUS_APPS + getOptInBlockedApps()).toSet()

    // ------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------

    /**
     * Minutes used today across all tracked apps.
     *
     * Note the single division. Rounding per-app and then summing discards up
     * to 59 seconds for EVERY app, which across a dozen tracked apps silently
     * gifted the user several minutes a day. Sum milliseconds, divide once.
     */
    fun getUsedMinutes(): Int {
        val end = System.currentTimeMillis()
        val start = startOfToday()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, start, end
        ) ?: return 0

        val tracked = trackedPackages()
        var totalMs = 0L
        for (usageStats in stats) {
            if (usageStats.packageName in tracked) {
                totalMs += usageStats.totalTimeInForeground
            }
        }
        return (totalMs / 60_000L).toInt()
    }

    /**
     * Per-app usage for the settings screen, as parallel arrays sorted by
     * descending usage. Parallel arrays rather than a Map because this crosses
     * an AIDL boundary, where primitives are cheapest.
     */
    fun getUsageMinutesPerApp(): Pair<Array<String>, IntArray> {
        val end = System.currentTimeMillis()
        val start = startOfToday()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, start, end
        ) ?: return Pair(emptyArray(), IntArray(0))

        val tracked = trackedPackages()
        val byPackage = HashMap<String, Long>()
        for (usageStats in stats) {
            if (usageStats.packageName in tracked) {
                byPackage[usageStats.packageName] =
                    (byPackage[usageStats.packageName] ?: 0L) + usageStats.totalTimeInForeground
            }
        }
        val sorted = byPackage.entries.sortedByDescending { it.value }
        return Pair(
            sorted.map { it.key }.toTypedArray(),
            sorted.map { (it.value / 60_000L).toInt() }.toIntArray()
        )
    }

    fun getRemainingMinutes(): Int =
        (getTimeLimitMinutes() - getUsedMinutes()).coerceAtLeast(0)

    /** The single question enforcement asks. */
    fun isLimitReached(): Boolean = getUsedMinutes() >= getTimeLimitMinutes()

    // ------------------------------------------------------------------
    // Daily reset
    // ------------------------------------------------------------------

    /**
     * Reset the day if the calendar day really has changed.
     *
     * The tamper check: we stored wall-clock and monotonic time together at the
     * last reset. Monotonic time (`elapsedRealtime`) counts real elapsed time
     * including sleep and CANNOT be set by the user. If wall-clock has advanced
     * far more than monotonic has, the clock was moved rather than time having
     * passed, and we refuse the reset.
     *
     * Deliberately NOT solved by forcing AUTO_TIME: taking away the ability to
     * set your own clock is a bigger imposition on an honest user than this
     * bypass is worth. We detect instead of prohibit.
     */
    fun checkDailyReset() {
        val today = currentDayOfYear()
        val lastDay = Settings.System.getInt(contentResolver, SETTING_LAST_RESET_DAY, -1)
        if (today == lastDay) return

        val nowWall = System.currentTimeMillis()
        val nowMono = SystemClock.elapsedRealtime()
        val lastWall = Settings.System.getLong(contentResolver, SETTING_LAST_RESET_WALL, 0L)
        val lastMono = Settings.System.getLong(contentResolver, SETTING_LAST_RESET_MONO, 0L)

        if (lastWall > 0L && lastMono > 0L) {
            val wallDelta = nowWall - lastWall
            val monoDelta = nowMono - lastMono
            // A reboot resets elapsedRealtime, making monoDelta small and the
            // comparison meaningless, so only treat a forward wall jump as
            // suspicious when monotonic time has not gone backwards.
            if (monoDelta >= 0 && wallDelta - monoDelta > CLOCK_TAMPER_SLACK_MS) {
                Log.w(TAG, "Clock moved forward by ${wallDelta - monoDelta}ms more than " +
                        "monotonic time; refusing daily reset (suspected tampering)")
                return
            }
        }

        Settings.System.putInt(contentResolver, SETTING_LAST_RESET_DAY, today)
        Settings.System.putLong(contentResolver, SETTING_LAST_RESET_WALL, nowWall)
        Settings.System.putLong(contentResolver, SETTING_LAST_RESET_MONO, nowMono)
        Log.i(TAG, "Daily counter reset for day $today")
    }

    /**
     * Schedule the midnight rollover.
     *
     * Uses RTC_WAKEUP so the reset happens even if the device is asleep; a
     * counter that only resets when someone picks the phone up would leave the
     * user blocked into the next morning.
     */
    fun scheduleMidnightReset() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, BootReceiver::class.java).apply {
            action = BootReceiver.ACTION_MIDNIGHT_RESET
        }
        val pending = PendingIntent.getBroadcast(
            context, MIDNIGHT_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP, nextMidnight(), AlarmManager.INTERVAL_DAY, pending
        )
        Log.i(TAG, "Midnight reset scheduled")
    }

    // ------------------------------------------------------------------
    // User-editable app lists
    // ------------------------------------------------------------------

    /** Apps the user voluntarily added to the daily budget. */
    fun getOptInBlockedApps(): Set<String> = readCsv(SETTING_OPTIONAL_BLOCKED)

    /** Apps the user chose to block outright, with no daily allowance. */
    fun getPermanentlyBlockedApps(): Set<String> = readCsv(SETTING_PERMANENTLY_BLOCKED)

    private fun readCsv(key: String): Set<String> {
        val raw = Settings.System.getString(contentResolver, key) ?: return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    // ------------------------------------------------------------------
    // Calendar helpers
    // ------------------------------------------------------------------

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nextMidnight(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun currentDayOfYear(): Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
}
