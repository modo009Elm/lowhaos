// Copyright (C) 2026 The LowhaOS Project
// SPDX-License-Identifier: Apache-2.0

package app.lowha.core;

/**
 * Binder interface to the LowhaOS core service.
 *
 * The service runs as the system UID. Every implementation method therefore
 * clears the calling identity before touching UsageStats or Settings -- see
 * LowhaCoreService.asSystem().
 */
interface ILowhaCoreService {

    // ---- commitment ----

    /** Is the user inside a commitment period, during which the limit cannot be raised? */
    boolean isCommitmentActive();

    /** Milliseconds left in the current commitment period; 0 if none. */
    long getRemainingCommitmentMs();

    /** Is the short window during which settings may be loosened currently open? */
    boolean isSettingsWindowOpen();

    /** Milliseconds left in the settings window; 0 if closed. */
    long getSettingsWindowRemainingMs();

    // ---- daily budget ----

    /** Today's budget, in minutes. */
    int getTimeLimitMinutes();

    /**
     * Set today's budget.
     *
     * Lowering is always permitted. Raising is permitted only while the
     * settings window is open.
     *
     * @return true if the change was applied, false if it was refused.
     */
    boolean setTimeLimitMinutes(int minutes);

    /** Minutes of budget left today. */
    int getRemainingTimeMinutes();

    // ---- usage ----

    /** Packages currently subject to the daily budget. */
    String[] getTrackedPackages();

    /** Packages with usage today, sorted by descending usage. Parallel to getUsageAppMinutes(). */
    String[] getUsageAppPackages();

    /** Minutes used today, sorted descending. Parallel to getUsageAppPackages(). */
    int[] getUsageAppMinutes();

    // ---- app lists ----

    /** The fixed, non-removable tracked set. */
    String[] getHazardousApps();

    /** Apps the user has added to the daily budget. */
    String[] getOptionalBlockedApps();

    /**
     * Replace the user-added app list.
     *
     * Protected packages are filtered out service-side regardless of what the
     * caller passes.
     *
     * @return true if applied.
     */
    boolean setOptionalBlockedApps(in String[] packages);

    /** Packages that can never be limited (dialer, messaging, emergency, settings). */
    String[] getProtectedPackages();

    // ---- misc ----

    /** Is this package blocked right now? */
    boolean isAppCurrentlyBlocked(String packageName);

    /** Version string of the active content blocklist. */
    String getBlocklistVersion();
}
