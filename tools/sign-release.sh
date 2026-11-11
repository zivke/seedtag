#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
# Signs the release APK with the release key. Run it OUTSIDE the dev container, on the machine
# that holds the key, from a fresh clone checked out at the release tag: the key and its password
# never enter the container, and the script that handles them isn't one the container could edit.
#
#   tools/sign-release.sh (--expect <sha256> | --no-expect) [--no-verify-tag] <keystore> [key alias]
#
# Before anything touches the key, it checks:
# - --expect: seedtag-unsigned.apk has this SHA-256, the one the GitHub Actions run of the release
#   tag shows (or `make build` in a fresh clone on another machine). So a build tampered with in the
#   dev container isn't signed. It's required; --no-expect skips it, with a warning.
# - The clone has no uncommitted changes, HEAD is exactly a vX.Y.Z tag, and `git verify-tag` accepts
#   that tag's signature (made with your key, outside the container). --no-verify-tag skips the
#   signature check, with a warning, e.g. for a first try.
# - The APK says it was built from HEAD (META-INF/version-control-info.textproto).
# - The permissions (check-apk.sh).
#
# Reads seedtag-unsigned.apk (from `make build`), writes seedtag.apk and its checksum in
# seedtag.apk.sha256. apksigner asks for the keystore password. The result must be signed with the
# certificate whose SHA-256 is in tools/release-cert.sha256, or it's deleted. Needs git, unzip and
# the Android build-tools (apksigner, aapt2), from ANDROID_HOME or on PATH.
#
# Review what changed since the last release before running this with your key, in this clone:
# git diff <previous tag>..<this tag> (the source, and this script, check-apk.sh and the CI workflow).
set -eu

usage="usage: tools/sign-release.sh (--expect <sha256> | --no-expect) [--no-verify-tag] <keystore> [key alias]"
EXPECT=""
NO_EXPECT=0
VERIFY_TAG=1
while [ $# -gt 0 ]; do
    case "$1" in
        --expect) EXPECT="$(printf '%s' "${2:?--expect needs the SHA-256 of an independent build}" | tr 'A-F' 'a-f')"; shift 2 ;;
        --no-expect) NO_EXPECT=1; shift ;;
        --no-verify-tag) VERIFY_TAG=0; shift ;;
        --*) echo "Unknown option $1. $usage" >&2; exit 2 ;;
        *) break ;;
    esac
done
KS="${1:?$usage}"
ALIAS="${2:-}"
if [ -z "$EXPECT" ] && [ "$NO_EXPECT" = 0 ]; then
    echo "Give --expect <SHA-256 from the CI run of the release tag>, or --no-expect. $usage" >&2
    exit 2
fi
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
for tool in git unzip sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Missing: $tool" >&2; exit 1; }
done

[ -f "$IN" ] || { echo "Missing: $IN (run make build first)" >&2; exit 1; }

# 1. The independent build.
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
    echo "!! --no-expect: $IN ($got) wasn't compared with an independent build." >&2
    echo "!! See \"Build integrity\" in README.md." >&2
fi

# 2. This clone: clean, at a release tag, signed by you. The apk files themselves are ignored by git.
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
if [ -n "$(git -C "$REPO" status --porcelain --untracked-files=no)" ]; then
    echo "!! $REPO has uncommitted changes (git status). Sign from a clean clone of the release tag." >&2
    exit 1
fi
HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
TAG="$(git -C "$REPO" describe --exact-match --tags --match 'v[0-9]*' HEAD 2>/dev/null)" || {
    echo "!! HEAD ($HEAD_SHA) isn't a vX.Y.Z tag. Check out the release tag first." >&2
    exit 1
}
if [ "$VERIFY_TAG" = 1 ]; then
    if ! git -C "$REPO" verify-tag "$TAG" >/dev/null 2>&1; then
        echo "!! git verify-tag $TAG failed: the tag isn't signed, or not by a key git trusts here." >&2
        echo "!! Create release tags with git tag -s outside the container (see README.md), or pass --no-verify-tag." >&2
        exit 1
    fi
    echo ">> $TAG is signed: $(git -C "$REPO" verify-tag "$TAG" 2>&1 | grep -m1 -i 'good' || echo 'git verify-tag OK')"
else
    echo "!! --no-verify-tag: the signature on $TAG wasn't checked." >&2
fi

# 3. The APK was built from this commit.
built="$(unzip -p "$IN" META-INF/version-control-info.textproto 2>/dev/null | sed -n 's/^ *revision: "\([0-9a-f]*\)".*/\1/p')"
if [ "$built" != "$HEAD_SHA" ]; then
    echo "!! $IN was built from commit ${built:-(unknown)}, but $TAG is $HEAD_SHA. Not signing." >&2
    exit 1
fi
echo ">> $IN was built from $TAG ($HEAD_SHA)"

# 4. Permissions.
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
