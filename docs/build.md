# Building LowhaOS

LowhaOS is not a standalone Android tree. It is a **component overlay** that
drops into a LineageOS checkout, in the same way a device tree does. You will
need the LineageOS sources and working device support for your target before
anything here is useful.

## What you need

| | |
|---|---|
| Disk | ~250 GB free (the source tree alone is ~120 GB) |
| RAM | 16 GB minimum, 32 GB comfortable |
| CPU | Any modern x86-64. A full build took ~110 min on 32 vCPU |
| OS | Linux. Ubuntu 22.04 or 24.04 are the well-trodden paths |
| Java | JDK 21 (installed by the LineageOS build environment) |

A first build is measured in hours and mostly bounded by disk speed. Use an
SSD; a spinning disk will roughly triple it.

## 1. Set up a LineageOS tree

Follow the [LineageOS build guide](https://wiki.lineageos.org/devices/) for your
device. LowhaOS v0.6 targets **LineageOS 23.2 (Android 16)** on the
**Fairphone 6 (`FP6`)**.

```bash
mkdir -p ~/android/lineage && cd ~/android/lineage
repo init -u https://github.com/LineageOS/android.git -b lineage-23.2 --git-lfs
repo sync -c -j$(nproc) --force-sync --no-clone-bundle --no-tags
```

## 2. Device support and proprietary blobs

You need your device's tree, kernel and vendor blobs. **These are not
distributed here** — vendor blobs are proprietary firmware belonging to the
hardware manufacturer and their chipset vendor, and redistributing them is not
ours to do.

Extract them from a device already running the stock OS, using the standard
`extract-files.sh` in the device tree.

> A build with an incomplete vendor image will flash and then hang at boot.
> The failure mode is silent: `vold` waits forever in `mount_all --late`
> because the KeyMint HAL is missing. If your device boots to the animation and
> stays there, check the vendor blobs before anything else.

## 3. Add the LowhaOS components

```bash
git clone https://github.com/modo009Elm/lowhaos.git vendor/lowhaos
```

Then include the product config from your device makefile, **after** the
LineageOS common config:

```make
$(call inherit-product, vendor/lowhaos/vendor-config/device-lowha-core.mk)
```

Order matters. `PRODUCT_PACKAGE_OVERLAYS` is first-match-wins, and the config
*prepends* the Lowha overlay so it beats the LineageOS defaults. Including it
too early silently loses the branding.

## 4. Supply the prebuilt APKs

See [`lowha/prebuilts/README.md`](../lowha/prebuilts/README.md). Briefly: drop
`LowhaBrowser.apk` and `AuroraStore.apk` next to their `Android.bp` files.

Generate a signing key for the re-signed prebuilts:

```bash
subject='/C=GB/ST=/L=/O=LowhaOS/OU=/CN=lowha_preload/emailAddress='
development/tools/make_key vendor/lowhaos/security/lowha_preload "$subject"
```

Keep that key private and out of version control. `.gitignore` already excludes
`*.pk8` and `*.x509.pem`.

## 5. Preflight, then build

Run the static checks first. They take under a second and catch several classes
of error that otherwise surface ~110 minutes later, or at flash time:

```bash
python3 vendor/lowhaos/tools/lowha-preflight.py vendor/lowhaos
```

Then:

```bash
source build/envsetup.sh
lunch lineage_FP6-bp2a-userdebug
mka bacon
```

The flashable zip lands in `out/target/product/FP6/`.

Re-run preflight against the output to confirm the overrides took effect:

```bash
python3 vendor/lowhaos/tools/lowha-preflight.py vendor/lowhaos \
        --out out/target/product/FP6
```

## 6. Flash

See [flashing.md](flashing.md).

## Build operations: lessons paid for

These are in [the postmortem](postmortems/2026-07-22-build-operations.md) in
full. The short version, because each of these cost hours:

1. **Never kill `ninja` by name.** `pkill -x ninja` corrupted the kernel object
   tree and cost a 4.2 GB delete plus a full kernel rebuild. Stop a build with
   Ctrl-C, or let it finish.
2. **Never `pkill -f` a pattern that matches your own shell.** A monitor loop
   matching `soong_ui|ninja` matched itself and killed the session it was
   monitoring.
3. **Do not edit sources mid-build.** Soong re-globs and restarts analysis; one
   session spent 97 minutes without ever reaching `bacon`.
4. **`adb remount` shadows flashed partitions.** The overlay on `/mnt/scratch`
   wins over the real partition, so a freshly flashed image looks like it did
   not apply. Run `adb enable-verity` and reboot before flashing.
5. **Verify artefacts by timestamp and dimension, not presence.** "Absent" and
   "not rebuilt yet" look identical until you check.
