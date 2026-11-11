#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
# Checks a built APK: valid signature, no permission except NFC, HIDE_OVERLAY_WINDOWS and VIBRATE, not
# debuggable or test-only, backups off, and no app components but the known ones. Prints the signing
# certificate and the APK's SHA-256.
#
#   tools/check-apk.sh seedtag.apk
#   tools/check-apk.sh --unsigned seedtag-unsigned.apk   # no signature to check
#   tools/check-apk.sh --debug seedtag-debug.apk         # a debug build: debuggable, Compose's preview activity
#
# The no-network guarantee is the app's main security property, and a library
# update can quietly add a permission through manifest merging. This fails the
# build if that happens. The rest catches honest mistakes (a debug build signed for release, a library
# adding an exported service); a tampered APK is caught by sign-release.sh --expect, not here.
set -eu

usage="usage: tools/check-apk.sh [--unsigned] [--debug] <apk>"
UNSIGNED=0
DEBUG=0
while [ $# -gt 0 ]; do
    case "$1" in
        --unsigned) UNSIGNED=1; shift ;;
        --debug) DEBUG=1; shift ;;
        --*) echo "Unknown option $1. $usage" >&2; exit 2 ;;
        *) break ;;
    esac
done
APK="${1:?$usage}"
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
# The manifest: the <application> element's own attributes, and every component it declares.
manifest="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK")"
app_attrs="$(printf '%s\n' "$manifest" | awk '/^ *E: application /{f=1; next} f && /^ *E: /{exit} f')"
has_attr() { printf '%s\n' "$app_attrs" | grep -q ":$1(0x[0-9a-f]*)=$2\$"; }
problems=""
if has_attr debuggable true && [ "$DEBUG" = 0 ]; then problems="$problems
android:debuggable is true (a debug build?)"; fi
if has_attr testOnly true; then problems="$problems
android:testOnly is true"; fi
if ! has_attr allowBackup false; then problems="$problems
android:allowBackup isn't false"; fi
# Components: SeedTag's one activity, androidx.startup's initializer (not exported) and
# profileinstaller's receiver (only callable with the DUMP permission, i.e. over adb). Debug builds
# also have Compose's preview activity. Anything else (a library's service, an extra exported
# activity) is an error, until it's been looked at and added here.
components="$(printf '%s\n' "$manifest" | awk '
    /^ *E: (activity|activity-alias|service|receiver|provider) /{ want = $2; next }
    want && /^ *E: /{ print want, "(unnamed)"; want = "" }
    want && /:name\(0x01010003\)="/{ match($0, /="[^"]*"/); print want, substr($0, RSTART + 2, RLENGTH - 3); want = "" }')"
allowed="activity rs.zivke.seedtag.MainActivity
provider androidx.startup.InitializationProvider
receiver androidx.profileinstaller.ProfileInstallReceiver"
if [ "$DEBUG" = 1 ]; then allowed="$allowed
activity androidx.compose.ui.tooling.PreviewActivity"; fi
unknown="$(printf '%s\n' "$components" | grep -vxF "$allowed" || true)"
if [ -n "$unknown" ]; then problems="$problems
$(printf '%s\n' "$unknown" | sed 's/^/component it must not have: /')"; fi
if ! printf '%s\n' "$components" | grep -qxF "activity rs.zivke.seedtag.MainActivity"; then problems="$problems
no rs.zivke.seedtag.MainActivity (couldn't read the manifest?)"; fi
if [ -n "$problems" ]; then
    echo "!! $APK:" >&2
    printf '%s\n' "$problems" | sed '/^$/d; s/^/!!   /' >&2
    exit 1
fi

if [ "$UNSIGNED" = 1 ]; then state="unsigned"; else state="signed"; fi
if [ "$DEBUG" = 1 ]; then state="$state debug build"; fi
echo ">> $APK: $state, permissions OK (NFC, HIDE_OVERLAY_WINDOWS and VIBRATE only, no network)"
echo ">> manifest OK: $(if [ "$DEBUG" = 1 ]; then echo "debuggable (debug build)"; else echo "not debuggable"; fi), not test-only, no backups, known components only"
echo ">> SHA-256: $(sha256sum "$APK" | cut -d' ' -f1)"
ls -l "$APK"
