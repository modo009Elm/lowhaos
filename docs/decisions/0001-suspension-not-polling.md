# 1. Enforce with package suspension, not polling

**Status:** accepted · **Date:** 2026-08-07

## Context

The first enforcement engine polled the foreground task every 1.5 seconds and,
on seeing a blocked app, launched a full-screen block activity over it.

Two defects followed from the design rather than the implementation:

1. **A poll interval is a window.** The blocked app was on screen and
   interactive for up to 1.5s on every launch. Long enough to read a feed.
2. **It only saw what it happened to observe.** The dispatcher de-duplicated on
   package name, so after one block `lastBlocked` stayed set. Returning to the
   app through Recents never produced the launcher-resume transition that would
   have cleared it, so re-entry was silently allowed. Anyone who pressed the
   Recents button found this within a day.

Shortening the interval trades battery for a smaller window without ever
closing it, and does nothing about the second defect.

## Decision

Use `PackageManager.setPackagesSuspended()` — the framework mechanism Android's
own Digital Wellbeing uses for app timers.

## Consequences

The framework refuses to launch a suspended package at all, so:

* There is **no window**. The app never renders.
* **Every entry point is covered at once** — launcher, Recents, notifications,
  widgets, deep links, `am start`. The Recents bypass disappears as a property
  of the mechanism rather than as a special case anyone had to remember.
* The launcher greys the icon, so the limit is visible *before* tapping.
* Polling drops from an enforcement mechanism to a noticing one. The interval
  moved 1.5s → 30s, which is a large battery win.

Costs, accepted knowingly:

* Requires `SUSPEND_APPS` (`signature|role|verifier`), so the service must be
  platform-signed and run as the system UID.
* Suspension is **persistent framework state that outlives our process**. If
  the service dies while apps are suspended, they stay suspended. Boot-time
  reconciliation is therefore mandatory, not cosmetic — see
  `BlockingDispatcher.reconcileSuspension()`.
* The dialog's neutral button must be `BUTTON_ACTION_MORE_DETAILS`.
  `BUTTON_ACTION_UNSUSPEND` exists and would hand the user a one-tap escape
  from their own commitment.
