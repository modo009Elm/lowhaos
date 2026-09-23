# 4. Remove stock packages with Soong `overrides:`

**Status:** accepted · **Date:** 2026-09-23

## Context

LowhaOS ships its own browser, because URL filtering and SafeSearch enforcement
live there. Any second browser on the device defeats that entirely — and a
preinstalled one requires no skill whatsoever to find.

`PRODUCT_DEL_PACKAGES += Jelly Browser2` was used to remove the stock browsers.

## The problem

**`PRODUCT_DEL_PACKAGES` does not exist in this build system.** Grepping all of
`build/make/` for it returns nothing.

It was a silent no-op for two consecutive releases. Jelly shipped in both while
the build stayed green, and a preflight check that validated this same
non-existent variable reported PASS throughout.

Two things disguised it:

* `Browser2` genuinely was absent — but because *Jelly itself* declares
  `overrides: ["Browser2", "CarHTMLViewer"]`. The mechanism was working; it
  just was not ours.
* A first diagnosis blamed a stale artefact in `out/`. Deleting the directory
  appeared to fix it, and the next build recreated it. The file timestamp
  proved it was being rebuilt, not left behind.

## Decision

Declare `overrides:` on the replacement module:

```
android_app_import {
    name: "LowhaBrowser",
    overrides: ["Jelly", "Browser2"],
```

This is the mechanism Jelly uses, and the one the build system actually reads.

## Consequences

* Never use `PRODUCT_DEL_PACKAGES`. It is not a real variable here.
* A check must validate the mechanism that **actually governs** the outcome.
  Preflight check F now parses real `overrides:` blocks and fails if any listed
  package is still present in the build output. Validating the wrong mechanism
  is worse than having no check, because it manufactures confidence.
* Prefer verifying an artefact's **timestamp and dimensions** over its mere
  presence. "The file is not there" and "the file is not there *yet*" look
  identical until you check when it was written.
