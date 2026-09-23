# Security

## Reporting a vulnerability

Please open a [security advisory](../../security/advisories/new) rather than a
public issue, and allow a reasonable window for a fix before disclosing.

## Known limitations in the current build

LowhaOS v0.6 is a working prototype. Several protections are **not yet in
place**, and this section says so plainly rather than leaving people to assume
otherwise — a tool that overstates what it enforces is worse than one that is
honest about its gaps, because people make decisions based on the claim.

If you are considering LowhaOS as a safeguard for someone else, read this
section first.

### 1. Debug build with ADB enabled — highest severity

v0.6 is built as `userdebug` with ADB available. Anyone with physical access,
a USB cable and a computer can change the stored limit directly. This bypasses
every other protection in the system.

*Fix:* build the `user` variant, which disables ADB by default. Complete
closure additionally requires Device Owner (below), since Developer Options can
otherwise re-enable ADB.

### 2. No Device Owner provisioned

Android's Device Owner role is what allows an application to apply
device-wide restrictions. LowhaOS does not provision one, so a family of
restrictions that the design assumes are simply never applied:

| Restriction | What it would close |
|---|---|
| `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` | Sideloading an unrestricted browser or app |
| `DISALLOW_CONFIG_DATE_TIME` | Clock changes (currently detected, not prevented) |
| `DISALLOW_DEBUGGING_FEATURES` | Re-enabling ADB |
| `DISALLOW_FACTORY_RESET` | Wiping the device to escape |
| `DISALLOW_SAFE_BOOT` | Safe mode, which does not start our service |

In practice this means **unknown-sources installation is not blocked**, despite
being part of the intended design. A user can install another browser and
bypass content filtering entirely.

*Fix:* implement a `DeviceAdminReceiver` and provision during setup. Device
Owner can only be established before any account is added, so it must happen in
the onboarding flow. This is the single highest-value item on the roadmap.

### 3. Backup restore can reinstall blocked apps

The bundled backup application holds `INSTALL_PACKAGES`. Restoring a backup can
install applications without passing through the normal store flow — including
browsers LowhaOS has never heard of, which are therefore not suspended and not
filtered.

*Fix:* remove the backup application via Soong `overrides:`, or replace it with
one that does not hold that permission. The trade-off is that users lose
backup and restore.

### 4. Prebuilt browser signed with a debug key

The shipped browser binary is signed with the standard Android debug key, whose
private key is public. An attacker could sign a modified build with the same
key and have it accepted as a legitimate update to the browser the filtering
depends on.

*Fix:* sign release builds with a private release key.

## Threat model

LowhaOS is designed against a **motivated but non-technical user** — typically
the device's own owner, who asked for the limit and will later wish they had
not. Against that model, suspension-based enforcement and the commitment period
work well.

It is **not** currently hardened against a technically capable adversary with
physical access. Items 1 and 2 above are the reason, and both are closable.
