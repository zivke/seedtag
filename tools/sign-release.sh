#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
# Signs the release APK with the release key. Run it OUTSIDE the dev container, on the machine
# that holds the key, from a fresh clone checked out at the release tag: the key and its password
# never enter the container, and the script that handles them isn't one the container could edit.
#
#   tools/sign-release.sh (--expect <sha256> | --no-expect) [--no-verify-tag] [--no-remote-check]
#                         <keystore> [key alias]
#
# Before anything touches the key, it checks:
# - --expect: seedtag-unsigned.apk has this SHA-256, the one the GitHub Actions run of the release
#   tag shows (or `make build` in a fresh clone on another machine). So a build tampered with in the
#   dev container isn't signed. It's required; --no-expect skips it, with a warning.
# - The clone has no uncommitted changes, and HEAD is exactly the tag v<versionName>. The tag is
#   signed with the key in this clone's user.signingkey (compared by fingerprint, not just "a key
#   git verify-tag accepts"), made outside the container. --no-verify-tag skips the signature
#   check, with a warning, e.g. for a first try.
# - The tag on origin (REMOTE=… for another remote) is still this one, so CI built what you tagged
#   and nothing moved it since. --no-remote-check skips that, with a warning, e.g. offline.
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

usage="usage: tools/sign-release.sh (--expect <sha256> | --no-expect) [--no-verify-tag] [--no-remote-check] <keystore> [key alias]"
EXPECT=""
NO_EXPECT=0
VERIFY_TAG=1
REMOTE_CHECK=1
REMOTE="${REMOTE:-origin}"
while [ $# -gt 0 ]; do
    case "$1" in
        --expect) EXPECT="$(printf '%s' "${2:?--expect needs the SHA-256 of an independent build}" | tr 'A-F' 'a-f')"; shift 2 ;;
        --no-expect) NO_EXPECT=1; shift ;;
        --no-verify-tag) VERIFY_TAG=0; shift ;;
        --no-remote-check) REMOTE_CHECK=0; shift ;;
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
# Nothing from the environment decides which repository or config git uses; no hooks, no fsmonitor.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES \
    GIT_NAMESPACE GIT_CEILING_DIRECTORIES GIT_CONFIG GIT_CONFIG_PARAMETERS GIT_CONFIG_COUNT
git() { command git -c core.hooksPath=/dev/null -c core.fsmonitor=false "$@"; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BUILD_TOOLS="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)}"
BUILD_TOOLS="${BUILD_TOOLS%/}"
if [ -x "$BUILD_TOOLS/apksigner" ]; then APKSIGNER="$BUILD_TOOLS/apksigner"
elif command -v apksigner >/dev/null 2>&1; then APKSIGNER="$(command -v apksigner)"
else echo "Missing: apksigner (set ANDROID_HOME, or put the Android build-tools on PATH)" >&2; exit 1
fi
for tool in git unzip sha256sum ssh-keygen; do
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
# The tag is v<versionName> of this commit, not whatever v* tag happens to point here too.
ver="$(git -C "$REPO" show HEAD:app/build.gradle.kts | sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([0-9]*\.[0-9]*\.[0-9]*\)"[[:space:]]*$/\1/p')"
[ "$(printf '%s\n' "$ver" | grep -c .)" = 1 ] || {
    echo "!! Can't read one versionName X.Y.Z from app/build.gradle.kts at HEAD." >&2
    exit 1
}
TAG="v$ver"
if [ "$(git -C "$REPO" rev-parse --verify --quiet "refs/tags/$TAG^{commit}")" != "$HEAD_SHA" ]; then
    echo "!! HEAD ($HEAD_SHA, versionName $ver) isn't the tag $TAG. Check out the release tag first." >&2
    exit 1
fi
if [ "$VERIFY_TAG" = 1 ]; then
    # Signed with the key in user.signingkey: its fingerprint, not just any key in allowed_signers.
    sk="$(git -C "$REPO" config --path user.signingkey || true)"
    case "$sk" in
        key::*) pub="${sk#key::}" ;;
        ssh-*|ecdsa-*|sk-*) pub="$sk" ;;
        "") pub="" ;;
        *) pub="$(head -n 1 "$sk" 2>/dev/null || true)" ;;
    esac
    fp="$(printf '%s\n' "$pub" | ssh-keygen -lf - 2>/dev/null | awk '{ print $2 }')" || fp=""
    if [ -z "$fp" ]; then
        echo "!! No release signing key: set user.signingkey in $REPO to its public key (README," >&2
        echo "!! \"Signing a release\"), or pass --no-verify-tag." >&2
        exit 1
    fi
    vt="$(git -C "$REPO" verify-tag "refs/tags/$TAG" 2>&1 | LC_ALL=C tr -d '\000-\010\013-\037\177')" || true
    name="$(git -C "$REPO" cat-file tag "refs/tags/$TAG" 2>/dev/null | sed -n 's/^tag //p' | head -n 1)"
    if [ "$name" != "$TAG" ] || ! printf '%s\n' "$vt" | awk -v f="$fp" '/^Good "git" signature/ && $NF == f { g = 1 } END { exit !g }'; then
        echo "!! $TAG isn't a tag signed with the release signing key $fp:" >&2
        printf '%s\n' "$vt" | sed 's/^/!!   /' >&2
        echo "!! Create release tags with tools/release-tag.sh outside the container (README.md), or pass --no-verify-tag." >&2
        exit 1
    fi
    echo ">> $TAG is signed with $fp"
else
    echo "!! --no-verify-tag: the signature on $TAG wasn't checked." >&2
fi
if [ "$REMOTE_CHECK" = 1 ]; then
    there="$(git -C "$REPO" ls-remote --tags "$REMOTE" "refs/tags/$TAG")" || {
        echo "!! Can't reach $REMOTE to check its $TAG; pass --no-remote-check to skip that." >&2
        exit 1
    }
    there="$(printf '%s\n' "$there" | awk -v r="refs/tags/$TAG" '$2 == r { print $1 }')"
    if [ "$there" != "$(git -C "$REPO" rev-parse "refs/tags/$TAG")" ]; then
        echo "!! $TAG on $REMOTE is ${there:-missing}, not the tag here: something moved or deleted it, so" >&2
        echo "!! the CI run may have built other code. Not signing." >&2
        exit 1
    fi
    echo ">> $TAG on $REMOTE is this tag"
else
    echo "!! --no-remote-check: $TAG on $REMOTE wasn't compared with this one." >&2
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
