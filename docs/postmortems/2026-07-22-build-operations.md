# LowhaCoreService Build — Post-Mortem

> **Date:** 2026-07-21 to 2026-07-22  
> **Total build attempts:** 9  
> **Unique errors:** 10  
> **Final status:** Not yet complete — SELinux policy has 2 remaining type errors (`settings_app` lines 28-29)

---

## Error Summary

| # | Build attempt | Error | Root cause | Fix |
|---|---|---|---|---|
| 1 | Full OTA via `brunch` | `FileNotFoundError: 'repo'` — build script couldn't find the `repo` tool | The `repo` binary is at `/workspace/lineage/.repo/repo/repo` but the `nohup` shell doesn't inherit the PATH that includes it | Added `export PATH="/workspace/lineage/.repo/repo:$PATH"` to `build-core.sh` |
| 2 | Full OTA via `make -j8 bacon` | `Found blocked Android.mk file` — Soong refused to parse the module | The source tree had BOTH `Android.bp` and `Android.mk` files for LowhaCoreService. LineageOS build system denies `.mk` files when a `.bp` exists to prevent duplicate module definitions | Deleted `Android.mk` — only `Android.bp` is needed |
| 3 | Full OTA via `make -j8 bacon` | `'lineage_FP6' — Do you have the right repo manifest?` — lunch failed | Caused by `brunch` being called instead of `lunch` + `make`. The `brunch` shortcut resolves the device name differently in non-interactive shells | Switched to `lunch lineage_FP6-bp2a-userdebug && make -j8 bacon` in the build script |
| 4 | Full OTA (Soong lock) | `Tried to lock out/.lock, but timed out` | A previous build did not cleanly exit, leaving the `.lock` file on disk. Soong uses flock-based locking — any stale lock blocks new builds | `rm -f /workspace/lineage/out/.lock && killall -9 soong_ui` |
| 5 | Full OTA (SELinux `//`) | `vendor/sepolicy.cil.raw FAILED` — `syntax error at token '//'` | The agent wrote `//` comments in `lowha_core.te`. The Android SEPolicy parser (`checkpolicy`) uses the C preprocessor, which only accepts `#` comments. `//` is not valid C preprocessor syntax | `sed -i 's|^//|#|g' lowha_core.te` |
| 6 | Full OTA (SELinux `//` cached) | Same as #5 — build still failed on the same error | Soong cached the broken sepolicy intermediate files. Even though the source was fixed, the build used the cached broken copy | Cleared `out/soong/.intermediates/system/sepolicy/` and rebuilt |
| 7 | Full OTA (Kotlin compile) | `unresolved reference 'setActivityController'` — Kotlin couldn't find the method | `ActivityManager.setActivityController()` is annotated `@UnsupportedAppUsage` — it's hidden from the public SDK. Kotlin's compiler works against the public `android.jar`, so it can't resolve the symbol | Switched to `java.lang.reflect.Proxy` — loads `IActivityController` at runtime and invokes `setActivityController()` via `Method.invoke()` |
| 7 | Full OTA (Kotlin compile) | `'systemReady' overrides nothing` | The `IActivityController.Stub()` interface in the SDK doesn't declare a `systemReady()` method on this API level. The agent assumed the AIDL would generate one | Removed the explicit `systemReady` override — the reflection proxy handles all methods dynamically |
| 7 | Full OTA (Kotlin compile) | `argument type mismatch: actual type is 'Long', but 'Int' was expected` | `Settings.System.getLong()` returns Kotlin `Long` but the agent's code assigned it to `Int` variables. Unlike Java, Kotlin does NOT implicitly narrow numeric types | Added explicit `.toInt()` calls in `FocusScoreEngine.kt` |
| 8 | Full OTA (SELinux type) | `unknown type 'settings_prop'` at line 27 | The agent wrote `allow lowha_core settings_prop:property_service set` — the type `settings_prop` does not exist in AOSP's SELinux type database. The agent invented a type name that doesn't match any real Android SELinux type | Removed the line. A platform-signed system service accesses properties through `Settings.System`/`Settings.Global` APIs, not raw property service |
| 9 | Full OTA (SELinux type #2) | `unknown type 'settings_app'` at line 28 | The agent also wrote `allow lowha_core settings_app:dir search` and `settings_app:file { read open }` — same problem, different invented type. `settings_app` is not a valid SELinux type in AOSP | **NOT YET FIXED** — needs removal (lines 28-29 in `lowha_core.te`) |

---

## Root Cause Analysis

### Why did this take 9 builds?

**No pre-flight validation.** Every bug was discovered via the pod's full OTA build cycle (4-45 minutes each). None of these errors would have been caught by a linter or IDE because:

- The SELinux errors require Android's `checkpolicy` binary (only on the pod)
- The Kotlin errors require the full `android.jar` framework (only on the pod)
- The Soong/Android.mk block requires the LineageOS build system

However, **4 of the 10 errors could have been caught by reading the source files before building:**

| Error | Could have been caught by |
|---|---|
| `//` comments in SELinux | Reading `lowha_core.te` — every line starts with `//` |
| `settings_prop` type | Searching AOSP SELinux type database for `settings_prop` |
| `settings_app` type | Same — neither type exists in AOSP |
| AIDL path mismatch | Comparing AIDL `package` declaration against file path |

### The structural problem

The agent's source code (`lowhaos/LowhaCoreService/`) was written on the Mac's Claude Design agent and never validated against Android's actual build environment before being pushed to the pod. The translation from "this Kotlin code should work" to "this Kotlin code compiles inside the LineageOS tree" had a gap.

---

## State at Pause (2026-07-22 02:00 UTC)

### Fixed and verified
- [x] AIDL file at correct path: `src/app/lowha/core/ILowhaCoreService.aidl`
- [x] `Android.mk` deleted
- [x] `Android.bp` has `platform_apis: true`
- [x] `//` comments in SELinux converted to `#`
- [x] `settings_prop` line removed from SELinux
- [x] `BlockingDispatcher.kt` uses reflection proxy for `IActivityController`
- [x] `FocusScoreEngine.kt` Long→Int types fixed
- [x] `LowhaCoreService.kt` has foreground notification added
- [x] `MidnightReceiver.kt` created (midnight reset alarm)
- [x] Build script has `repo` in PATH
- [x] Separate `device-lowha-core.mk` and file structure

### NOT YET FIXED
- [ ] **Remove lines 28-29** from `lowha_core.te`: `allow lowha_core settings_app:dir search` and `allow lowha_core settings_app:file { read open }`. The type `settings_app` is invented and doesn't exist in AOSP SELinux.
- [ ] **Clear sepolicy intermediates** after the fix: `rm -rf out/soong/.intermediates/system/sepolicy/`

### Files that need no further changes
- `LowhaCoreService.kt` ✅
- `UsageMonitor.kt` ✅
- `CommitmentTimer.kt` ✅
- `BlockScreenActivity.kt` ✅
- `BootReceiver.kt` ✅
- `MidnightReceiver.kt` ✅
- `LowhaListSyncService.kt` ✅
- `ILowhaCoreService.aidl` ✅
- `Android.bp` ✅
- `AndroidManifest.xml` ✅
- `device-lowha-core.mk` ✅

---

## Resume Procedure

When the pod is restarted and the next build is launched, these are the EXACT steps needed:

```bash
# 1. Connect to pod, clear stale lock
rm -f /workspace/lineage/out/.lock
killall -9 soong_ui ninja ckati 2>/dev/null

# 2. Fix remaining SELinux issue (lines 28-29)
sed -i '/settings_app/d' /workspace/lineage/vendor/lowhaos/framework/sepolicy/lowha_core.te

# 3. Clear cached sepolicy intermediates
rm -rf /workspace/lineage/out/soong/.intermediates/system/sepolicy/

# 4. Launch build
nohup /workspace/lineage/build-core.sh > /workspace/lineage/fullbuild-0722.log 2>&1 &
```

Expected result: Full OTA zip within 40-60 minutes, containing:
- All 7 onboarding screens (from the earlier frozen build)
- LowhaCoreService in `/system/priv-app/`
- BlockingInterceptor (IActivityController via reflection proxy)
- Midnight reset alarm
- Foreground notification for Android 14+ compliance
