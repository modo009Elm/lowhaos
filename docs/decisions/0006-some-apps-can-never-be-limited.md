# 6. Some apps can never be limited

**Status:** accepted · **Date:** 2026-08-07

## Context

The app picker originally offered every installed app, including the dialer and
the messaging app.

## Decision

A fixed `PROTECTED_PACKAGES` set — dialer, telephony, messaging, contacts,
emergency, and Settings — is excluded from suspension and from the daily
budget, and cannot be added to either.

## Consequences

A phone that cannot call, text or reach emergency services is not an ethical
product, it is a brick.

The exclusion is enforced **in the service**, not only in the UI. The settings
screen also hides these apps from the picker, but that is a convenience; the
service filters them again on write, so no caller — however privileged — can
put the dialer on a timer through the binder interface.

Settings is protected for a different reason: suspending it would remove the
user's route back to their own controls, including the one that lifts the
block.
