#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke85
# Checks a built APK: valid signature, and no permission except NFC.
#
#   tools/check-apk.sh seedtag.apk
#
# The no-network guarantee is the app's main security property, and a library
# update can quietly add a permission through manifest merging. This fails the
# build if that happens.
set -eu

APK="$1"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
    SDK="$(sed -n 's/^sdk.dir=//p' local.properties)"
fi
BUILD_TOOLS="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)}"
BUILD_TOOLS="${BUILD_TOOLS%/}"
AAPT2="$BUILD_TOOLS/aapt2"
APKSIGNER="$BUILD_TOOLS/apksigner"
for f in "$AAPT2" "$APKSIGNER"; do
    if [ ! -x "$f" ]; then
        echo "Missing: $f (set ANDROID_HOME)" >&2
        exit 1
    fi
done

"$APKSIGNER" verify --print-certs "$APK" | grep -E 'Signer #1 certificate (DN|SHA-256)'

# androidx.core declares and uses <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION,
# a signature permission private to this app. Everything else except NFC is an error.
# uses-permission-sdk-23 grants the same permissions, so it counts too.
perms="$("$AAPT2" dump permissions "$APK")"
pkg="$(printf '%s\n' "$perms" | sed -n 's/^package: //p')"
extra="$(printf '%s\n' "$perms" \
    | sed -nE "s/^uses-permission(-sdk-23)?: name='([^']*)'.*/\2/p" \
    | grep -vxF -e 'android.permission.NFC' -e "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" || true)"
if [ -n "$extra" ]; then
    echo "!! $APK requests permissions it must not have:" >&2
    echo "$extra" | sed 's/^/!!   /' >&2
    exit 1
fi
echo ">> $APK: signed, permissions OK (NFC only, no network)"
ls -l "$APK"
