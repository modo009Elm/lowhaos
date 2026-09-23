/*
 * Copyright (C) 2026 The LowhaOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package app.lowha.core

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * CommitmentTimer -- makes a chosen limit binding for a period.
 *
 * The product idea is that a limit you can raise the moment it becomes
 * inconvenient is not a limit. So:
 *
 *   * Lowering the limit is always allowed, immediately.
 *   * Raising it is only allowed while the settings window is open, which
 *     happens once the commitment period has elapsed.
 *   * Raising it restarts the commitment.
 *
 * The asymmetry is deliberate: it is always easy to ask less of yourself
 * later, and deliberately hard to ask more.
 */
class CommitmentTimer(private val context: Context) {

    companion object {
        private const val TAG = "LowhaCommitment"

        /** How long a commitment binds for. */
        const val COMMITMENT_DURATION_MS = 7L * 24 * 60 * 60 * 1000

        /** How long the user may loosen settings once a commitment has elapsed. */
        const val SETTINGS_WINDOW_MS = 15L * 60 * 1000

        const val SETTING_COMMIT_START_WALL = "lowha_commit_start_wall"
        const val SETTING_COMMIT_START_MONO = "lowha_commit_start_mono"
    }

    private val contentResolver = context.contentResolver

    /** Begin (or restart) a commitment period, stamping both clocks. */
    fun startCommitment() {
        Settings.System.putLong(
            contentResolver, SETTING_COMMIT_START_WALL, System.currentTimeMillis())
        Settings.System.putLong(
            contentResolver, SETTING_COMMIT_START_MONO, SystemClock.elapsedRealtime())
        Log.i(TAG, "Commitment started")
    }

    /**
     * Elapsed time since the commitment began.
     *
     * Takes the SMALLER of the wall-clock and monotonic deltas. Winding the
     * clock forward inflates the wall delta; taking the minimum means that
     * buys nothing. A reboot resets monotonic time, which is why wall-clock is
     * still consulted rather than dropped.
     */
    private fun elapsedMs(): Long {
        val startWall = Settings.System.getLong(contentResolver, SETTING_COMMIT_START_WALL, 0L)
        if (startWall == 0L) return Long.MAX_VALUE   // never committed
        val startMono = Settings.System.getLong(contentResolver, SETTING_COMMIT_START_MONO, 0L)

        val wallDelta = System.currentTimeMillis() - startWall
        val monoDelta = SystemClock.elapsedRealtime() - startMono
        return if (monoDelta in 0..wallDelta) monoDelta else wallDelta
    }

    fun isCommitmentActive(): Boolean = elapsedMs() < COMMITMENT_DURATION_MS

    fun getRemainingCommitmentMs(): Long =
        (COMMITMENT_DURATION_MS - elapsedMs()).coerceAtLeast(0L)

    /** Open once the commitment has elapsed, and only briefly. */
    fun isSettingsWindowOpen(): Boolean {
        val elapsed = elapsedMs()
        if (elapsed == Long.MAX_VALUE) return true      // pre-onboarding
        return elapsed >= COMMITMENT_DURATION_MS &&
                elapsed < COMMITMENT_DURATION_MS + SETTINGS_WINDOW_MS
    }

    fun getSettingsWindowRemainingMs(): Long {
        if (!isSettingsWindowOpen()) return 0L
        val elapsed = elapsedMs()
        if (elapsed == Long.MAX_VALUE) return SETTINGS_WINDOW_MS
        return (COMMITMENT_DURATION_MS + SETTINGS_WINDOW_MS - elapsed).coerceAtLeast(0L)
    }
}
