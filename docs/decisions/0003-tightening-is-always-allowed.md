# 3. Tightening is always allowed; loosening is gated

**Status:** accepted · **Date:** 2026-08-07

## Context

A limit you can raise the moment it becomes inconvenient is not a limit. But a
limit you cannot *lower* punishes exactly the behaviour the product exists to
encourage.

## Decision

Asymmetric gating:

| Direction | Allowed | Effect on the commitment clock |
|---|---|---|
| Lower the limit | always, immediately | **does not restart it** |
| Raise the limit | only while the settings window is open | restarts the 7-day period |

## Consequences

It is always easy to ask less of yourself, and deliberately hard to ask more.

Lowering below what you have already used today blocks the apps **immediately,
until midnight**. This is intended, and it is why the confirmation dialog says
so in plain language rather than a generic "are you sure?".

### The bug this decision produced

The first implementation routed both directions through
`CommitmentTimer.applySettingsChange()`, which applied its own gate and
returned `Unit`. A tightening change was therefore gated twice, silently
dropped, and the slider sprang back to its old value with no error.

Gate **once**, at the boundary. `LowhaCoreService.setTimeLimitMinutes()` now
owns the decision and performs the write directly.
