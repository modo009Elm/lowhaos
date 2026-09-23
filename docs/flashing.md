# Flashing LowhaOS

> **This erases everything on the device.** Back up anything you care about
> first. An unlocked bootloader also means the device no longer passes the
> integrity checks some banking and DRM apps require.

## Before you start

* A Fairphone 6 with an **unlocked bootloader**
  ([Fairphone's instructions](https://support.fairphone.com/))
* `adb` and `fastboot` on your computer (`platform-tools`)
* A USB-C cable that carries data — charge-only cables are a common and
  confusing failure
* The LowhaOS zip and its `.sha256`

## 1. Verify the download

Never skip this. A truncated zip will fail partway through the flash.

```bash
sha256sum -c LowhaOS-v0.6-FP6.zip.sha256
```

## 2. Clear any adb overlay

If you have ever run `adb remount` on this device, an overlay is shadowing the
real partitions and your freshly flashed image will appear not to have applied.

```bash
adb root
adb enable-verity
adb reboot
```

## 3. Install LineageOS Recovery

Follow the LineageOS install guide for the Fairphone 6. LowhaOS uses the stock
LineageOS recovery; there is nothing Lowha-specific about this step.

## 4. Sideload

Boot to recovery → **Apply update** → **Apply from ADB**, then:

```bash
adb sideload LowhaOS-v0.6-FP6.zip
```

The progress counter stops updating around 47% and stays there. This is normal
— `adb` stops reporting once the device has finished reading. The line that
matters is:

```
Total xfer: 1.00x
```

## 5. Factory reset

**Do this before first boot**, from recovery: **Factory reset** → **Format data**.

Skipping it leaves data from the previous OS behind and the setup flow will not
run cleanly.

## 6. First boot

Several minutes. You should land in LowhaOS onboarding, where you choose your
daily limit.

That choice is binding for 7 days: you can lower it at any time, but you cannot
raise it until the commitment period ends. Pick a number you can live with.

## Troubleshooting

**Stuck on the boot animation for more than ~10 minutes.** Almost always
incomplete vendor blobs. See [build.md](build.md) step 2.

**`adb sideload` says "device unauthorized".** Normal in recovery — the
recovery shell does not hold your adb key. Sideload still works.

**The phone boots the previous version.** An `adb remount` overlay is shadowing
the new image. Step 2.
