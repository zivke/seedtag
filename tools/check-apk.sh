#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
# Checks a built APK: valid signature, no permission except NFC, HIDE_OVERLAY_WINDOWS and VIBRATE, not
# debuggable or test-only, backups off, and no app components but the known ones. Also (board B36): no
# Application class, shared user ID or other process, no intent filter but the launcher's, no <queries>, no
# permission it declares but androidx's own signature one, minSdk/targetSdk not lowered, no cleartext traffic
# or network security config, the data-extraction rules in place, and only the known native libraries.
# Prints the signing certificate and the APK's SHA-256.
#
#   tools/check-apk.sh seedtag.apk
#   tools/check-apk.sh --unsigned seedtag-unsigned.apk   # no signature to check
#   tools/check-apk.sh --debug seedtag-debug.apk         # a debug build: debuggable
#
# The no-network guarantee is the app's main security property, and a library
# update can quietly add a permission through manifest merging. This fails the
# build if that happens. The rest catches honest mistakes (a debug build signed for release, a library
# adding an exported service). Whether the APK is the one built from the release tag is checked
# elsewhere: release-tag.sh's review, the CI run page, and sign-release.sh.
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
manifest_attrs="$(printf '%s\n' "$manifest" | awk '/^ *E: manifest /{f=1; next} f && /^ *E: /{exit} f')"
has_attr() { printf '%s\n' "$app_attrs" | grep -q ":$1(0x[0-9a-f]*)=$2\$"; }
# Set at all, to any value: a resource reference (@0x7f...) counts, since it can resolve to true.
sets_attr() { printf '%s\n' "$app_attrs" | grep -q ":$1(0x[0-9a-f]*)="; }
problems=""
if sets_attr debuggable && ! has_attr debuggable false && [ "$DEBUG" = 0 ]; then problems="$problems
android:debuggable is set, not to false (a debug build?)"; fi
if sets_attr testOnly && ! has_attr testOnly false; then problems="$problems
android:testOnly is set, not to false"; fi
if ! has_attr allowBackup false; then problems="$problems
android:allowBackup isn't false"; fi
if ! has_attr fullBackupContent false; then problems="$problems
android:fullBackupContent isn't false"; fi
if ! sets_attr dataExtractionRules; then problems="$problems
no android:dataExtractionRules (Android 12+ device-to-device transfer would copy the app's data)"; fi
# The app's own code only: no Application class (code that runs before MainActivity), one process.
if printf '%s\n' "$app_attrs" | grep -q ':name(0x01010003)='; then problems="$problems
android:name on <application> (an Application class)"; fi
if printf '%s\n' "$manifest" | grep -q ':process(0x[0-9a-f]*)='; then problems="$problems
android:process is set (a second process)"; fi
if printf '%s\n' "$manifest_attrs" | grep -q ':sharedUserId(0x[0-9a-f]*)='; then problems="$problems
android:sharedUserId is set"; fi
# No network: no cleartext permission, no network security config (there's no INTERNET to use them anyway).
if sets_attr usesCleartextTraffic && ! has_attr usesCleartextTraffic false; then problems="$problems
android:usesCleartextTraffic is set"; fi
if sets_attr networkSecurityConfig; then problems="$problems
android:networkSecurityConfig is set"; fi
# Package visibility: SeedTag needs to see no other app.
if printf '%s\n' "$manifest" | grep -qE '^ *E: queries '; then problems="$problems
a <queries> element (asks to see other apps)"; fi
# The only permission it declares is androidx.core's own, signature-level (protectionLevel 0x2).
declared="$(printf '%s\n' "$manifest" | awk '
    /^ *E: (permission|permission-group|permission-tree) /{ want = $2; name = ""; next }
    want && /^ *E: /{ print want, name, level; want = "" }
    want && /:name\(0x01010003\)="/{ match($0, /="[^"]*"/); name = substr($0, RSTART + 2, RLENGTH - 3) }
    want && /:protectionLevel\(/{ sub(/.*=/, ""); level = $0 }
    END { if (want) print want, name, level }')"
unknown="$(printf '%s\n' "$declared" | sed '/^$/d' | grep -vxF "permission $pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION 0x00000002" || true)"
if [ -n "$unknown" ]; then problems="$problems
$(printf '%s\n' "$unknown" | sed 's/^/declares: /')"; fi
# SDK levels: never below what SeedTag is made for (a lower targetSdk drops platform protections).
min_sdk="$(printf '%s\n' "$manifest" | sed -n 's/.*:minSdkVersion(0x[0-9a-f]*)=\([0-9]*\)$/\1/p')"
target_sdk="$(printf '%s\n' "$manifest" | sed -n 's/.*:targetSdkVersion(0x[0-9a-f]*)=\([0-9]*\)$/\1/p')"
if [ -z "$min_sdk" ] || [ "$min_sdk" -lt 26 ]; then problems="$problems
minSdkVersion is ${min_sdk:-unreadable}, expected 26 or more"; fi
if [ -z "$target_sdk" ] || [ "$target_sdk" -lt 36 ]; then problems="$problems
targetSdkVersion is ${target_sdk:-unreadable}, expected 36 or more"; fi
# Intent filters: the launcher's MAIN/LAUNCHER and nothing else (no NFC dispatch, no links, no share target).
filters="$(printf '%s\n' "$manifest" | awk '
    /^ *E: (action|category|data) /{ if (want) print want, "(unnamed)"; want = $2; next }
    want && /^ *E: /{ print want, "(unnamed)"; want = "" }
    want && /:name\(0x01010003\)="/{ match($0, /="[^"]*"/); print want, substr($0, RSTART + 2, RLENGTH - 3); want = "" }
    END { if (want) print want, "(unnamed)" }')"
unknown="$(printf '%s\n' "$filters" | sed '/^$/d' | grep -vxF -e "action android.intent.action.MAIN" \
    -e "category android.intent.category.LAUNCHER" || true)"
if [ -n "$unknown" ]; then problems="$problems
$(printf '%s\n' "$unknown" | sed 's/^/intent filter it must not have: /')"; fi
# No library code may run before SeedTag's own: no component factory, which would create every
# component (androidx.core's is removed in the manifest).
if printf '%s\n' "$app_attrs" | grep -q ':appComponentFactory('; then problems="$problems
android:appComponentFactory is set (a library instantiating the app's components)"; fi
# Components: SeedTag's one activity and nothing else. A library's provider, receiver or service
# (androidx.startup's initializer, the profile installer's receiver) is an error, until it's been
# looked at and added here.
components="$(printf '%s\n' "$manifest" | awk '
    /^ *E: (activity|activity-alias|service|receiver|provider) /{ want = $2; next }
    want && /^ *E: /{ print want, "(unnamed)"; want = "" }
    want && /:name\(0x01010003\)="/{ match($0, /="[^"]*"/); print want, substr($0, RSTART + 2, RLENGTH - 3); want = "" }')"
allowed="activity rs.zivke.seedtag.MainActivity"
unknown="$(printf '%s\n' "$components" | grep -vxF "$allowed" || true)"
if [ -n "$unknown" ]; then problems="$problems
$(printf '%s\n' "$unknown" | sed 's/^/component it must not have: /')"; fi
# Shared libraries the system would load into the app's process. Only androidx.window's two
# optional ones (Compose asks the phone's own window extensions about folds and screen areas;
# they're part of the system image, and only present on some phones).
libraries="$(printf '%s\n' "$manifest" | awk '
    /^ *E: (uses-library|uses-native-library) /{ want = $2; next }
    want && /^ *E: /{ print want, "(unnamed)"; want = "" }
    want && /:name\(0x01010003\)="/{ match($0, /="[^"]*"/); print want, substr($0, RSTART + 2, RLENGTH - 3); want = "" }')"
allowed_libraries="uses-library androidx.window.extensions
uses-library androidx.window.sidecar"
unknown="$(printf '%s\n' "$libraries" | sed '/^$/d' | grep -vxF "$allowed_libraries" || true)"
if [ -n "$unknown" ]; then problems="$problems
$(printf '%s\n' "$unknown" | sed 's/^/library it must not load: /')"; fi
if ! printf '%s\n' "$components" | grep -qxF "activity rs.zivke.seedtag.MainActivity"; then problems="$problems
no rs.zivke.seedtag.MainActivity (couldn't read the manifest?)"; fi
# Native code: only androidx.graphics.path's small library (Compose's path drawing), for the four ABIs.
if command -v unzip >/dev/null 2>&1; then entries="$(unzip -Z1 "$APK")"
elif command -v python3 >/dev/null 2>&1; then
    entries="$(python3 -c 'import sys, zipfile; print("\n".join(zipfile.ZipFile(sys.argv[1]).namelist()))' "$APK")"
else echo "Missing: unzip or python3 (to list the APK's native libraries)" >&2; exit 1
fi
allowed_so="lib/arm64-v8a/libandroidx.graphics.path.so
lib/armeabi-v7a/libandroidx.graphics.path.so
lib/x86/libandroidx.graphics.path.so
lib/x86_64/libandroidx.graphics.path.so"
unknown="$(printf '%s\n' "$entries" | grep '^lib/' | grep -vxF "$allowed_so" || true)"
if [ -n "$unknown" ]; then problems="$problems
$(printf '%s\n' "$unknown" | sed 's/^/native library it must not have: /')"; fi
if [ -n "$problems" ]; then
    echo "!! $APK:" >&2
    printf '%s\n' "$problems" | sed '/^$/d; s/^/!!   /' >&2
    exit 1
fi

if [ "$UNSIGNED" = 1 ]; then state="unsigned"; else state="signed"; fi
if [ "$DEBUG" = 1 ]; then state="$state debug build"; fi
echo ">> $APK: $state, permissions OK (NFC, HIDE_OVERLAY_WINDOWS and VIBRATE only, no network)"
echo ">> manifest OK: $(if [ "$DEBUG" = 1 ]; then echo "debuggable (debug build)"; else echo "not debuggable"; fi), not test-only, no backups, only MainActivity, no component factory, known libraries only"
echo ">> also OK: no Application class, process or shared user, launcher intent filter only, no <queries>, no declared permission but androidx's, minSdk $min_sdk, targetSdk $target_sdk, no cleartext, known native libraries"
if command -v sha256sum >/dev/null 2>&1; then sum="$(sha256sum "$APK")"; else sum="$(shasum -a 256 "$APK")"; fi
echo ">> SHA-256: ${sum%% *}"
ls -l "$APK"
