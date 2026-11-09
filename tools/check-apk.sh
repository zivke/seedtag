#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
# Checks a built APK: valid signature, and no permission except NFC, HIDE_OVERLAY_WINDOWS and VIBRATE.
# Prints the signing certificate and the APK's SHA-256.
#
#   tools/check-apk.sh seedtag.apk
#   tools/check-apk.sh --unsigned seedtag-unsigned.apk   # permissions only
#
# The no-network guarantee is the app's main security property, and a library
# update can quietly add a permission through manifest merging. This fails the
# build if that happens.
set -eu

UNSIGNED=0
if [ "${1:-}" = "--unsigned" ]; then UNSIGNED=1; shift; fi
APK="${1:?usage: tools/check-apk.sh [--unsigned] <apk>}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
    SDK="$(sed -n 's/^sdk.dir=//p' local.properties)"
fi
BUILD_TOOLS="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)}"
BUILD_TOOLS="${BUILD_TOOLS%/}"
# Outside the dev container (e.g. on the signing machine) the tools may only be on PATH.
tool() {
    if [ -x "$BUILD_TOOLS/$1" ]; then echo "$BUILD_TOOLS/$1"
    elif command -v "$1" >/dev/null 2>&1; then command -v "$1"
    else echo "Missing: $1 (set ANDROID_HOME, or put the Android build-tools on PATH)" >&2; exit 1
    fi
}
AAPT2="$(tool aapt2)"
APKSIGNER="$(tool apksigner)"

if [ "$UNSIGNED" = 1 ]; then
    if "$APKSIGNER" verify "$APK" >/dev/null 2>&1; then
        echo "!! $APK is signed, but --unsigned was given" >&2
        exit 1
    fi
else
    "$APKSIGNER" verify --print-certs "$APK" | grep -E 'Signer #1 certificate (DN|SHA-256)'
fi

# androidx.core declares and uses <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION,
# a signature permission private to this app. HIDE_OVERLAY_WINDOWS lets SeedTag hide other apps'
# overlays (Android 12+) and VIBRATE runs the vibration motor; neither grants access to anything.
# Everything else except NFC is an error.
# uses-permission-sdk-23 grants the same permissions, so it counts too.
perms="$("$AAPT2" dump permissions "$APK")"
pkg="$(printf '%s\n' "$perms" | sed -n 's/^package: //p')"
extra="$(printf '%s\n' "$perms" \
    | sed -nE "s/^uses-permission(-sdk-23)?: name='([^']*)'.*/\2/p" \
    | grep -vxF -e 'android.permission.NFC' -e 'android.permission.HIDE_OVERLAY_WINDOWS' \
        -e 'android.permission.VIBRATE' \
        -e "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" || true)"
if [ -n "$extra" ]; then
    echo "!! $APK requests permissions it must not have:" >&2
    echo "$extra" | sed 's/^/!!   /' >&2
    exit 1
fi
if [ "$UNSIGNED" = 1 ]; then state="unsigned"; else state="signed"; fi
echo ">> $APK: $state, permissions OK (NFC, HIDE_OVERLAY_WINDOWS and VIBRATE only, no network)"
echo ">> SHA-256: $(sha256sum "$APK" | cut -d' ' -f1)"
ls -l "$APK"
