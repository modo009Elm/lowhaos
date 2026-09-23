# LowhaOS — Implementation Reference

> **Version:** 2026-08-06
> **Author:** Fable (Claude Code)
> **Audience:** the next engineer/agent picking this up
> **Base:** LineageOS 23.2 (Android 16, API 36), Fairphone Gen 6 (FP6)
> **Build host:** the build host, `/workspace/lineage` — **authoritative**, the laptop tree is stale
>
> This documents what exists, why it is built that way, what was rejected and why,
> and — importantly — **what is still broken**. Read §9 before trusting anything.

---

## 1. Architecture at a glance

```
                     ┌──────────────────────────────────────┐
   FIRST BOOT        │  LineageSetupWizard                  │
                     │  (org.lineageos.setupwizard)         │
                     │  system_ext/priv-app                 │
                     │                                      │
                     │  7 Lowha screens interleaved with    │
                     │  the Lineage flow via                │
                     │  res/raw/lineage_wizard_script.xml   │
                     │                                      │
                     │  LowhaTimeSettingActivity.commitLimit│
                     └──────────────┬───────────────────────┘
                                    │ Intent ACTION_COMMIT
                                    │ + EXTRA_LIMIT_MINUTES
                                    ▼
   ┌────────────────────────────────────────────────────────────────┐
   │  LowhaCoreService          app.lowha.core                      │
   │  /system/priv-app          sharedUserId=android.uid.system     │
   │                            android:process="system"            │
   │                            → RUNS INSIDE system_server         │
   │                                                                │
   │  ├── UsageMonitor          counts usage, owns the limit        │
   │  ├── BlockingDispatcher    decides + enforces blocking         │
   │  ├── CommitmentTimer       7-day lock, 1-hour change window    │
   │  ├── MidnightReceiver      daily counter reset                 │
   │  ├── BootReceiver          starts the service on boot          │
   │  └── ILowhaCoreService     AIDL surface for system apps        │
   └───────┬──────────────────────────────────┬─────────────────────┘
           │ AIDL (signature-guarded)          │ startActivity
           ▼                                   ▼
   ┌───────────────────────────┐      ┌────────────────────────────┐
   │ LowhaSettings             │      │ BlockScreenActivity        │
   │ app.lowha.settings        │      │ full-screen, singleInstance│
   │ system_ext/priv-app       │      │ noHistory, excludeFromRec. │
   │                           │      └────────────────────────────┘
   │ ├── LowhaSettingsActivity │
   │ └── LowhaAppsActivity     │
   └───────────────────────────┘

   Also shipped:  AuroraStore (com.aurora.store, system_ext/priv-app)
                  LowhaBrowser (com.lowha.browser, system_ext/app)
                  LowhaPreview (org.lineageos.lowhapreview, system/app)
```

**Single source of truth:** `LowhaCoreService` owns all persisted state. Nothing
else writes LowhaOS settings. UI reads and requests through AIDL; the service
validates and decides. This is deliberate — see §4.1.

---

## 2. Where everything lives

| Path | Lines | Purpose |
|---|---|---|
| `packages/apps/LowhaCoreService/src/UsageMonitor.kt` | 272 | Usage counting, limit, daily reset |
| `packages/apps/LowhaCoreService/src/BlockingDispatcher.kt` | 171 | Block decision + enforcement loop |
| `packages/apps/LowhaCoreService/src/CommitmentTimer.kt` | 189 | 7-day commitment, settings window |
| `packages/apps/LowhaCoreService/src/LowhaCoreService.kt` | 256 | Service entry, AIDL binder impl |
| `packages/apps/LowhaCoreService/src/BlockScreenActivity.kt` | 71 | The RESTRICTED screen |
| `packages/apps/LowhaCoreService/src/MidnightReceiver.kt` | 24 | Fired by the daily alarm |
| `.../src/app/lowha/core/ILowhaCoreService.aidl` | 80 | AIDL contract (path mirrors package — required) |
| `packages/apps/LowhaSettings/src/.../LowhaSettingsActivity.kt` | 265 | Main panel |
| `packages/apps/LowhaSettings/src/.../LowhaAppsActivity.kt` | 222 | "Daily time limit apps" |
| `vendor/lowhaos/device-lowha-core.mk` | 37 | PRODUCT_PACKAGES, privapp copy, sepolicy dir |
| `vendor/lowhaos/framework/privapp-permissions-lowha.xml` | 32 | Privileged permission allowlist |
| `vendor/lowhaos/framework/sepolicy/lowha_core.te` | 17 | SELinux (deliberately minimal — §4.6) |
| `vendor/lowhaos/prebuilts/{LowhaBrowser,AuroraStore}/Android.bp` | 47/48 | Prebuilt imports |
| `vendor/lowhaos/security/` | — | `lowha_preload` signing key + `android_app_certificate` |
| `packages/apps/SetupWizard/` | — | Onboarding (7 Lowha activities + wizard script) |

Persisted keys (all via `Settings.System` / `Settings.Global`):

```
system.lowha_time_limit_minutes      user's daily cap, 0..90
system.lowha_daily_usage_minutes     cached counter (largely vestigial, §9.4)
system.lowha_daily_reset_day         Calendar.DAY_OF_YEAR of last reset
system.lowha_optional_blocked_apps   CSV, user-added time-limited apps
system.lowha_permanently_blocked_apps CSV, never-allowed apps
global.lowha_commitment_expiry       epoch ms when the 7-day lock ends
global.lowha_settings_window_end     epoch ms when the 1-hour window closes
global.lowha_onboarding_complete     1 once ACTION_COMMIT has run
```

---

## 3. The daily cumulative time limit — end to end

### 3.1 How a hazardous app is "detected"

**It isn't, and that is intentional.** There is no install listener, no
`PACKAGE_ADDED` receiver, no registration step. `UsageMonitor.HAZARDOUS_APPS`
(lines 23–37) is a hardcoded list of 13 package names.

```kotlin
val HAZARDOUS_APPS: List<String> = listOf(
    "com.instagram.android", "com.zhiliaoapp.musically", "com.snapchat.android",
    "com.facebook.katana",   "com.google.android.youtube","com.twitter.android",
    "com.kuaishou.nebula",   "com.reddit.frontpage",     "com.threads.app",
    "com.tumblr",            "com.pinterest",            "com.twitch.android.app",
    "com.linkedin.android")
```

**Why no install detection.** Enforcement happens at *launch*, not install. An
app that is not installed simply contributes 0 minutes. Install it later and it
counts from its first launch with no registration. A `PACKAGE_ADDED` approach
would add a moving part and a race window between install and registration
during which the app would be unmonitored. Rejected as strictly worse.

`allTimedApps` (lines 95–96) is the effective set:

```kotlin
private val allTimedApps: Set<String>
    get() = HAZARDOUS_APPS.toSet() + optionalBlockedApps
```

so apps the user adds in `LowhaAppsActivity` share the **same** cumulative pool.

### 3.2 What counts the time

**Android does. We only read it.** `UsageMonitor.getCumulativeUsageMinutes()`:

```kotlin
val stats = usageStatsManager.queryUsageStats(INTERVAL_DAILY, midnightToday, now)
for (usageStats in stats)
    if (usageStats.packageName in allTimedApps)
        totalMinutes += usageStats.totalTimeInForeground / 60000
```

**Why not our own timer.** A foreground-service stopwatch would need to survive
Doze, process death and reboots, and would double-count against the platform's
own bookkeeping. `UsageStatsManager` is maintained by `system_server`, survives
reboots and is the same source Digital Wellbeing uses. Rejected our own timer as
more code and less reliable.

> ⚠️ This function has a **real accuracy defect**. See §9.1.

### 3.3 What runs when the limit is reached

Nothing is "called" — it is **polled**, every 1500 ms, in
`BlockingDispatcher.startPollingFallback()`:

```kotlin
val events = usm.queryEvents(now - POLL_WINDOW_MS, now)
while (events.hasNextEvent()) {
    events.getNextEvent(ev)
    if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latestPkg = ev.packageName
}
if (latestPkg != null && latestPkg !in SELF_PACKAGES) {
    val reason = shouldBlockApp(latestPkg)
    if (reason != NOT_BLOCKED && latestPkg != lastBlocked) {
        lastBlocked = latestPkg
        showBlockScreen(context, latestPkg, reason)
    }
}
```

Decision logic (`shouldBlockApp`, lines 140–144):

```kotlin
if (isPermanentlyBlocked(packageName)) return PERMANENTLY_BLOCKED
if (isTimeLimitedApp(packageName) && usageMonitor.isLimitReached()) return TIME_LIMIT_REACHED
return NOT_BLOCKED
```

`lastBlocked` de-dupes so the block screen is not relaunched on every tick.

### 3.4 Why polling, and why that is a compromise

The **intended** mechanism is `ActivityManager.setActivityController()` +
`IActivityController`, which intercepts a launch *before the activity draws* —
the app never appears. `BlockingDispatcher.registerInterceptor()` still attempts
this first, via `java.lang.reflect.Proxy` because the interface is
`@UnsupportedAppUsage` and absent from the public SDK.

**On the FP6 (API 36) it throws `NoSuchMethodException`.** Verified on-device:

```
W LowhaBlocker: setActivityController failed
  (android.app.ActivityManager.setActivityController [interface android.app.IActivityController])
  - starting polling fallback
I LowhaBlocker: Polling fallback active (1500ms interval)
W LowhaCore:    Enforcement: interceptor unavailable, polling fallback in use
```

This **contradicts** `lowhaos-app-blocking-plan-v2.md`, whose peer review
concluded the API was still viable on API 36. It is not, on this device.

**Consequence: a blocked app is visible for up to 1.5 s before the block screen
covers it.** That is the single biggest functional gap in the product.

Alternatives considered:

| Option | Verdict |
|---|---|
| `IActivityController` | Preferred, unavailable on API 36. Still attempted first. |
| Polling `queryEvents` (current) | Works everywhere, ≤1.5 s leak. Shipped. |
| `AccessibilityService` | **The likely correct answer** — instant, no leak, how commercial screen-time apps do it. Needs a user-granted toggle, which is awkward for an OS-level guarantee but is grantable by a Device Owner. Not yet built. |
| Patch `frameworks/base` | Instant and unbypassable, but a large framework fork to carry across LineageOS merges. |

**A previous version of this code logged "using polling" in the catch block with
no polling implemented.** Any reflection failure silently disabled all blocking
while the notification claimed protection was active. That is why
`isInterceptorActive()` exists and why the service logs which path is live —
`adb logcat -d | grep "Enforcement:"` answers "is this phone actually protected".

### 3.5 The block screen

`BlockScreenActivity` + `res/layout/activity_block_screen.xml`. Launched with
`FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP`, carrying
`blocked_package`, `block_reason`, `remaining_minutes`.

Manifest flags — each deliberate:

```xml
android:excludeFromRecents="true"   <!-- cannot be resurrected from Recents -->
android:launchMode="singleInstance" <!-- never stacks -->
android:noHistory="true"            <!-- leaves no back-stack entry -->
android:taskAffinity=""             <!-- does not join the blocked app's task -->
```

Copy differs by reason (time limit vs permanent block). "Got it" returns to the
launcher via `ACTION_MAIN`/`CATEGORY_HOME`.

### 3.6 Seeing time remaining

`LowhaSettingsActivity.refresh()` over AIDL:

```kotlin
val limit     = svc.timeLimitMinutes
val remaining = svc.remainingTimeMinutes
val used      = (limit - remaining).coerceAtLeast(0)
// "34 of 90 min used" + progress bar + per-app breakdown
```

Per-app data arrives as **two parallel arrays** (`getTrackedPackages()` /
`getUsageMinutesPerApp()`), both sorted identically. The UI iterates
`minOf(pkgs.size, mins.size)` and logs a mismatch rather than trusting either —
an app can enter the stats between the two binder calls.

**Why parallel arrays, not a Map.** AIDL `Map` marshalling is untyped
(`Map<Object,Object>`), requires manual casting and is the AOSP anti-pattern.
Parallel arrays are the idiomatic choice.

### 3.7 Lowering the limit below current usage

**Locks the user out immediately, for the rest of the day. This is intended and
confirmed by the product owner.**

It works because nothing caches the limit — `userMaxMinutes` is a *getter*
(lines 60–67) re-read on every call:

```kotlin
private val userMaxMinutes: Int
    get() = Settings.System.getInt(cr, SETTING_TIME_LIMIT, HARD_MAXIMUM_MINUTES)
                    .coerceIn(0, HARD_MAXIMUM_MINUTES)
```

Used 50, set 40 → the next poll tick (≤1.5 s) evaluates `50 >= 40` → blocked.

### 3.8 The asymmetric change rule

**Product decision (2026-08-06): tightening is always allowed; loosening is
gated on the commitment window.**

```kotlin
override fun setTimeLimitMinutes(minutes: Int): Boolean = asSystem {
    val clamped = minutes.coerceIn(0, HARD_MAXIMUM_MINUTES)
    val current = /* read from Settings.System */
    if (clamped > current && !commitmentTimer.isSettingsWindowOpen()) {
        Log.w(TAG, "increase to ${clamped}min rejected - commitment active")
        return@asSystem false
    }
    ...
}
```

Same rule for the app list: **adding** apps is always allowed, **removing**
needs the window, and hazardous apps can never be removed at all.

**Why not the literal R-COM-02** ("no Settings UI can bypass the commitment")?
Because a blanket lock also blocks the user from becoming *stricter*, which
serves no one and makes the panel look broken. The commitment exists to stop a
tempted user weakening a promise — not to stop them keeping it harder. The UI
reflects this: during a commitment the slider stays **enabled but capped at the
current limit**, rather than disabled outright.

---

## 4. Design decisions and rejected alternatives

### 4.1 LowhaCoreService owns all state

`sharedUserId="android.uid.system"` + `android:process="system"` + `coreApp="true"`
means the service's components load **into `system_server`**.

**Consequences that matter:**
- It inherits `system_server`'s SELinux domain. It does **not** get its own —
  see §4.6.
- A crash in this code can take down `system_server` (i.e. bootloop). Treat
  every change here as boot-critical.
- It has system UID, so it bypasses the privapp permission allowlist. This is
  why it survived the partition bug that bricked LowhaSettings (§8.1).

**Why not a normal priv-app?** It needs `PACKAGE_USAGE_STATS`, must survive
Doze, and must be alive before the user reaches the launcher. Running in
`system_server` guarantees all three.

### 4.2 Binder identity — `asSystem { }`

Every AIDL method body is wrapped:

```kotlin
private inline fun <T> asSystem(block: () -> T): T {
    val token = Binder.clearCallingIdentity()
    try { return block() } finally { Binder.restoreCallingIdentity(token) }
}
```

**Why.** Binder transactions execute with the **caller's** UID. Even though the
service lives in `system_server`, a call from LowhaSettings (uid 10147) was
evaluated against *LowhaSettings'* permissions, and `UsageStatsService` refused:

```
SecurityException: app.lowha.core from uid 10147 not allowed to perform GET_USAGE_STATS
```

Safe because the service is guarded by the signature-level
`app.lowha.permission.LOWHA_CORE`, so only platform-signed Lowha apps can bind,
and every mutating call re-validates server-side.

**Rejected alternative:** give LowhaSettings `PACKAGE_USAGE_STATS`. That widens
its privileges for no reason and is what caused the bootloop in §8.1.

### 4.3 Onboarding lives in LineageSetupWizard, not a separate app

The 7 Lowha screens are `SubBaseActivity` subclasses **inside**
`packages/apps/SetupWizard`, chained by `res/raw/lineage_wizard_script.xml`:

```
… datetime → lowha_parental → restore → … → navigation
   → lowha_lifetime → lowha_time → lowha_blocked → lowha_browser → lowha_finish
```

**Why not a standalone setup app.** The wizard already owns first-boot: it holds
`category.HOME` + `DEVICE_INITIALIZATION_WIZARD` and calls `finishSetupWizard()`
which sets `DEVICE_PROVISIONED` and `USER_SETUP_COMPLETE`. Replacing that
machinery would mean reimplementing provisioning. Interleaving is far less code
and gets Wi-Fi/language/lock-screen for free.

`packages/apps/LowhaPreview` is a **separate standalone copy** of the same
screens — a design-review/demo build, launchable post-setup. Do not confuse the
two: **the real onboarding is in SetupWizard.**

### 4.4 Onboarding persists via the service, not directly

`LowhaTimeSettingActivity.commitLimit()` sends
`ACTION_COMMIT` + `EXTRA_LIMIT_MINUTES` to `LowhaCoreService`, which clamps,
writes the limit, starts the commitment, resets the counter, starts tracking and
sets `lowha_onboarding_complete`.

**Why not write `Settings.System` from the wizard?** It would duplicate the
clamping and commitment logic in a second place, and the two would drift. One
writer, one validator.

**Historical note:** before 2026-08-06 the commit button only called
`onNextPressed()`. The slider value was **never saved** — every device ran the
90-minute default regardless of what the user chose. The commitment screen was
decoration. This was the single most important bug fixed in this round.

### 4.5 Prebuilt APKs: two different signing strategies, on purpose

| | LowhaBrowser | AuroraStore |
|---|---|---|
| Signing | `presigned: true` (keeps its own) | re-signed with `lowha_preload` |
| Repack | `preprocessed: true` (byte-for-byte) | Soong repacks |
| Privileged | **No** | **Yes** |
| Partition | `system_ext/app` | `system_ext/priv-app` |

**Browser:** requests only normal/runtime permissions (`aapt2 dump permissions`
confirms zero `signature|privileged`), so priv-app buys nothing. It also *cannot*
be a priv-app as built: priv-apps need uncompressed dex, but `preprocessed:true`
(required to preserve its v2 signature) forbids repacking. Dropping `privileged`
resolves both.

**Aurora:** genuinely needs `INSTALL_PACKAGES` (`signature|privileged`), so it
must be a priv-app with uncompressed dex — which requires repacking — which
requires re-signing. Signed with a **dedicated** `lowha_preload` key rather than
the platform key, so it qualifies only via the *privileged* route and holds
exactly the two whitelisted permissions. Signing a third-party app with the
platform key would implicitly grant it every signature-level permission in the
OS.

Aurora is the **preload** variant (`AuroraStore-preload-4.8.4.apk`); the standard
build only requests `REQUEST_INSTALL_PACKAGES` and would prompt on every install.

### 4.6 SELinux is deliberately minimal

`lowha_core.te` is 17 lines and declares **one type**:

```
type lowha_blocklist_file, file_type, data_file_type, core_data_file_type;
allow system_server lowha_blocklist_file:dir  create_dir_perms;
allow system_server lowha_blocklist_file:file create_file_perms;
```

**Why so small.** The previous version declared a `lowha_core` domain via
`init_daemon_domain()`. That macro is for **native daemons that init execs from
a labelled binary**. LowhaCoreService is an APK loaded into `system_server`; it
inherits that domain and can never have its own. The entire domain block was
inert, and two of its rules referenced invented types (`settings_app`,
`settings_prop`) that fail `checkpolicy`.

**Rule: never invent a type.** Verify first:
`grep -rw "<type>" /workspace/lineage/system/sepolicy/`

---

## 5. Build and install

### 5.1 Fast iteration (app changes) — minutes

```bash
# on pod
m LowhaCoreService LowhaSettings -j8
# laptop
scp …/out/target/product/FP6/{system_ext/priv-app/LowhaSettings/LowhaSettings.apk,…} .
adb root && adb remount
adb push LowhaSettings.apk /system_ext/priv-app/LowhaSettings/
adb shell stop && adb shell start
```

Requires **Developer options → Rooted debugging**. Uses overlayfs — the change
is live but **not in the signed partition**. Always cut a real OTA before a
milestone.

### 5.2 Full OTA — ~90 minutes

```bash
/workspace/rebuild-chain.sh    # preflight → modules → verify → bacon
```

**Why so slow, since Soong is incremental?** Measured: `/workspace` is a network
mount and small-file IO is **~100× slower than local** (0.031 s vs 3.223 s for
200 writes). AOSP's access pattern is millions of small stats. On top of that,
`bacon` re-hashes and re-signs whole partitions regardless of change size
(~51 min). Module compile is genuinely incremental; the surrounding phases are
not. `ccache` is **not installed** — worth adding, though it only helps C/C++.

### 5.3 Build operations — rules paid for in lost hours

Every rule here exists because breaking it cost real time on 2026-08-06/07.
None of them are theoretical.

---

#### RULE 1 — Never `pkill` ninja or the kernel build. It corrupts `KERNEL_OBJ`.

**Cost: ~3 hours.**

`pkill -x ninja` kills the build mid-write. Soong's own locking cleans up after
itself; a signal does not. A partially emitted kernel-module artifact was left
behind and every subsequent build failed at the final image link:

```
FAILED: out/target/product/FP6/obj/KERNEL_OBJ/arch/arm64/boot/Image
llvm-objcopy: error: '../sm7635-modules/qcom/opensource/touch-drivers/qts.ko.btf':
              No such file or directory
```

**The build system cannot self-heal from this.** Re-running produces the same
error forever, because the missing file is never regenerated — its parent target
is considered up to date. The only fix is:

```bash
rm -rf out/target/product/FP6/obj/KERNEL_OBJ     # 4.2 GB
```

…which forces a full kernel recompile from zero (~60–90 min on this pod).

**Do instead:**
- Let the build finish. It is almost always faster than the recovery.
- If you truly must stop it: `kill <soong_ui pid>` and let soong tear down its
  own children. Never signal ninja directly.
- After any abnormal stop, `rm -f out/.lock` before the next build.

---

#### RULE 2 — `pkill -f "<pattern>"` matches your own command line.

**Cost: two dropped SSH sessions and one stuck monitor that ran for ~3 hours.**

```bash
pkill -f "soong_ui|ninja"      # ← kills the SSH session running this command
```

`pkill -f` / `pgrep -f` match the **full command line**, and the command line of
the shell you are typing into contains that pattern. Two separate failures came
from this:

- an SSH session killed itself mid-command
- a monitor loop `until ! pgrep -f "soong_ui|ninja|ckati"` matched **itself**,
  so it never exited and reported a finished build as still running

**Do instead:** `pkill -x soong_ui` (exact name match), or `kill <pid>` after
resolving the pid explicitly.

---

#### RULE 3 — Do not edit source while a build is running.

**Cost: ~90 min on a build that never reached `bacon`.**

Soong re-globs when it detects changed files. Editing `LowhaSettings` sources
mid-build made it re-analyse repeatedly; after 97 minutes it was still in the
*module* phase (60 targets) and had never started `bacon`. Worse, the resulting
tree is a mix of files read before and after the edit — the output is not
trustworthy even if it completes.

**Do instead:** finish the edit, *then* start the build. If you must change
something, stop the build (Rule 1), edit, restart.

---

#### RULE 4 — Do not run destructive self-tests against a live build tree.

**Cost: one aborted build.**

Validating that the stale-artifact gate worked meant copying `LowhaSettings.apk`
back to `/system/priv-app` to recreate the bootloop condition. A build was
running concurrently, its stale-artifact check saw the test copy, and aborted:

```
ABORT: LowhaSettings still on /system - stale artifact, would bootloop again
```

The gate worked perfectly. The problem was testing it against the tree the gate
was watching.

**Do instead:** self-test against a copy, or when nothing is building.

---

#### RULE 5 — `m <module>` still drags in the kernel.

Building a single APK routinely triggers `Building Kernel Config` and kernel
compilation. This is expected, not a fault — the module's install target depends
on the image, which depends on the kernel. It is why a "quick" module build is
~25–35 min rather than ~2.

**Do instead:** for app-only iteration, use the APK push path (§5.1). It skips
this entirely.

---

#### RULE 6 — `ninja may be stuck` is almost always a false alarm.

It appears every 5 minutes during kernel compilation because kernel `make`
produces no ninja-level output for long stretches. It has fired on **every**
build in this project and been wrong every time.

**Do instead:** confirm before reacting —

```bash
ps aux --sort=-%cpu | head -5                                   # is anything burning CPU?
find out/target/product/FP6/obj/KERNEL_OBJ -newermt "-2 minutes" | wc -l   # recent writes?
```

Non-zero writes or a busy compiler process means it is working.

---

#### RULE 7 — Verify the artifact, not the exit code.

A green build proves compilation. It does not prove the change shipped. Three
times this session an artifact check caught something an exit code would not
have:

- `lowha_blocklist_file` present in the compiled `vendor_sepolicy.cil` → proved
  `BOARD_SEPOLICY_DIRS` was actually picked up
- commit strings present in the shipped `LineageSetupWizard.apk` dex → proved
  `commitLimit()` reached the binary
- a `PRODUCT_PACKAGES` edit silently no-op'd (heredoc escaping) while the script
  printed "added" — caught only by reading the file back

**Do instead:** after any scripted edit, read the file back. After any build,
grep the built artifact for the thing you added.

---

#### RULE 8 — Expected build timings on this pod

| Operation | Time | Notes |
|---|---|---|
| APK push (§5.1) | **~90 s** | root + remount + push + framework restart |
| `m <module>` | 25–35 min | includes kernel dependency |
| `m bacon` | ~50 min | re-hashes and re-signs whole partitions |
| Full chain | ~90 min | modules + bacon |
| Full chain after `KERNEL_OBJ` wipe | **~3 h** | avoid — see Rule 1 |

The dominant cost is **not** compilation. `/workspace` is a network mount
(`mfs#eu-nl-1.the build host.net`, FUSE) measured at **~100× slower than local** for
small-file IO — 0.031 s vs 3.223 s for 200 small writes. AOSP's access pattern
is millions of small stats, so Soong analysis alone re-walks the tree every
time. `ccache` is not installed; adding it would help C/C++ only.

If the pod ever has local NVMe scratch, moving `out/` there while keeping
sources on `/workspace` would cut this substantially — `out/` is where the
write-heavy churn happens.

---

### 5.4 Pre-flight validator — run before every build

`/workspace/lowha-preflight.py` catches runtime-only contracts no build gate sees:

| Check | Catches |
|---|---|
| A | privapp allowlist on a different partition than the app → **bootloop** |
| B | allowlist entry for a package that never installs |
| C | custom permission referenced but never `<permission>`-declared |
| D | intent action used in code but absent from any intent-filter |
| E | package-valued build property naming a package not in the image |

Exits non-zero on failure and is wired as a hard gate in `rebuild-chain.sh`.
Self-tested: recreating the bootloop condition makes it fail correctly.

**It knows about the system-UID exemption** (§4.1) so it does not false-positive
on LowhaCoreService, and it greps the wider tree before failing on a permission,
because platform permissions like `lineageos.permission.*` are declared in
`lineage-sdk`.

---

## 6. Device facts worth knowing

- **Virtual A/B.** There are not two full copies of the OS — one `super` plus
  snapshots. Once the new slot boots, a **merge writes the snapshot into `super`,
  consuming the old slot**. `fastboot --set-active` is **not a rollback** after
  the new slot has booted. Treat a flashed OTA as one-way; keep the previous
  **OTA zip** on the laptop as the real fallback.
- **Recovery is on its own partition** outside `super` and survives everything.
  `fastboot reboot recovery` + sideload is the reliable recovery path.
- **The TCP port changes on every pod restart**, and `/root/.ssh/authorized_keys`
  is container-local so it is wiped. A copy lives at `/workspace/authorized_keys`.
- `adb sideload` success looks like failure: `adb: failed to read command: Success`
  with exit 0 is **normal**. `Total xfer: 1.00x` means a clean transfer.

---

## 7. Requirements status

| Req | Status |
|---|---|
| R-ONB-02 limit set during onboarding | ✅ verified on device (`null` → set) |
| R-SMT cumulative limit across hazardous apps | ⚠️ works, counting inaccurate (§9.1) |
| R-COM-01 7-day commitment | ✅ expiry set exactly 7 days out |
| R-COM-02 no bypass | ⚠️ refined to asymmetric (§3.8); **clock bypass open** (§9.2) |
| R-BRW-01/02/03 browser exclusivity | ✅ only browser, role pinned |
| R-STR-02/03 Aurora as installer | ✅ installed, holds INSTALL_PACKAGES |
| R-STR-03 *only* app with INSTALL_PACKAGES | ❌ Seedvault also holds it |
| R-STR-04 unknown sources blocked | ❌ needs Device Owner, silently unmet |
| R-ATM-03/08 ADB restrictions | ❌ `userdebug` build |
| R-SMT-08 Settings panel | ✅ top of Settings, live data |

---

## 8. Bugs fixed this round (and what they teach)

### 8.1 Bootloop — privapp allowlist partition mismatch
`LowhaSettings` in `/system/priv-app` with the allowlist in
`/system_ext/etc/permissions`. Android requires **same partition**;
PackageManager aborted `system_server` at `systemReady()`. ~50 crashes.
Fixed twice over: removed the unneeded permission **and** moved the app to
`system_ext`. Now gated by preflight check A.
*Lesson: package names were verified with `aapt2`; partitions were not.*

### 8.2 Trebuchet crash — flag-guarded init, unguarded consumer
`RecentsView.java` only built the Recents DI graph
`if (enableRefactorTaskThumbnail())` (default off), but `TaskView.kt:566-567`
reads `RecentsDependencies.get()` in **unguarded property initialisers**.
Every swipe-up crashed the launcher. Fixed by hoisting **both**
`maybeInitialize()` and `createRecentsViewScope()` out of the flag check —
the latter contains `scope.linkTo(getScope(DEFAULT_SCOPE_ID))`, which is what
makes `DispatcherProvider` resolvable.
*Upstream bug, marked `LOWHAOS FIX` in-place. Watch for it on LineageOS merges.*
*Lesson: the first fix was incomplete and moved the failure rather than removing
it. "The error changed" is not "the error is gone".*

### 8.3 Runtime-only defects found by reading, not building
- browser role pinned to `app.lowha.browser`, a package that never existed
- privapp entry for the same non-existent package
- `app.lowha.permission.LOWHA_CORE` referenced but never declared → every bind failed
- bind action missing from the service intent-filter → resolved to nothing
- slider crashed: `findViewById` inside the SeekBar listener returned null

None were caught by a green build. All are now covered by preflight C/D/E.

---

## 9. KNOWN DEFECTS — read before trusting the limit

### 9.1 Sub-minute usage is discarded  🔴 accuracy
`UsageMonitor.kt:164` — `foregroundTimeMs / 60000` is **integer division**.
59 seconds of use counts as **0 minutes**. Across 13 apps that is up to ~12
minutes/day silently uncounted. `queryUsageStats(INTERVAL_DAILY)` also
aggregates lazily, so the currently-open app under-reports.

The file comment claiming "±30 seconds (R-NFR-02)" is **false**.

**Fix:** the `queryEvents()` migration promised in `lowhaos-app-blocking-plan-v2.md`
§2e and never applied. Replace the aggregate query with paired
`ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` events and sum the millisecond intervals.
~60 lines, one function. Edge cases: an app resumed but not yet paused (add
`now - resumeTime`), and events straddling midnight. **Highest-value fix in the
codebase.**

### 9.2 Clock change bypasses the limit  🔴 security
There is **no** handling of `ACTION_TIME_CHANGED` or `ACTION_TIMEZONE_CHANGED`
anywhere. `getCumulativeUsageMinutes()` queries from *local* midnight and
`checkDailyReset()` compares `Calendar.DAY_OF_YEAR`.

**Exploit:** Settings → Date & time → disable automatic → set tomorrow's date.
The usage window moves with the clock, the query returns ~0, and the limit is
gone. About four taps, no technical skill.

**Fixes, strongest first:**
1. Device Owner + `DISALLOW_CONFIG_DATE_TIME` (also fixes §9.3) — removes the toggle
2. Force `AUTO_TIME=1` and lock it (also Device Owner)
3. **No-privilege option:** cross-check the wall clock against
   `SystemClock.elapsedRealtime()`. A day boundary only counts if the monotonic
   clock advanced too. Manual changes do not move `elapsedRealtime`, so the jump
   is detectable. ~1 hour.

### 9.3 Blocking is degraded  🟠 core promise
Polling fallback, ≤1.5 s visible leak. See §3.4. `AccessibilityService` is the
likely correct answer.

### 9.4 Redundant reset bookkeeping  🟡 tidiness
`SETTING_DAILY_USAGE` is written by `getCumulativeUsageMinutes()` but the value
is re-derived from midnight on every call, so the stored counter is never the
source of truth. Two mechanisms, one redundant. Harmless but confusing.

### 9.5 Deferred security gaps  🟠
Recorded on 2026-08-03 as accepted for the showcase build:
Seedvault holds `INSTALL_PACKAGES` (restore-from-backup reinstalls blocked apps);
no Device Owner so R-STR-04 is silently unmet; `userdebug` build with ADB on;
browser is debug-signed. **All must be closed before a production image.**

---

## 10. Recommended next steps

1. **`queryEvents()` migration** (§9.1) — the limit is not trustworthy without it
2. **Clock-change defence** (§9.2) — the easiest real bypass on the device
3. **Device Owner provisioning** — unlocks §9.2, R-STR-04 and the Seedvault fix
4. **`AccessibilityService` interceptor** (§9.3) — removes the 1.5 s leak
5. Confirmation dialog when lowering below current usage (behaviour is correct,
   the surprise is not)
6. Cut a full OTA — everything since the last flash lives in an overlayfs

---

## 11. Honest statement of confidence

Verified on hardware: boots; onboarding persists the chosen limit; the 7-day
commitment starts; Aurora installs apps; Lowha Browser is the only browser;
Trebuchet no longer crashes; the Settings panel shows live data; the apps screen
renders and writes.

**Not verified end-to-end:** an actual block triggered by exhausting the limit
through real usage. The mechanism is proven in code and the block screen renders,
but nobody has yet watched the counter run to zero and the screen appear
organically. **Do that before demoing to anyone.**

Everything in §9 is a known, reproducible defect — not speculation.
