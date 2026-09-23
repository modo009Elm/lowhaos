# Copyright (C) 2026 The LowhaOS Project
# SPDX-License-Identifier: Apache-2.0
#
# LowhaOS product configuration.
#
# Include this from your device makefile AFTER the LineageOS common config, so
# the overlay prepend below actually wins.

PRODUCT_PACKAGES += \
    LowhaCoreService \
    LowhaSettings \
    LowhaBrowser \
    AuroraStore

# Stock browsers are displaced by LowhaBrowser via Soong `overrides:`
# in lowha/prebuilts/LowhaBrowser/Android.bp.
#
# Do NOT use PRODUCT_DEL_PACKAGES here. It is not a real variable in this build
# system -- grepping build/make/ for it returns nothing -- so it fails silently
# and the package you meant to remove ships anyway. See
# docs/decisions/0004-removing-a-stock-package.md.

# Lowha Browser answers web links by default.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.lowha.version=0.6 \
    role_manager.default_browser_package=com.lowha.browser

# PREPENDED, not appended.
#
# PRODUCT_PACKAGE_OVERLAYS is first-match-wins. vendor/lineage adds its own
# overlay earlier in the include order, so appending ours meant Lineage's
# default wallpaper kept winning and our branding silently did not apply.
PRODUCT_PACKAGE_OVERLAYS := lowha/overlay $(PRODUCT_PACKAGE_OVERLAYS)

# LowhaCoreService's SELinux domain.
BOARD_SEPOLICY_DIRS += lowha/sepolicy
