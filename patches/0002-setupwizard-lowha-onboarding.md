# SetupWizard: add the LowhaOS limit-setting step

**Target:** `packages/apps/SetupWizard`
**Upstream:** LineageOS — files remain under their original licence.

## Change

Add `LowhaTimeSettingActivity` to the first-run flow and register it in
`res/raw/lineage_wizard_script.xml`, so the user chooses their daily budget
during setup rather than discovering the feature later.

LineageOS drives its setup flow entirely from that XML script; adding an
activity without registering it there means it never appears.

## The part that was missing

An earlier revision rendered the slider, let the user choose a value, and never
persisted it. The service still read `null` and applied the default. The screen
looked finished and did nothing.

The fix is to send the chosen value to the core service on commit:

```java
private void commitLimit(int minutes) {
    Intent intent = new Intent("app.lowha.core.action.COMMIT");
    intent.setPackage("app.lowha.core");
    intent.putExtra("limit_minutes", minutes);
    startService(intent);
}
```

`LowhaCoreService` handles `ACTION_COMMIT` by writing the limit, setting
`lowha_onboarding_complete`, and starting the commitment period.

## Lesson

A UI that *collects* a value is not the same as a UI that *saves* one, and
nothing short of reading the value back on the device distinguishes them. This
was found by checking `settings get system lowha_time_limit_minutes` after
onboarding and seeing `null`.
