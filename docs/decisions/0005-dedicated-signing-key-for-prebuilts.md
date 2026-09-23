# 5. Sign third-party prebuilts with a dedicated key

**Status:** accepted · **Date:** 2026-08-03

## Context

Aurora Store needs `INSTALL_PACKAGES`, a `signature|privileged` permission, so
the APK must be signed with a key the platform trusts. The path of least
resistance is `certificate: "platform"`.

## Decision

Generate a dedicated `lowha_preload` key and sign Aurora with that instead.

## Consequences

The platform key is the most powerful credential on the device: anything signed
with it can share the system UID. A third-party binary we do not build should
not carry it. A dedicated key grants exactly the privilege Aurora needs and
nothing more, and can be rotated without re-signing the platform.

Two mechanical details that each cost a build cycle:

* The key must be declared as an `android_app_certificate` **module**, and
  referenced with a **leading colon** — `certificate: ":lowha_preload"`.
  Without the colon Soong treats the value as a path and fails to resolve it
  (`build/soong/java/app.go`, `SrcIsModule`).
* Soong verifies every shared library an APK references. Declare the optional
  ones via `optional_uses_libs:` rather than reaching for the escape hatch that
  disables the check.
