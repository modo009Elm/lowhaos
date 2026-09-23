# LowhaOS

**Open-Source OS for android phones which limits/blocks social media and blocks all adult and gambling content/apps**

LowhaOS is a [LineageOS](https://lineageos.org/) fork made for the Fairphone 6 (can be used in any android device). You
choose a daily budget for social media during setup. When it runs out, those
apps stop opening — not with a reminder you can swipe away, but enforced by the
operating system itself.

The limit is binding for seven days. You can always lower it. Raising it has to
wait.

Parental controls option to simply block social media for under-16s

---

## Why an OS?

If you have an iphone and want easy app solution, search for Lowha, it is available here:
https://apps.apple.com/gb/app/lowha/id6773096638

Every screen-time app has the same hole: it runs at the same privilege as the
apps it polices. Uninstall it, revoke its permissions, force-stop it, or deny
it accessibility access, and it stops working. It asks nicely, and it can be
told no.

LowhaOS moves enforcement below the apps. The core service is platform-signed,
runs as the system UID, and uses the same framework mechanism Android's own
Digital Wellbeing uses. A blocked app does not launch — from the launcher, from
Recents, from a notification, from a deep link, or from `am start`.

## How the enforcement actually works

```
   UsageStatsManager  ──►  UsageMonitor  ──►  is the budget spent?
                                                     │
                                                     ▼
                                          BlockingDispatcher
                                                     │
                                  PackageManager.setPackagesSuspended()
                                                     │
                                                     ▼
                                    the framework refuses to launch the app
                                     and shows a LowhaOS dialog instead
```

The important property is that **the framework does the refusing**. Earlier
versions polled the foreground app and threw a blocking screen over it, which
left the app visible and usable for up to 1.5 seconds on every launch, and
missed the Recents route entirely. Suspension has no window and covers every
entry point at once — not because each was handled, but because launching is
what is blocked.

The details, and why each alternative was rejected, are in
[docs/decisions/](docs/decisions/).

## What it does

**Daily social media budget.** Thirteen apps are tracked by default — Instagram,
TikTok, Snapchat, Facebook, YouTube, X, Reddit, Threads, Tumblr, Pinterest,
Twitch, Kuaishou, LinkedIn. You can add more. You cannot remove the defaults.

**A commitment that means something.** Lowering your limit takes effect
immediately. Raising it waits for the commitment period to end. Lowering below
what you have already used today blocks the apps at once, until midnight — the
confirmation dialog says so plainly before you commit.

**Apps that can never be limited.** The dialer, messaging, contacts, emergency
dialling and Settings are excluded, in the service rather than merely in the
UI. A phone that cannot call for help is not an ethical product.

**A single browser.** Lowha Browser is the only browser on the device and is
pinned as the default. Content filtering lives there, so a second browser would
defeat it — the stock browsers are removed at build time.

**Clock tampering is detected, not prevented.** You keep control of your own
clock. Winding it forward does not grant you a free daily reset; see
[ADR-0002](docs/decisions/0002-detect-clock-tampering-rather-than-prohibit-it.md).

## Project status

**Current release: v0.6**, built and tested on Fairphone 6 hardware.

Verified on-device: onboarding persists the chosen limit; the Settings panel
appears and is styled correctly; suspension blocks tracked apps with no visible
window; protected apps are excluded from the picker; stock browsers are absent;
branding applies to home and lock screens.

Not yet verified: a block firing from organic accumulated usage, rather than
one triggered deliberately during testing.

> **A note on this repository.** The build host that carried the v0.6 working
> tree was lost before the project was published. The components here were
> reassembled from the development record — the enforcement code and its
> comments are the originals, the surrounding module structure was rebuilt
> around them. It has been checked statically (see `tools/`) but **has not been
> compiled in exactly this form**. Treat v0.6 binaries as the tested artefact
> and this tree as the readable, buildable source of the same design. If you
> build it and hit a problem, an issue would be genuinely useful.

### Known limitations

LowhaOS is a working prototype, not a hardened product. Several bypasses are
open by design in the current build, and they are documented honestly in
[SECURITY.md](SECURITY.md) rather than glossed over. The short version: this
build ships as `userdebug` with ADB available, and there is no Device Owner, so
a determined user with a USB cable can change the limit. Closing those is the
roadmap's first priority.

## Install

No prebuilt images are published here — LowhaOS binaries necessarily embed
proprietary Fairphone and Qualcomm firmware, which is not ours to redistribute.
Build your own from **[docs/build.md](docs/build.md)**, then flash it following
**[docs/flashing.md](docs/flashing.md)**.

Requires a Fairphone 6 with an unlocked bootloader. Flashing erases the device.

## Build from source

**[docs/build.md](docs/build.md)** — LowhaOS is a component overlay for a
LineageOS tree, not a standalone Android source tree. You will need the
LineageOS sources, device support for your target, and proprietary vendor blobs
extracted from your own device.

## Repository layout

```
lowha/
  apps/LowhaCoreService/   the system service: usage, policy, enforcement
  apps/LowhaSettings/      the Settings panel and app picker
  sepolicy/                SELinux policy for the core service
  overlay/                 branding and resource overlays
  prebuilts/               Soong modules for the browser and app store
vendor-config/             product makefile and privileged-permission allowlist
patches/                   patches against upstream LineageOS packages
tools/                     preflight validator and static checks
docs/
  decisions/               architecture decision records
  postmortems/             what broke, why, and what changed as a result
```

## Documentation

| | |
|---|---|
| [Build](docs/build.md) | Getting a tree and compiling |
| [Flashing](docs/flashing.md) | Getting it onto a phone |
| [Decisions](docs/decisions/) | Why the design is the way it is |
| [Implementation reference](docs/implementation-reference.md) | The detailed internals |
| [Postmortems](docs/postmortems/) | Failures worth not repeating |
| [Security](SECURITY.md) | Known bypasses and the roadmap to closing them |

The postmortems are public deliberately. A boot loop caused by a privileged app
whose permission allowlist sat on the wrong partition is not obvious, is not
well documented anywhere, and took a day to find. Writing it down is more
useful than pretending it did not happen.

## Licence

Apache 2.0 — see [LICENSE](LICENSE).

LowhaOS builds on work by others, and their terms are theirs, not ours. See
[NOTICE](NOTICE) for full attribution: LineageOS and AOSP (Apache-2.0), Lowha
Browser derived from [Lightning Browser](https://github.com/anthonycr/Lightning-Browser)
(MPL-2.0), and [Aurora Store](https://gitlab.com/AuroraOSS/AuroraStore) (GPL-3.0).

Proprietary vendor firmware is **not** distributed in this repository. You
extract it from your own device.

## Acknowledgements

LineageOS, for the tree this is built on and for fifteen years of keeping
devices alive. Fairphone, for building hardware you are allowed to unlock.
The Aurora Store and Lightning Browser projects.
