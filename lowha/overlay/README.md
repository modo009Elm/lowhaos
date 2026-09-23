# Resource overlays

Resources placed here replace the LineageOS and AOSP defaults at build time.

## Wallpaper: mind the density bucket

Branding assets are **not committed** to this repository — supply your own.

Placing a wallpaper only in `drawable-nodpi/` is not enough. Android resolves
by density first, so LineageOS's own `drawable-xxhdpi/default_wallpaper.png`
wins on any device in that bucket and yours silently never appears.

The Fairphone 6 reports **440 dpi**, which is the `xxhdpi` bucket. Provide at
minimum:

```
frameworks/base/core/res/res/
  drawable-nodpi/default_wallpaper.png
  drawable-xxhdpi/default_wallpaper.png      <- the one FP6 actually uses
  drawable-xxxhdpi/default_wallpaper.png
```

Verify by dimension rather than by presence after a build:

```bash
unzip -p out/target/product/FP6/system/framework/framework-res.apk \
      res/drawable-xxhdpi-v4/default_wallpaper.png | file -
```

If it reports square dimensions, you are looking at the LineageOS default and
the overlay has not applied — most likely because `PRODUCT_PACKAGE_OVERLAYS`
appended instead of prepending. It is first-match-wins. See
`vendor-config/device-lowha-core.mk`.
