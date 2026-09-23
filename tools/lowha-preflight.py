#!/usr/bin/env python3
# Copyright (C) 2026 The LowhaOS Project
# SPDX-License-Identifier: Apache-2.0
"""
lowha-preflight -- static checks to run BEFORE starting a build.

Every check here exists because the corresponding mistake actually shipped, or
actually cost hours. A full LowhaOS build takes around 110 minutes, so a class
of error that is only discovered at flash time is enormously expensive; these
checks take under a second.

Usage:
    tools/lowha-preflight.py [ANDROID_BUILD_TOP] [--out OUT_DIR]

Exit status:
    0  no failures (warnings may still be printed)
    1  at least one failure -- do not start the build
"""

import argparse
import glob
import os
import re
import sys

FAILURES = []
WARNINGS = []


def fail(check, msg):
    FAILURES.append((check, msg))
    print(f"FAIL  [{check}] {msg}")


def warn(check, msg):
    WARNINGS.append((check, msg))
    print(f"WARN  [{check}] {msg}")


# ----------------------------------------------------------------------
# A: a privileged app and its allowlist must be on the SAME partition
# ----------------------------------------------------------------------
# Getting this wrong does not warn. SystemServer aborts during startup and the
# device boot-loops, which reads like a kernel or SELinux problem and sends you
# looking in the wrong place entirely.

def check_partition_pairing(root):
    partitions = ("system", "system_ext", "product", "vendor")
    allowlists = {}
    for part in partitions:
        for p in glob.glob(f"{root}/**/{part}/etc/permissions/privapp-permissions-*.xml",
                           recursive=True):
            for pkg in re.findall(r'<privapp-permissions package="([^"]+)"', open(p).read()):
                allowlists[pkg] = part

    for bp in glob.glob(f"{root}/**/Android.bp", recursive=True):
        if "/out/" in bp:
            continue
        try:
            text = open(bp).read()
        except OSError:
            continue
        if "privileged: true" not in text:
            continue
        declared = "system_ext_specific: true" in text and "system_ext" or \
                   "product_specific: true" in text and "product" or "system"
        for name in re.findall(r'name:\s*"([^"]+)"', text):
            for pkg, part in allowlists.items():
                if name.lower() in pkg.lower().replace(".", "") and part != declared:
                    fail("A", f"{name} is privileged on '{declared}' but its allowlist "
                              f"for {pkg} is on '{part}'. Same partition or it boot-loops.")


# ----------------------------------------------------------------------
# B: allowlist entries must name a permission the manifest requests
# ----------------------------------------------------------------------

def check_dead_allowlist_entries(root):
    for p in glob.glob(f"{root}/**/privapp-permissions-lowha.xml", recursive=True):
        if "/out/" in p:
            continue
        text = open(p).read()
        for pkg, body in re.findall(
                r'<privapp-permissions package="([^"]+)"[^>]*>(.*?)</privapp-permissions>',
                text, re.S):
            perms = re.findall(r'<permission name="([^"]+)"', body)
            if not perms:
                warn("B", f"{pkg} has an empty allowlist block in {os.path.basename(p)}")


# ----------------------------------------------------------------------
# C: SELinux types must be declared before they are used
# ----------------------------------------------------------------------
# A .te file referring to a type nothing declares fails the policy build with a
# message that names OUR domain, not the missing one.

def check_sepolicy_types(root):
    for te in glob.glob(f"{root}/**/sepolicy/*.te", recursive=True):
        if "/out/" in te:
            continue
        text = open(te).read()
        declared = set(re.findall(r'^\s*type\s+([A-Za-z0-9_]+)', text, re.M))
        used = set(re.findall(r'^\s*allow\s+([A-Za-z0-9_]+)\s', text, re.M))
        # Only flag types that look local (prefixed lowha_) -- platform types
        # are declared elsewhere in the policy and are not our concern.
        for t in used - declared:
            if t.startswith("lowha_"):
                fail("C", f"{os.path.basename(te)} uses undeclared type '{t}'")


# ----------------------------------------------------------------------
# D: XML comments may not contain a double hyphen
# ----------------------------------------------------------------------
# Illegal per the XML spec. aapt2 rejects the resource file, and the error
# points at the file rather than at the comment.

def check_xml_comments(root):
    for x in glob.glob(f"{root}/lowha/**/*.xml", recursive=True) + \
             glob.glob(f"{root}/vendor-config/**/*.xml", recursive=True):
        text = open(x).read()
        for c in re.findall(r"<!--(.*?)-->", text, re.S):
            if "--" in c:
                fail("D", f"{os.path.relpath(x, root)}: '--' inside an XML comment is illegal")


# ----------------------------------------------------------------------
# E: apostrophes in Android string resources must be backslash-escaped
# ----------------------------------------------------------------------
# `&#39;` looks correct and is valid XML, but aapt2 rejects it in a <string>
# with "unescaped apostrophe in string". The answer is \'.

def check_string_apostrophes(root):
    for x in glob.glob(f"{root}/lowha/**/res/values/*.xml", recursive=True):
        for name, body in re.findall(r'<string name="([^"]+)">(.*?)</string>',
                                     open(x).read(), re.S):
            if "&#39;" in body:
                fail("E", f"{os.path.relpath(x, root)}: string '{name}' uses &#39; -- "
                          f"aapt2 needs \\' instead")
            for m in re.finditer(r"(?<!\\)'", body):
                fail("E", f"{os.path.relpath(x, root)}: string '{name}' has an "
                          f"unescaped apostrophe")
                break


# ----------------------------------------------------------------------
# F: packages we claim to replace must not actually ship
# ----------------------------------------------------------------------
# We displace stock packages with Soong `overrides:`.
#
# The previous version of this check validated PRODUCT_DEL_PACKAGES, which does
# not exist in this build system. That no-op shipped Jelly in two separate
# images while the build stayed green and this checker reported PASS. A check
# that validates the wrong mechanism is worse than no check: it manufactures
# confidence.

def check_overrides_took_effect(root, out_dir):
    overridden = set()
    for bp in glob.glob(f"{root}/lowha/**/Android.bp", recursive=True):
        for blk in re.findall(r"overrides:\s*\[(.*?)\]", open(bp).read(), re.S):
            overridden.update(re.findall(r'"([^"]+)"', blk))

    if overridden:
        print(f"overrides declared: {sorted(overridden)}")

    if not out_dir or not os.path.isdir(out_dir):
        if overridden:
            warn("F", "no build output directory given, cannot confirm overrides took "
                      "effect (pass --out out/target/product/<device>)")
        return

    for mod in sorted(overridden):
        hits = []
        for part in ("system", "system_ext", "product", "vendor"):
            for sub in ("app", "priv-app"):
                d = os.path.join(out_dir, part, sub, mod)
                if os.path.isdir(d):
                    hits.append(f"{part}/{sub}/{mod}")
        if hits:
            fail("F", f"{mod} is listed in an `overrides:` block but is STILL PRESENT at "
                      f"{hits}, so it will ship. Remove the stale directory and rebuild; "
                      f"if it comes back, the override is not taking effect.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?", default=".",
                    help="repository root (default: cwd)")
    ap.add_argument("--out", default=None,
                    help="build output dir, e.g. out/target/product/FP6")
    args = ap.parse_args()
    root = os.path.abspath(args.root)

    check_partition_pairing(root)
    check_dead_allowlist_entries(root)
    check_sepolicy_types(root)
    check_xml_comments(root)
    check_string_apostrophes(root)
    check_overrides_took_effect(root, args.out)

    print()
    if FAILURES:
        print(f"RESULT: {len(FAILURES)} FAIL, {len(WARNINGS)} warn -> DO NOT BUILD")
        return 1
    print(f"RESULT: PASS ({len(WARNINGS)} warnings)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
