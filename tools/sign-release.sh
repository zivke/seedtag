#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
# Signs the release APK with the release key. Run it OUTSIDE the dev container, on the machine
# that holds the key: the key and its password never enter the container.
#
#   tools/sign-release.sh [--expect <sha256>] <keystore> [key alias]
#
# --expect: the SHA-256 of seedtag-unsigned.apk from an independent build of the same commit (the
# GitHub Actions build, or `make build` in a fresh clone on another machine). The APK is only signed
# if it matches, so a build tampered with in the dev container isn't signed by mistake. Without it,
# the script warns and signs anyway.
#
# Reads seedtag-unsigned.apk (from `make build`), writes seedtag.apk and its checksum in
# seedtag.apk.sha256. apksigner asks for the
# keystore password. The result must be signed with the certificate whose SHA-256 is in
# tools/release-cert.sha256, or it's deleted. Needs the Android build-tools (apksigner, aapt2),
# from ANDROID_HOME or on PATH.
#
# This script comes from the repository, which the dev container can write to. Before running it
# with your key, check it hasn't changed unexpectedly: git log -p -- tools/sign-release.sh tools/check-apk.sh
set -eu

EXPECT=""
if [ "${1:-}" = "--expect" ]; then
    EXPECT="$(printf '%s' "${2:?--expect needs the SHA-256 of an independent build}" | tr 'A-F' 'a-f')"
    shift 2
fi
KS="${1:?usage: tools/sign-release.sh [--expect <sha256>] <keystore> [key alias]}"
ALIAS="${2:-}"
IN="${IN:-seedtag-unsigned.apk}"
OUT="${OUT:-seedtag.apk}"
HERE="$(cd "$(dirname "$0")" && pwd)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BUILD_TOOLS="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)}"
BUILD_TOOLS="${BUILD_TOOLS%/}"
if [ -x "$BUILD_TOOLS/apksigner" ]; then APKSIGNER="$BUILD_TOOLS/apksigner"
elif command -v apksigner >/dev/null 2>&1; then APKSIGNER="$(command -v apksigner)"
else echo "Missing: apksigner (set ANDROID_HOME, or put the Android build-tools on PATH)" >&2; exit 1
fi

[ -f "$IN" ] || { echo "Missing: $IN (run make build first)" >&2; exit 1; }
got="$(sha256sum "$IN" | cut -d' ' -f1)"
if [ -n "$EXPECT" ]; then
    if [ "$got" != "$EXPECT" ]; then
        echo "!! $IN has SHA-256 $got," >&2
        echo "!! but the independent build has $EXPECT. Not signing." >&2
        echo "!! Find out what differs first (unzip both and diff -r, or diffoscope)." >&2
        exit 1
    fi
    echo ">> $IN matches the independent build ($got)"
else
    echo "!! No --expect: $IN ($got) wasn't compared with an independent build." >&2
    echo "!! See \"Build integrity\" in README.md." >&2
fi
"$HERE/check-apk.sh" --unsigned "$IN" >/dev/null

rm -f "$OUT"
if [ -n "$ALIAS" ]; then
    "$APKSIGNER" sign --ks "$KS" --ks-key-alias "$ALIAS" --out "$OUT" "$IN"
else
    "$APKSIGNER" sign --ks "$KS" --out "$OUT" "$IN"
fi
rm -f "$OUT.idsig"

expected="$(tr -d ' \n' < "$HERE/release-cert.sha256")"
actual="$("$APKSIGNER" verify --print-certs "$OUT" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [ "$actual" != "$expected" ]; then
    rm -f "$OUT"
    echo "!! Signed with certificate $actual, but the release certificate is $expected. Deleted $OUT." >&2
    exit 1
fi
"$HERE/check-apk.sh" "$OUT"
sha256sum "$OUT" > "$OUT.sha256"
echo ">> $OUT is signed with the release key. Publish $OUT.sha256 and the certificate digest with it."
