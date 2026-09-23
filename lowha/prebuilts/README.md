# Prebuilt APKs

The `.apk` files these modules reference are **not committed to this
repository**. Only the Soong module definitions are.

| Module | Where to get the APK | Licence |
|---|---|---|
| `LowhaBrowser` | Build from [`browser/`](../../browser) or drop in your own | MPL-2.0 |
| `AuroraStore` | [gitlab.com/AuroraOSS/AuroraStore](https://gitlab.com/AuroraOSS/AuroraStore) releases | GPL-3.0 |

Place each APK next to its `Android.bp` using the filename in the `apk:` field,
then build normally.

## Why they are not vendored

Binaries in a source repository go stale silently, make the history heavy, and
in Aurora's case mean redistributing someone else's build under a licence that
deserves to be honoured by pointing at the original.
