# Launcher3: initialise the recents scope regardless of the refactor flag

**Target:** `packages/apps/Launcher3/quickstep/src/com/android/quickstep/views/RecentsView.java`
**Upstream:** LineageOS / AOSP — file remains under its original licence.

## Symptom

"Trebuchet keeps stopping" on every attempt to open Recents.

## Cause

`RecentsView` performed two pieces of setup inside a block guarded by
`enableRefactorTaskThumbnail()`:

```java
if (enableRefactorTaskThumbnail()) {
    ...
    maybeInitialize(recentsDependencies);
    createRecentsViewScope(context);
}
```

Both are required unconditionally. With the flag off:

* skipping `maybeInitialize()` left a `lateinit` property unset →
  `UninitializedPropertyAccessException`
* skipping `createRecentsViewScope()` left the DI scope unlinked (it performs
  `scope.linkTo(getScope(DEFAULT_SCOPE_ID))`) →
  `IllegalStateException: Factory for DispatcherProvider not defined!`

The second fault only became visible once the first was fixed, which is why a
partial fix appeared to *change* the crash rather than remove it. Worth
remembering: a crash that moves is not necessarily a crash that is closer to
being solved.

## Change

Hoist **both** calls out of the flag guard, so they run in either
configuration. Mark the edit `LOWHAOS FIX` so it is greppable across rebases.

## Why it matters here

More than an ordinary crash. An unusable Recents screen masks whether app
suspension behaves correctly *in Recents* — precisely the entry point the
earlier polling-based enforcement failed to cover. Fixing the crash was a
prerequisite for testing the bypass fix at all.
