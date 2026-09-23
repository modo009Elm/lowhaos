# 2. Detect clock tampering rather than prohibit it

**Status:** accepted · **Date:** 2026-08-07

## Context

The daily counter is keyed on the device's calendar day. Winding the clock
forward past midnight therefore hands the user a free reset.

The obvious fix is to force `Settings.Global.AUTO_TIME` on and block changes.

## Decision

Do **not** force `AUTO_TIME`. Detect the jump instead.

At each reset we store wall-clock time and `SystemClock.elapsedRealtime()`
together. Monotonic time counts real elapsed time including sleep and **cannot
be set by the user**. If wall-clock has advanced far more than monotonic has,
time did not pass — the clock was moved — and the reset is refused.

Slack is four hours: generous enough to absorb any genuine timezone change,
DST shift or NTP correction, while still catching the much larger jump needed
to reach tomorrow.

## Consequences

Users keep control of their own clock. Taking that away is a real imposition on
an honest user — travel, a phone with a drifting RTC, simply preferring manual
time — and it is disproportionate to the bypass it closes.

The trade-off is that detection is not prevention: a user who moves the clock
forward gets no reset, but also does not get caught doing anything else. That
is the right balance for a tool whose value depends on the user's own buy-in.

A related subtlety: `CommitmentTimer` takes the **smaller** of the wall and
monotonic deltas, so winding the clock forward cannot shorten a commitment
either. A reboot resets monotonic time, which is why wall-clock is still
consulted rather than dropped.
