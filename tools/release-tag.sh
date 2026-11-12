#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
# Creates, signs, verifies and pushes a release tag, showing everything first and asking before each
# step that changes anything. Run it on the HOST, in the release clone (~/seedtag-release), never in the
# dev container or in the folder it mounts (README, "Signing a release").
#
#   tools/release-tag.sh [--dry-run] [--commit <rev>] [--remote <name>] [--bootstrap <vX.Y.Z>]
#
#   --commit     what to tag (default: <remote>/main). The version comes from versionName in that commit.
#   --remote     default origin
#   --dry-run    all the checks and the review, then stop before creating the tag
#   --bootstrap  only for the first signed release: review against <vX.Y.Z> although it isn't signed with
#                your release key, and run although the checkout isn't such a tag (read this script first).
#                You confirm that tag's commit from evidence of your own, e.g. the APK you signed.
#
# Everything on GitHub is treated as untrusted (the dev container can push as you), so:
# - It fetches only main, never tags. The previous release, the start of the review, is the newest vX.Y.Z
#   tag in the history of the commit that is signed with your release key; others are listed and ignored.
# - "Signed" means signed with the key in user.signingkey (its fingerprint), with the tag's own name and
#   the message "SeedTag X.Y.Z" inside, and gpg.ssh.allowedSignersFile must list only that key, so the
#   agent check covers every key git verify-tag would accept.
# - The script that runs is the one checked out here, and that must be a release tag signed with your key:
#   a new version of this script shows up in the review instead of running unreviewed. Never pull main here.
# - Commit messages and diffs are shown with control characters removed, so they can't redraw the terminal.
# - git runs without hooks or fsmonitor, and refs are always given in full (refs/tags/…, refs/remotes/…).
#
# Steps:
#  1. This clone: not a container, clean, checked out at a signed release, signing set up (gpg.format ssh,
#     a signing key that isn't in the SSH agent and is the only key in gpg.ssh.allowedSignersFile).
#  2. Fetch main. Show the commit to tag, its version and the previous release; refuse if the tag exists,
#     the commit isn't on <remote>/main, the version isn't newer, the changelog has no section for it,
#     versionCode didn't go up, or a .gitattributes in it sets a filter.
#  3. Review, each part on request in your pager: the commits, the changelog section, everything except the
#     app's source code and docs, the whole diff. Refuses undiffable (binary) changes except art/*.png and
#     the Gradle wrapper jar; warns about bidirectional-text and other control characters.
#  4. Type the tag name to create the signed tag; it must verify with your key.
#  5. Confirm to push the tag (only the tag); otherwise offer to delete it again.
#  6. Optionally check out the tag; print the next steps (CI run of exactly this commit, sign-release.sh).
set -eu

# Nothing from the environment decides which repository or config git uses.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES \
    GIT_COMMON_DIR GIT_ATTR_SOURCE GIT_NAMESPACE GIT_CEILING_DIRECTORIES GIT_CONFIG GIT_CONFIG_PARAMETERS GIT_CONFIG_COUNT \
    GIT_EXTERNAL_DIFF GIT_DIFF_OPTS
# No hooks (fetch, tag, push and checkout would run them) and no fsmonitor program.
git() { command git -c core.hooksPath=/dev/null -c core.fsmonitor=false "$@"; }

HERE="$(cd "$(dirname "$0")" && pwd -P)"
usage="usage: tools/release-tag.sh [--dry-run] [--commit <rev>] [--remote <name>] [--bootstrap <vX.Y.Z>]"
DRY=0
REV=""
REMOTE=origin
BOOT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY=1; shift ;;
        --commit) REV="${2:?--commit needs a revision}"; shift 2 ;;
        --remote) REMOTE="${2:?--remote needs a name}"; shift 2 ;;
        --bootstrap) BOOT="${2:?--bootstrap needs the previous release tag, vX.Y.Z}"; shift 2 ;;
        -h|--help) awk 'NR > 3 && /^#/ { sub(/^# ?/, ""); print; next } NR > 3 { exit }' "$0"; exit 0 ;;
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
# Text from the repository (commit messages, names, diffs) goes through this before it reaches the
# terminal: no escape sequences, carriage returns or other control characters except tab and newline
# (C1 controls in UTF-8 too, U+0080-U+009F), and bidirectional-text controls shown as <bidi>.
clean() {
    LC_ALL=C tr -d '\000-\010\013-\037\177' \
        | LC_ALL=C awk '{ gsub(/\302[\200-\237]/, ""); gsub(/\342\200[\252-\256]|\342\201[\246-\251]/, "<bidi>"); print }'
}
is_release() { printf '%s\n' "$1" | grep -qxE 'v[0-9]+\.[0-9]+\.[0-9]+'; }
short() { git rev-parse --short "$1"; }
subject() { git log -1 --no-color --format="${2:-%h %s}" --date=short "$1" | clean; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
interrupted() { printf '\n%s\n' "${R}✗ Interrupted. Steps above that say they changed something did; nothing else was.$N" >&2; exit 130; }
trap interrupted INT TERM HUP
PG="$(git var GIT_PAGER 2>/dev/null || echo less)"
# page <command…>: runs it into a file (so a failure is noticed, and quitting the pager early doesn't kill
# the script), then shows it cleaned in your pager.
page() {
    "$@" > "$TMP/page" || die "Failed: $*"
    trap '' INT
    clean < "$TMP/page" | LESS="${LESS-FRX}" sh -c "$PG" || true
    trap interrupted INT
}

# ---- 1. this clone ---------------------------------------------------------------------------------
step "1. This clone"
[ -t 0 ] || die "Run it in a terminal: it asks before every step."
[ "$(printf 'a\342\200\256\302\233b\n' | clean)" = "a<bidi>b" ] \
    || die "This system's awk or tr can't filter bytes as needed ($(command -v awk)); try another awk on PATH."
if [ -f /.dockerenv ] || [ -f /run/.containerenv ] || [ -n "${REMOTE_CONTAINERS:-}${CODESPACES:-}${DEVCONTAINER:-}" ]; then
    die "This looks like a container. Run it on the host, in the release clone (README, \"Signing a release\")."
fi
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || die "Not in a git repository."
cd "$ROOT"
case "$ROOT" in /workspaces/*) die "$ROOT is a dev container path. Use the separate release clone." ;; esac
[ "$HERE" = "$(cd "$ROOT/tools" 2>/dev/null && pwd -P)" ] \
    || die "This is $HERE/release-tag.sh, not the copy in this clone. Run tools/release-tag.sh from $ROOT."
[ -f tools/sign-release.sh ] && [ -f app/build.gradle.kts ] || die "$ROOT isn't a SeedTag clone."
git --version | awk '{ split($3, v, "."); exit !(v[1] > 2 || (v[1] == 2 && v[2] >= 34)) }' \
    || die "git 2.34 or later is needed for SSH signatures ($(git --version))."
[ -n "$BOOT" ] && ! is_release "$BOOT" && die "--bootstrap needs a vX.Y.Z tag, not $BOOT."
say "Clone:          $ROOT"
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "Uncommitted changes (git status). The release clone must be clean."
hooks="$(ls "$(git rev-parse --git-common-dir)/hooks" 2>/dev/null | grep -v '\.sample$' || true)"
[ -z "$hooks" ] || warn "Hooks in .git/hooks: $(echo $hooks). They don't run here, but a fresh clone has none: check them."
command git config --get-regexp '^(core\.hookspath|core\.sshcommand|gpg\.ssh\.program|gpg\.program|remote\..*\.pushurl|url\..*\.(push)?insteadof)$' \
    | while IFS= read -r c; do warn "Set in git config: $c"; done

[ "$(git config gpg.format || true)" = ssh ] || die "gpg.format isn't ssh here: git config gpg.format ssh"
sk="$(git config --path user.signingkey || true)"
case "$sk" in
    "") die "No user.signingkey: git config user.signingkey \"\$HOME/.ssh/seedtag_signing.pub\"" ;;
    key::*|ssh-*|ecdsa-*|sk-*) die "user.signingkey is a literal key: git can sign with that only through the SSH
  agent, which must not hold it. Set it to the path of the .pub file next to the private key." ;;
    *) [ -f "$sk" ] || die "user.signingkey file $sk doesn't exist."; pub="$(head -n 1 "$sk")" ;;
esac
case "$pub" in ssh-*|ecdsa-*|sk-*) ;; *) die "user.signingkey must be the public key (the .pub file), not $sk." ;; esac
material="$(printf '%s\n' "$pub" | awk '{ print $2 }')"
fp="$(printf '%s\n' "$pub" | ssh-keygen -lf - 2>/dev/null | awk '{ print $2 }')" || fp=""
[ -n "$material" ] && [ -n "$fp" ] || die "ssh-keygen can't read the public key in user.signingkey."
say "Signing key:    $fp ($sk)"
agent="$(ssh-add -L 2>/dev/null)" && st=0 || st=$?
case "$st" in
    0) if printf '%s\n' "$agent" | awk '{ print $2 }' | grep -qxF "$material"; then
           die "The signing key is loaded in the SSH agent, which VS Code forwards into the dev container: the
  container could sign tags with it. Remove it (ssh-add -d $sk) and let git read the key file."
       fi
       ok "The signing key isn't among the $(printf '%s\n' "$agent" | wc -l | tr -d ' ') key(s) in the SSH agent (${SSH_AUTH_SOCK:-})." ;;
    1) ok "The SSH agent (${SSH_AUTH_SOCK:-}) holds no keys." ;;
    *) warn "No SSH agent reachable from this shell (SSH_AUTH_SOCK=${SSH_AUTH_SOCK:-unset}), so the one VS Code
  forwards can't be checked from here. In a terminal of the desktop session: ssh-add -l must not list $fp." ;;
esac
asf="$(git config --path gpg.ssh.allowedSignersFile || true)"
[ -n "$asf" ] || die "No gpg.ssh.allowedSignersFile: git verify-tag (and sign-release.sh) can't check the tag."
[ -f "$asf" ] || die "gpg.ssh.allowedSignersFile $asf doesn't exist."
# Every entry must be the signing key: git verify-tag accepts any key in the file.
signers="$(awk -v m="$material" '
    { sub(/\r$/, "") }
    /^[[:space:]]*(#|$)/ { next }
    { k = ""; for (i = 1; i < NF; i++) if ($i ~ /^(ssh-|ecdsa-|sk-)/) { k = $(i + 1); break }
      if (k == m) good++; else bad = bad " " NR }
    END { printf "%d%s\n", good, bad }' "$asf")"
good="${signers%% *}"; bad="${signers#"$good"}"
[ "$good" -gt 0 ] || die "$asf doesn't list the signing key$([ -z "$bad" ] || printf ', only other keys (line(s)%s)' "$bad"):
  git verify-tag would reject the tag."
[ -z "$bad" ] || die "$asf also lists other keys (line(s)$bad). git verify-tag accepts any of them, so use a
  file that lists only the release signing key, set in this clone (README, \"Signing a release\")."

ok "$asf lists only this key."

# signed_release <tag>: a vX.Y.Z tag object of that name, signed with the signing key.
signed_release() {
    is_release "$1" || return 1
    [ "$(git cat-file -t "refs/tags/$1" 2>/dev/null)" = tag ] || return 1
    git cat-file tag "refs/tags/$1" > "$TMP/tagobj" 2>/dev/null || return 1
    [ "$(sed -n 's/^tag //p' "$TMP/tagobj" | head -n 1)" = "$1" ] || return 1
    [ "$(sed -n '/^$/{n;p;q;}' "$TMP/tagobj")" = "SeedTag ${1#v}" ] || return 1
    git verify-tag "refs/tags/$1" 2>&1 | awk -v f="$fp" '/^Good "git" signature/ && $NF == f { g = 1 } END { exit !g }'
}
here="$(git rev-parse HEAD)"
head_tag=""
for t in $(git tag --points-at HEAD); do
    if signed_release "$t"; then head_tag="$t"; fi
done
if [ -n "$head_tag" ]; then
    ok "Checked out:    $head_tag, signed with your key: this script is from that release."
elif [ -n "$BOOT" ]; then
    warn "Checked out:    $(subject HEAD), not a release tag signed with your key (--bootstrap).
  This copy of the script is what runs next to your signing key: read it before going on."
    must "Have you read tools/release-tag.sh as checked out here?"
else
    die "Checked out:    $(subject HEAD), not a vX.Y.Z tag signed with $fp, so this copy of the
  script isn't from a reviewed release. Check out the last release (git checkout vX.Y.Z); for the first
  signed release, read the script and use --bootstrap <previous release tag>."
fi

# ---- 2. what to tag ----------------------------------------------------------------------------------
step "2. What to tag"
git check-ref-format "refs/remotes/$REMOTE/main" || die "Bad remote name $REMOTE."
url="$(git remote get-url "$REMOTE")" || die "No remote $REMOTE."
purl="$(git remote get-url --push "$REMOTE")"
main_ref="refs/remotes/$REMOTE/main"
old="$(git rev-parse --verify --quiet "$main_ref^{commit}" || true)"
say "Fetching main from $REMOTE ($url), no tags…"
git fetch --no-tags "$REMOTE" "+refs/heads/main:$main_ref"
new="$(git rev-parse --verify "$main_ref^{commit}")"
if [ -n "$old" ] && ! git merge-base --is-ancestor "$old" "$new"; then
    warn "$REMOTE/main was rewritten: it no longer contains $(short "$old"), which it had before this fetch.
  Unless you force-pushed main yourself, stop and find out why."
    must "Go on anyway?"
fi
REV="${REV:-$main_ref}"
sha="$(git rev-parse --verify --quiet "$REV^{commit}")" || die "$REV isn't a commit."
git merge-base --is-ancestor "$sha" "$main_ref" || die "$sha isn't on $REMOTE/main. Push main first (from VS Code)."

# gradle_value <commit> <name>: the value on the one line of app/build.gradle.kts that mentions <name>.
gradle_value() {
    git show "$1:app/build.gradle.kts" > "$TMP/gradle" || die "No app/build.gradle.kts at $(short "$1")."
    _n="$(grep -c "$2" "$TMP/gradle" || true)"
    [ "$_n" = 1 ] || die "app/build.gradle.kts at $(short "$1") mentions $2 on $_n lines, expected exactly one."
    sed -n "s/^[[:space:]]*$2[[:space:]]*=[[:space:]]*\"\{0,1\}\([0-9.]*\)\"\{0,1\}[[:space:]]*\$/\1/p" "$TMP/gradle"
}
ver="$(gradle_value "$sha" versionName)"
code="$(gradle_value "$sha" versionCode)"
is_release "v$ver" || die "versionName at $(short "$sha") isn't X.Y.Z (or not on a line of its own)."
case "$code" in ''|*[!0-9]*) die "versionCode at $(short "$sha") isn't a number (or not on a line of its own)." ;; esac
TAG="v$ver"

# The previous release: the newest vX.Y.Z tag in the history of $sha signed with the key (or --bootstrap).
prev=""; ignored=""; sprev=""
for t in $(git tag --list 'v[0-9]*' --sort=-v:refname --merged "$sha"); do
    if signed_release "$t"; then sprev="$t"; break; fi
    if [ -z "$prev" ]; then
        if [ "$t" = "$BOOT" ]; then prev="$t"; else ignored="$ignored $t"; fi
    fi
done
if [ -n "$BOOT" ]; then
    [ -z "$sprev" ] || die "$sprev is a release signed with your key: --bootstrap is only for the first signed release."
    [ -n "$prev" ] || die "--bootstrap $BOOT: no such tag in the history of $(short "$sha")."
else
    prev="$sprev"
    [ -n "$prev" ] || die "No vX.Y.Z tag signed with $fp in the history of $(short "$sha").
  For the first signed release, use --bootstrap <previous release tag>."
fi
prev_sha="$(git rev-parse "refs/tags/$prev^{commit}")"
say "Commit:         $(subject "$sha" '%H%n                %s (%an, %ad)')"
say "Version:        $ver (versionCode $code) → tag $B$TAG$N"
say "Previous:       $prev → $prev_sha ($(subject "$prev_sha" %ad))"
[ -z "$ignored" ] || warn "Ignored (not vX.Y.Z, or not signed with your key):$ignored"
if [ -n "$BOOT" ]; then
    warn "$prev isn't signed with your key (--bootstrap) and came from GitHub with the clone, so nothing here
  proves it's the release you made: GitHub's tag page and the commit date can be forged too. Check the commit
  against evidence the container couldn't change, e.g. the $prev APK you signed or installed:
    apksigner verify --print-certs <apk> | grep SHA-256      (your release certificate's digest)
    unzip -p <apk> META-INF/version-control-info.textproto   (revision: the commit)
  or the commit hash you noted when you released it."
    printf '%sIf your evidence shows %s, type its first 12 characters (anything else stops):%s ' "$B" "$prev_sha" "$N"
    read -r typed || typed=""
    [ "$typed" = "$(printf '%.12s' "$prev_sha")" ] || die "Stopped. Nothing was changed."
fi
awk -v a="$ver" -v b="${prev#v}" 'BEGIN { split(a, x, "."); split(b, y, ".")
    for (i = 1; i <= 3; i++) if (x[i] + 0 != y[i] + 0) exit !(x[i] + 0 > y[i] + 0); exit 1 }' \
    || die "$ver isn't newer than $prev."
if git rev-parse --verify --quiet "refs/tags/$TAG" >/dev/null; then
    die "A tag $TAG exists in this clone already. If you didn't create it here, something else did (it may
  have come with the clone): don't check it out or sign it. Delete it (git tag -d $TAG) and start again."
fi
# remote_tag <tag>: the object id of refs/tags/<tag> on the remote (exact match), empty if it has none.
# Use only in a plain assignment, so that its die stops the script.
remote_tag() {
    _o="$(git ls-remote --tags "$REMOTE" "refs/tags/$1")" || die "Can't reach $REMOTE (git ls-remote failed)."
    printf '%s\n' "$_o" | awk -v r="refs/tags/$1" '$2 == r { print $1 }'
}
there="$(remote_tag "$TAG")"
[ -z "$there" ] || die "$TAG already exists on $REMOTE. If you didn't push it from this clone, something else did: don't use it."

pcode="$(gradle_value "$prev_sha" versionCode)"
case "$pcode" in ''|*[!0-9]*) die "Can't read $prev's versionCode." ;; esac
[ "$code" -gt "$pcode" ] || die "versionCode $code isn't higher than $prev's ($pcode)."
section="$(git show "$sha:changelog.md" | awk -v v="$ver" '
    index($0, "## [" v "] - ") == 1 { f = 1; print; next }
    f && /^## \[/ { exit }
    f')"
[ -n "$section" ] || die "changelog.md at $(short "$sha") has no '## [$ver] - YYYY-MM-DD' section."
# A filter in .gitattributes would run a program on checkout (step 6, sign-release.sh's clone).
git ls-tree -r "$sha" > "$TMP/tree" || die "Can't list the files of $(short "$sha")."
awk -F'\t' '{ p = tolower($2); sub(/^"/, "", p); sub(/"$/, "", p)
    if (p ~ /(^|\/)\.gitattributes$/) { split($1, m, " "); print m[3] "\t" $2 } }' "$TMP/tree" > "$TMP/attrs"
while IFS="$(printf '\t')" read -r oid a; do
    git cat-file blob "$oid" > "$TMP/attr" || die "Can't read $(printf '%s' "$a" | clean) at $(short "$sha")."
    if grep -qiE '(^|[[:space:]])filter=' "$TMP/attr"; then
        die "$(printf '%s' "$a" | clean) at $(short "$sha") sets a filter (filter=…): checking it out would run that filter's program."
    fi
done < "$TMP/attrs"
ok "$TAG doesn't exist yet; $prev → $ver; versionCode $pcode → $code; changelog has [$ver]; no filters."
ahead="$(git rev-list --count "$sha..$main_ref")"
[ "$ahead" = 0 ] || warn "$REMOTE/main has $ahead commit(s) after $(short "$sha"); they won't be in $TAG."
if [ "$here" = "$sha" ]; then
    warn "What's checked out is the commit being released: this run uses the new script itself, unreviewed."
elif ! git diff --quiet "$here" "$sha" -- tools/release-tag.sh; then
    warn "tools/release-tag.sh changes in $TAG. This run uses the checked-out one; review the new one in step 3."
fi

# ---- 3. review ---------------------------------------------------------------------------------------
step "3. Review $prev..$TAG"
DIFF="--no-color --no-ext-diff --no-textconv"
# shellcheck disable=SC2086
git diff $DIFF --no-renames --numstat "$prev_sha" "$sha" > "$TMP/numstat"
bin="$(awk -F'\t' '$1 == "-" { print $3 }' "$TMP/numstat")"
badbin="$(printf '%s\n' "$bin" | grep -vxE '(art/[^/]+\.png|gradle/wrapper/gradle-wrapper\.jar)?' | clean || true)"
[ -z "$badbin" ] || die "No diff can show what changed in these files: they're binary, or marked so in
  .gitattributes (-diff, binary). Find out why first (git diff --text $prev $(short "$sha") -- <file>):
$(printf '%s\n' "$badbin" | sed 's/^/  /')"
[ -z "$bin" ] || warn "Binary changes, not shown as text: $(printf '%s\n' "$bin" | clean | tr '\n' ' ')"
# shellcheck disable=SC2086
git diff $DIFF "$prev_sha" "$sha" > "$TMP/diff"
bidi="$(LC_ALL=C awk '/^\+\+\+ / { f = substr($0, 5) } /^\+/ && /\342\200[\252-\256]|\342\201[\246-\251]/ { print f }' "$TMP/diff" | sort -u | clean)"
[ -z "$bidi" ] || warn "Bidirectional-text control characters (they can make code read differently than it runs) in:
$(printf '%s\n' "$bidi" | sed 's/^/  /')"
ctl="$(LC_ALL=C tr -cd '\000-\010\013\014\016-\037\177' < "$TMP/diff" | wc -c | tr -d ' ')"
[ "$ctl" = 0 ] || warn "The diff contains $ctl control character(s) (escape sequences or similar); they're removed when shown."

say "$(git rev-list --count "$prev_sha..$sha") commits,$(git diff --shortstat "$prev_sha" "$sha")."
# Everything but the app's source code and docs: what builds, checks, signs and configures. Tests stay in:
# CI runs them in the same tree before it builds the APK.
focus() {
    "$@" -- . ':(exclude)app/src/main/java' ':(exclude)core/src/main/kotlin' ':(exclude)art' ':(exclude)*.md'
}
unseen=""
# shellcheck disable=SC2086
if ask "Show the commits?"; then page git log $DIFF --stat --format='%n%h %s%n%an, %ad' --date=short "$prev_sha..$sha"
else unseen="$unseen, the commits"; fi
if ask "Show the changelog section for $ver?"; then page printf '%s\n' "$section"
else unseen="$unseen, the changelog"; fi
say "Changed since $prev, except the app's source code and docs (app/src/main/java, core/src/main/kotlin, art/, *.md):"
# shellcheck disable=SC2086
focus git diff $DIFF --stat "$prev_sha" "$sha" > "$TMP/stat" || die "git diff --stat failed."
clean < "$TMP/stat" | sed 's/^/  /'
# shellcheck disable=SC2086
if ask "Show those diffs (what builds, checks, signs and configures the release)?"; then page focus git diff $DIFF "$prev_sha" "$sha"
else unseen="$unseen, the build and release diffs"; fi
if ask "Show the whole diff?"; then page cat "$TMP/diff"
else unseen="$unseen, the whole diff"; fi
if [ -z "$unseen" ]; then
    must "Reviewed: is everything from $prev to $(short "$sha") what you meant to release?"
else
    warn "You didn't view: ${unseen#, }."
    printf '%sType reviewed if everything from %s to %s is what you meant to release (anything else stops):%s ' \
        "$B" "$prev" "$(short "$sha")" "$N"
    read -r typed || typed=""
    [ "$typed" = reviewed ] || die "Stopped. Nothing was changed."
fi

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
vt="$(git verify-tag "refs/tags/$TAG" 2>&1 | clean)" || true
signed_release "$TAG" || { git tag -d "$TAG" >/dev/null; die "$TAG doesn't verify with $fp (deleted it again):
$vt"; }
[ "$(git rev-parse "refs/tags/$TAG^{commit}")" = "$sha" ] || { git tag -d "$TAG" >/dev/null; die "$TAG doesn't point at $sha (deleted it again)."; }
ok "$(printf '%s\n' "$vt" | grep -m1 '^Good' || echo "$TAG verifies with $fp")"
say "  $TAG → $(subject "$sha")"

# ---- 5. push -----------------------------------------------------------------------------------------
step "5. Push the tag"
say "Will run:  git push $REMOTE refs/tags/$TAG   (only the tag, to $purl; it starts the CI build)"
if ! ask "Push $TAG to $REMOTE ($purl)?"; then
    if ask "Delete the local tag $TAG again?"; then git tag -d "$TAG"; else say "Kept $TAG locally. Push it later: git push $REMOTE refs/tags/$TAG"; fi
    exit 0
fi
git push "$REMOTE" "refs/tags/$TAG:refs/tags/$TAG" \
    || die "The push failed; $TAG is still in this clone. Retry: git push $REMOTE refs/tags/$TAG, or delete it: git tag -d $TAG"
there="$(remote_tag "$TAG")"
[ "$there" = "$(git rev-parse "refs/tags/$TAG")" ] \
    || die "$REMOTE doesn't show $TAG as pushed. Check with: git ls-remote --tags $REMOTE"
ok "$TAG is on $REMOTE."

# ---- 6. next -----------------------------------------------------------------------------------------
step "6. Next"
if ask "Check out $TAG here now, for sign-release.sh?"; then
    GIT_LFS_SKIP_SMUDGE=1 git -c filter.lfs.smudge= -c filter.lfs.process= -c filter.lfs.required=false \
        -c advice.detachedHead=false checkout --quiet "refs/tags/$TAG"
    ok "HEAD is $TAG."
fi
repo="$(printf '%s\n' "$url" | sed -nE 's#^(https://github\.com/|git@github\.com:|ssh://git@github\.com/)([^/]+/[^/]+)/?$#\2#p' | sed 's/\.git$//')"
[ -n "$repo" ] && say "CI:  https://github.com/$repo/actions"
say "Wait for the run of $TAG. Use it only if GitHub's own run page (not the summary text, which the
build writes) shows the event as a push of $TAG and the commit as $(short "$sha"), that is
  $sha
If there are several runs for $TAG, stop: something re-ran or moved it. Copy the SHA-256 from the
run's summary and download its seedtag-unsigned.apk into $ROOT. Then:"
say "  git checkout $TAG   # if not done above"
say "  tools/sign-release.sh --expect <SHA-256 from the CI run> /path/to/your-key.jks"
