# Patches against upstream LineageOS

Each patch here modifies a file that belongs to LineageOS or AOSP. Those files
remain under their original licences; see [NOTICE](../NOTICE).

These are **descriptions of changes**, not `git apply`-able diffs. They record
the exact edit, the symptom that motivated it, and the reasoning — which is the
part that survives an upstream rebase. Line numbers do not.

| Patch | Target | Why |
|---|---|---|
| [`0001-launcher3-recents-scope-crash.md`](0001-launcher3-recents-scope-crash.md) | `packages/apps/Launcher3` | Fixes a crash that made Recents unusable |
| [`0002-setupwizard-lowha-onboarding.md`](0002-setupwizard-lowha-onboarding.md) | `packages/apps/SetupWizard` | Adds the limit-setting step to first-run setup |
