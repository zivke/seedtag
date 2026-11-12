#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
# Creates, signs, verifies and pushes a release tag, showing everything first and asking before each
# step that changes anything. Run it on the HOST, in the release clone (~/seedtag-release), never in the
# dev container or in the folder it mounts (README, "Signing a release").
#
#   tools/release-tag.sh [--dry-run] [--commit <rev>] [--remote <name>]
#
#   --commit   what to tag (default: <remote>/main). The version comes from versionName in that commit.
#   --remote   default origin
#   --dry-run  all the checks and the review, then stop before creating the tag
#
# It only fetches: the working tree isn't touched until the end (an optional checkout of the new tag for
# sign-release.sh). So the copy of this script that runs is the one already checked out here, from the
# last release, and a new version of it shows up in the review instead of running unreviewed.
#
# Steps:
#  1. This clone: not a container, clean, signing set up (gpg.format ssh, a signing key that isn't in the
#     SSH agent, the key in gpg.ssh.allowedSignersFile).
#  2. Fetch. Show the commit to tag, its version and the previous release; refuse if the tag exists, the
#     commit isn't on <remote>/main, the changelog has no section for the version, or versionCode didn't go up.
#  3. Review, each part on request in your pager: the commits, the changelog section, the build and release
#     files, the whole diff.
#  4. Type the tag name to create the signed tag; git verify-tag must accept it with your key.
#  5. Confirm to push the tag (only the tag); otherwise offer to delete it again.
#  6. Optionally check out the tag; print the next steps (CI, sign-release.sh).
set -eu

usage="usage: tools/release-tag.sh [--dry-run] [--commit <rev>] [--remote <name>]"
DRY=0
REV=""
REMOTE=origin
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY=1; shift ;;
        --commit) REV="${2:?--commit needs a revision}"; shift 2 ;;
        --remote) REMOTE="${2:?--remote needs a name}"; shift 2 ;;
        -h|--help) sed -n '4,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown argument $1. $usage" >&2; exit 2 ;;
    esac
done

if [ -t 1 ]; then B="$(printf '\033[1m')"; R="$(printf '\033[31m')"; G="$(printf '\033[32m')"; Y="$(printf '\033[33m')"; N="$(printf '\033[0m')"
else B=""; R=""; G=""; Y=""; N=""; fi
say() { printf '%s\n' "$*"; }
ok() { printf '%s✓%s %s\n' "$G" "$N" "$*"; }
warn() { printf '%s!%s %s\n' "$Y" "$N" "$*"; }
die() { printf '%s✗ %s%s\n' "$R" "$*" "$N" >&2; exit 1; }
step() { printf '\n%s== %s%s\n' "$B" "$*" "$N"; }
# The answer has to be typed: y or yes.
ask() {
    printf '%s%s%s [y/N] ' "$B" "$1" "$N"
    read -r answer || answer=""
    case "$answer" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}
must() { ask "$1" || die "Stopped. Nothing was changed${2:-}."; }

# ---- 1. this clone ---------------------------------------------------------------------------------
step "1. This clone"
if [ -f /.dockerenv ] || [ -f /run/.containerenv ] || [ -n "${REMOTE_CONTAINERS:-}${CODESPACES:-}${DEVCONTAINER:-}" ]; then
    die "This looks like a container. Run it on the host, in the release clone (README, \"Signing a release\")."
fi
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || die "Not in a git repository."
cd "$ROOT"
case "$ROOT" in /workspaces/*) die "$ROOT is a dev container path. Use the separate release clone." ;; esac
[ -f tools/sign-release.sh ] && [ -f app/build.gradle.kts ] || die "$ROOT isn't a SeedTag clone."
git --version | awk '{ split($3, v, "."); exit !(v[1] > 2 || (v[1] == 2 && v[2] >= 34)) }' \
    || die "git 2.34 or later is needed for SSH signatures ($(git --version))."
say "Clone:          $ROOT"
say "Checked out:    $(git log -1 --format='%h %s' HEAD) (this script is from here)"
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "Uncommitted changes (git status). The release clone must be clean."
hooks="$(ls .git/hooks 2>/dev/null | grep -v '\.sample$' || true)"
[ -z "$hooks" ] || warn "Hooks in .git/hooks: $hooks. Check them: a fresh clone has none."
[ -z "$(git config core.hooksPath || true)" ] || warn "core.hooksPath is set: $(git config core.hooksPath)"

[ "$(git config gpg.format || true)" = ssh ] || die "gpg.format isn't ssh here: git config gpg.format ssh"
sk="$(git config user.signingkey || true)"
[ -n "$sk" ] || die "No user.signingkey: git config user.signingkey ~/.ssh/seedtag_signing.pub"
case "$sk" in
    key::*) pub="${sk#key::}" ;;
    *) f="$(printf '%s' "$sk" | sed "s|^~/|$HOME/|")"; [ -f "$f" ] || die "user.signingkey file $f doesn't exist."; pub="$(cat "$f")" ;;
esac
material="$(printf '%s\n' "$pub" | awk '{ print $2 }')"
[ -n "$material" ] || die "Can't read the public key in user.signingkey."
fp="$(printf '%s\n' "$pub" | ssh-keygen -lf - 2>/dev/null | awk '{ print $2 }')" || fp=""
[ -n "$fp" ] || die "ssh-keygen can't read the public key in user.signingkey."
say "Signing key:    $fp ($sk)"
if ssh-add -L 2>/dev/null | awk '{ print $2 }' | grep -qxF "$material"; then
    die "The signing key is loaded in the SSH agent, which VS Code forwards into the dev container: the
  container could sign tags with it. Remove it (ssh-add -d $sk) and let git read the key file."
fi
ok "The signing key isn't in the SSH agent."
asf="$(git config gpg.ssh.allowedSignersFile || true)"
[ -n "$asf" ] || die "No gpg.ssh.allowedSignersFile: git verify-tag (and sign-release.sh) can't check the tag."
asf="$(printf '%s' "$asf" | sed "s|^~/|$HOME/|")"
grep -qF "$material" "$asf" 2>/dev/null || die "$asf doesn't list the signing key: git verify-tag would reject the tag."
ok "$asf lists it."

# ---- 2. what to tag ----------------------------------------------------------------------------------
step "2. What to tag"
url="$(git remote get-url "$REMOTE")" || die "No remote $REMOTE."
say "Fetching $REMOTE ($url)…"
git fetch --quiet --tags "$REMOTE"
REV="${REV:-$REMOTE/main}"
sha="$(git rev-parse --verify --quiet "$REV^{commit}")" || die "$REV isn't a commit."
git merge-base --is-ancestor "$sha" "$REMOTE/main" || die "$sha isn't on $REMOTE/main. Push main first (from VS Code)."
ver="$(git show "$sha:app/build.gradle.kts" | sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p')"
code="$(git show "$sha:app/build.gradle.kts" | sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p')"
printf '%s\n' "$ver" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || die "versionName at $sha is '$ver', not X.Y.Z."
TAG="v$ver"
prev="$(git tag --list 'v[0-9]*' --sort=-v:refname --merged "$sha" | head -n 1)"
[ -n "$prev" ] || die "No earlier v* tag in the history of $sha."
say "Commit:         $(git log -1 --format='%H%n                %s (%an, %ad)' --date=short "$sha")"
say "Version:        $ver (versionCode $code) → tag $B$TAG$N"
say "Previous:       $prev ($(git log -1 --format=%h "$prev"), $(git log -1 --format=%ad --date=short "$prev"))"
if git rev-parse --verify --quiet "refs/tags/$TAG" >/dev/null; then die "$TAG already exists here."; fi
if [ -n "$(git ls-remote --tags "$REMOTE" "refs/tags/$TAG")" ]; then die "$TAG already exists on $REMOTE."; fi
pcode="$(git show "$prev:app/build.gradle.kts" | sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p')"
[ "$code" -gt "$pcode" ] || die "versionCode $code isn't higher than $prev's ($pcode)."
section="$(git show "$sha:changelog.md" | awk -v v="$ver" '
    index($0, "## [" v "] - ") == 1 { f = 1; print; next }
    f && /^## \[/ { exit }
    f')"
[ -n "$section" ] || die "changelog.md at $sha has no '## [$ver] - YYYY-MM-DD' section."
ok "$TAG doesn't exist yet; versionCode $pcode → $code; changelog has [$ver]."
here="$(git rev-parse HEAD)"
ahead="$(git rev-list --count "$sha..$REMOTE/main")"
[ "$ahead" = 0 ] || warn "$REMOTE/main has $ahead commit(s) after $(git log -1 --format=%h "$sha"); they won't be in $TAG."
if git verify-tag "$prev" >/dev/null 2>&1; then ok "$prev is signed."; else warn "$prev isn't signed (or not by a key in $asf); only informational."; fi
if ! git diff --quiet "$here" "$sha" -- tools/release-tag.sh; then
    warn "tools/release-tag.sh differs between what's checked out and $TAG. This run uses the checked-out one;
  review the new one in step 3."
fi

# ---- 3. review ---------------------------------------------------------------------------------------
step "3. Review $prev..$TAG"
say "$(git rev-list --count "$prev..$sha") commits,$(git diff --shortstat "$prev" "$sha")."
REVIEW="tools .github Makefile build.gradle.kts settings.gradle.kts gradle app/build.gradle.kts app/proguard-rules.pro
    app/src/main/AndroidManifest.xml core/build.gradle.kts .devcontainer"
if ask "Show the commits?"; then git log --stat --format='%n%C(yellow)%h%Creset %s%n%an, %ad' --date=short "$prev..$sha"; fi
if ask "Show the changelog section for $ver?"; then printf '%s\n' "$section" | ${PAGER:-less -R}; fi
say "Build, checks and release files changed since $prev:"
# shellcheck disable=SC2086
git diff --stat "$prev" "$sha" -- $REVIEW | sed 's/^/  /'
# shellcheck disable=SC2086
if ask "Show those diffs (what builds, checks and signs the release)?"; then git diff "$prev" "$sha" -- $REVIEW; fi
if ask "Show the whole diff?"; then git diff "$prev" "$sha"; fi
must "Reviewed: is everything from $prev to $(git log -1 --format=%h "$sha") what you meant to release?"

# ---- 4. tag ------------------------------------------------------------------------------------------
step "4. Create the signed tag"
msg="SeedTag $ver"
say "Will run:  git tag -s -m \"$msg\" $TAG $sha"
say "Signed with $fp; git may ask for the key's passphrase."
if [ "$DRY" = 1 ]; then ok "--dry-run: stopping here. Nothing was changed."; exit 0; fi
printf '%sType %s to create it (anything else stops):%s ' "$B" "$TAG" "$N"
read -r typed || typed=""
[ "$typed" = "$TAG" ] || die "Stopped. Nothing was changed."
git tag -s -m "$msg" "$TAG" "$sha"
vt="$(git verify-tag "$TAG" 2>&1)" || { git tag -d "$TAG" >/dev/null; die "git verify-tag rejected $TAG (deleted it again):
$vt"; }
printf '%s\n' "$vt" | grep -qF "$fp" || { git tag -d "$TAG" >/dev/null; die "$TAG isn't signed with $fp (deleted it again):
$vt"; }
[ "$(git rev-parse "$TAG^{commit}")" = "$sha" ] || { git tag -d "$TAG" >/dev/null; die "$TAG doesn't point at $sha (deleted it again)."; }
ok "$(printf '%s\n' "$vt" | grep -m1 -i 'good' || echo 'git verify-tag OK')"
say "  $TAG → $(git log -1 --format='%h %s' "$sha")"

# ---- 5. push -----------------------------------------------------------------------------------------
step "5. Push the tag"
say "Will run:  git push $REMOTE refs/tags/$TAG   (only the tag; it starts the CI build)"
if ! ask "Push $TAG to $REMOTE ($url)?"; then
    if ask "Delete the local tag $TAG again?"; then git tag -d "$TAG"; else say "Kept $TAG locally. Push it later: git push $REMOTE refs/tags/$TAG"; fi
    exit 0
fi
git push "$REMOTE" "refs/tags/$TAG"
[ "$(git ls-remote --tags "$REMOTE" "refs/tags/$TAG" | cut -f1)" = "$(git rev-parse "$TAG")" ] \
    || die "$REMOTE doesn't show $TAG as pushed. Check with: git ls-remote --tags $REMOTE"
ok "$TAG is on $REMOTE."

# ---- 6. next -----------------------------------------------------------------------------------------
step "6. Next"
if ask "Check out $TAG here now, for sign-release.sh?"; then git -c advice.detachedHead=false checkout --quiet "$TAG"; ok "HEAD is $TAG."; fi
repo="$(printf '%s\n' "$url" | sed -nE 's#^(https://github\.com/|git@github\.com:|ssh://git@github\.com/)([^/]+/[^/]+)/?$#\2#p' | sed 's/\.git$//')"
[ -n "$repo" ] && say "CI run:  https://github.com/$repo/actions (wait for it, copy the SHA-256 from its summary)"
say "Then, with the run's seedtag-unsigned.apk in $ROOT:"
say "  git checkout $TAG   # if not done above"
say "  tools/sign-release.sh --expect <SHA-256 from the CI run> /path/to/your-key.jks"
