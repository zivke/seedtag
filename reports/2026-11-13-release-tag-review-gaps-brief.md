# Task brief: harden `tools/release-tag.sh`

A security review of `tools/release-tag.sh` found five gaps in what its review step can catch, plus a few
robustness nits. This file tells you exactly what to change. Line numbers refer to the version reviewed
(440 lines); locate each spot by the quoted code, not the number.

## Context you need

- The script runs on the **host**, in a separate release clone, next to the release signing key. It treats
  everything on GitHub as untrusted, because the dev container (where you are working) can push as the owner.
- The host always runs the copy from the **last signed release tag**. So your changes do not run until the
  release after the one that ships them, and the owner will read your diff of this script in step 3 of that
  release. Make the diff easy to review: small, no reformatting, no unrelated cleanups.

## Hard rules

1. Do not run `tools/release-tag.sh` for real, and do not make it runnable here. It refuses to run in a
   container; leave that check alone.
2. Do not add any flag, environment variable or "test mode" that skips or weakens a check.
3. Do not create, sign, delete or push tags. Do not touch signing config, `tools/sign-release.sh` or CI
   workflows.
4. POSIX `sh` only (it runs under dash). No bashisms, no new dependencies beyond what the script already
   uses, except `sha256sum`/`shasum` in fix 1.
5. Byte-level `awk`/`tr` code keeps `LC_ALL=C` and octal escapes, as the existing code does.
6. Every refusal fails closed (`die`). Anything from the repository that reaches the terminal goes through
   `clean` first.
7. One commit per fix, message says which fix. Do not push unless asked.

## Fix 1: changed Gradle wrapper jar must be confirmed against Gradle's checksum

**Problem.** Line 339 whitelists `gradle/wrapper/gradle-wrapper.jar` as an allowed binary change. It then
gets one "Binary changes" warning and the run continues, although the jar is code that runs in the CI build.

**Change.** In step 3, right after the `[ -z "$bin" ] || warn "Binary changes…"` line, add a gate: if the
jar is in `$bin` and exists at `$sha`, compute its SHA-256 and make the owner paste the published checksum.
Do not print the computed hash before they answer.

```sh
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi | awk '{ print $1 }'; }
JAR=gradle/wrapper/gradle-wrapper.jar
if printf '%s\n' "$bin" | grep -qxF "$JAR" && git cat-file -e "$sha:$JAR" 2>/dev/null; then
    git cat-file blob "$sha:$JAR" > "$TMP/jar" || die "Can't read $JAR at $(short "$sha")."
    jarsum="$(sha256 < "$TMP/jar")"
    gver="$(git show "$sha:gradle/wrapper/gradle-wrapper.properties" 2>/dev/null \
        | sed -n 's#^distributionUrl=.*/gradle-\([0-9][0-9.]*\)-[a-z]*\.zip.*#\1#p' | clean)"
    warn "$JAR changed. It is code that runs in the CI build, and no diff can show it.
  Look up the \"Wrapper JAR Checksum\" for Gradle ${gver:-<version in gradle-wrapper.properties>} at
  https://gradle.org/release-checksums/ in a browser (not from this repository). If the jar was generated
  by another Gradle version, it must match that version's entry there; if it matches none, don't release."
    printf '%sPaste that checksum (anything else stops):%s ' "$B" "$N"
    read -r typed || typed=""
    [ "$(printf '%s' "$typed" | tr -d '[:space:]' | tr 'A-F' 'a-f')" = "$jarsum" ] \
        || die "That isn't the checksum of the jar at $(short "$sha") ($jarsum). Stopped. Nothing was changed."
    ok "$JAR matches the checksum you pasted."
fi
```

**Test.** Scratch repo with a jar file changed between two commits: the right checksum passes, a wrong one
and an empty answer both stop. A deleted jar skips the gate.

## Fix 2: refuse submodules

**Problem.** A gitlink shows in the diff as one line (`+Subproject commit <sha>`) with numstat `1 0`, so the
binary check does not fire and the submodule's content is never reviewed.

**Change.** In step 2, right after `git ls-tree -r "$sha" > "$TMP/tree" || die …` (line 313):

```sh
subs="$(awk -F'\t' '$1 ~ /^160000 / { print $2 }' "$TMP/tree" | clean)"
[ -z "$subs" ] || die "$(short "$sha") contains submodule(s), whose content this review can't show:
$(printf '%s\n' "$subs" | sed 's/^/  /')"
```

Also add `--no-recurse-submodules` to the `git fetch` on line 218.

**Test.** `git update-index --add --cacheinfo 160000,<any commit>,vendor/lib`, commit, run the snippet on
that commit's `ls-tree -r`: it must list `vendor/lib`. A normal file named `160000 x` must not match.

## Fix 3: mark and warn about invisible characters, list non-ASCII in code files

**Problem.** `clean()` only marks U+202A–202E, U+2066–2069 and C1. Other bidi marks and zero-width or
blank characters pass through unmarked, and nothing points the reviewer at lookalike letters.

**Change A: one pair of patterns, used everywhere.** Define next to `clean()`:

```sh
# Bidirectional controls and marks: U+200E, U+200F, U+202A-202E, U+2066-2069, U+061C.
BIDI_RE='\342\200[\216\217\252-\256]|\342\201[\246-\251]|\330\234'
# Invisible or blank: U+00AD, U+034F, U+115F, U+1160, U+180E, U+200B-200D, U+2028, U+2029, U+2060-2065,
# U+206A-206F, U+2800, U+3164, U+FEFF, U+FFA0, tag characters U+E0000-E007F.
INV_RE='\302\255|\315\217|\341\205[\237\240]|\341\240\216|\342\200[\213-\215\250\251]|\342\201[\240-\245\252-\257]|\342\240\200|\343\205\244|\357\273\277|\357\276\240|\363\240[\200\201][\200-\277]'
```

In `clean()`, pass them with `awk -v bidi="$BIDI_RE" -v inv="$INV_RE"` and replace the existing bidi
`gsub` with `gsub(bidi, "<bidi>"); gsub(inv, "<inv>")`. Keep the order: CR, C1, bidi, inv, print.

**Change B: self-test.** Extend the step 1 self-test (line 120) so it covers one new bidi mark and at least
two invisibles. This input and output were verified with dash + mawk:

```
in:  'a\342\200\256\302\302\233\233\033[1mb\rc\342\200\217d\342\200\213e\330\234f\357\273\277g\363\240\201\201h\r\n'
out: 'a<bidi><C1>[1mb<CR>c<bidi>d<inv>e<bidi>f<inv>g<inv>h'
```

**Change C: warnings in step 3.** The `bidi=` scan (line 346) uses `$0 ~ re` with `-v re="$BIDI_RE"`. Add
the same scan with `$INV_RE` and its own warning ("Invisible characters in added lines of: …").

**Change D: non-ASCII in code files.** After those warnings, print (with `say`, not `warn`) the code, build
and config files that have non-ASCII bytes in added lines, with a count, so the reviewer knows where a
lookalike letter could hide:

```sh
LC_ALL=C awk '/^\+\+\+ / { f = substr($0, 5); next }
    /^\+/ && /[\200-\377]/ && f ~ /\.(kt|kts|sh|gradle|ya?ml|toml|properties|pro|json)$/ { n[f]++ }
    END { for (f in n) print n[f] "\t" f }' "$TMP/diff" | sort -k2 | clean
```

Adjust the extension list to what the repository actually contains. Leave out docs and `res/` string files,
which legitimately contain non-ASCII; a list that fires on every release gets ignored.

**Test.** Ordinary text must come out of `clean` unchanged: `Živković → ✓ … š č ć đ ж 日本`. Each code point
in the two comments above must come out as `<bidi>` or `<inv>`. Run the tests with every awk you have
(mawk, gawk, busybox awk); only mawk has been verified.

## Fix 4: a binary must not get through as text

**Problem.** `git diff <commit> <commit>` takes attributes from the working tree, which is the previous
release. If that release's `.gitattributes` has a bare `diff` attribute (`*.dat diff`), a binary file in
the next release gets line counts in `--numstat` instead of `-`, so line 339 does not refuse it, and
`clean()` strips its NUL bytes: `ab\0cd` is displayed as `+abcd`.

**Change A: refuse NUL bytes in the diff.** Right after `git diff $DIFF "$prev_sha" "$sha" > "$TMP/diff"`
(line 345):

```sh
[ "$(LC_ALL=C tr -cd '\000' < "$TMP/diff" | wc -c)" -eq 0 ] || die "The diff contains NUL bytes: a binary
  file is being shown as text (a 'diff' attribute in .gitattributes?). Find it: git diff --numstat --no-renames -a …"
```

Write the final message so it names a command the owner can actually run to find the file.

**Change B: refuse a bare `diff` attribute.** In the `.gitattributes` loop (lines 318–323), next to the
`filter=` test, also die when the file matches `grep -qE '(^|[[:space:]])diff([[:space:]]|$)'`. Message:
it forces files to be diffed as text, which would let a binary pass the next release's review.

**Test.** Must refuse: `*.dat diff`, `[attr]txt diff -text`, `*.dat<TAB>diff<CR>`. Must accept:
`*.png binary`, `*.kt diff=kotlin`, `* text=auto`, `*.x -diff`, `*.y !diff`. For change A, repeat the
scenario from the problem statement in a scratch repo, working tree at the first commit.

## Fix 5: clean tag names before printing

**Problem.** Git allows tag names containing U+009B and U+202E. Unsigned tags matching `v[0-9]*` are
printed raw on line 287.

**Change.** `warn "Ignored (not vX.Y.Z, or not signed with your key):$(printf '%s' "$ignored" | clean)"`.
Then check every other place a ref name, file name or config value from the repository is printed and
confirm it either passed `is_release`/`is_sha` or goes through `clean`.

## Nits

- **N1, script path.** Line 128 uses `$0` after `cd "$ROOT"`, so `cd tools && ./release-tag.sh` fails with
  a misleading message. Before the `cd`: `case "$0" in /*) self="$0" ;; *) self="$PWD/$0" ;; esac`, then
  use `"$self"` in the `hash-object` call. Keep `$0` in the message.
- **N2, environment.** The comment on line 45 claims more than the `unset` does. Warn (do not unset) when
  any of `GIT_SSH`, `GIT_SSH_COMMAND`, `GIT_ASKPASS`, `GIT_EXEC_PATH`, `GIT_CONFIG_GLOBAL`,
  `GIT_CONFIG_SYSTEM` is set, and reword the comment to say what is and isn't covered.
- **N3, config warnings.** Add to the regex on line 139: `core\.gitproxy`, `core\.askpass`,
  `core\.attributesfile`, `remote\..*\.(uploadpack|receivepack)`. Also print which pager will be used
  (`$PG`) in step 1, since line 111 runs it with `sh -c`. Do not warn about `credential.helper` or
  `core.pager`; they are normally set and would be noise.
- **N4, key files outside the clone.** Die if `gpg.ssh.allowedSignersFile` or the `user.signingkey` file is
  inside `$ROOT` (compare `$(cd "$(dirname "$f")" && pwd -P)/` against `"$ROOT"/*`). A tracked file would
  be replaced by the checkout in step 6.
- **N5, optional.** `git -c fetch.fsckObjects=true fetch …` on line 218. If it rejects the existing
  history, do not work around it: leave it out and report what it said.

## Documentation

Update the header comment (lines 4–42) so the "Steps" list and the bullets match the new behaviour:
submodules refused in step 2; wrapper jar checksum, NUL bytes, invisible characters and the non-ASCII list
in step 3. Update README "Signing a release" if it describes any of these. Follow the repository's
convention on whether tooling changes get a changelog entry.

## Before you finish

- `sh -n tools/release-tag.sh` and `dash -n tools/release-tag.sh` pass; `shellcheck -s sh` adds no new
  findings if it is available.
- Each snippet was exercised in a scratch repository under `/tmp`, under dash. No test scaffolding is
  committed unless the repository already has tests for `tools/`.
- `git diff` of the script contains only the changes above.
- Report back: what you changed per fix, what you could not test here (anything needing the host, the
  signing key or a terminal), and anything in this brief that did not match the code you found.

---

## Status since the brief

*Added after the work. The owner gave the brief above on 2026-11-13; it is saved as given. The work is on the
branch `release-tag-review-gaps`, one commit per item, reviewed afterwards in
[the post-0.4.2 review](2026-11-13-post-0.4.2-review.md).*

| # | Item | Status |
|---|---|---|
| Fix 1 | Wrapper jar checksum | **Done** in `166f707`. Also refuses unless a 64-digit hash was computed: without `sha256sum` and `shasum` the hash was empty, and an empty answer matched it. The paste got a second try in `404b18d` (P8), and the Gradle distribution got the same check in `74e9d37` (P5). |
| Fix 2 | Submodules | **Done** in `653d754`. |
| Fix 3 | Invisible characters | **Done** in `d970864`. The existing bidi scan used a literal regex, not `-v re=`; it was switched to `$BIDI_RE`. The non-ASCII count (change D) fired on almost every release and was replaced in `9faa8c5` (P3) by a warning that shows the lines with letters that look like Latin ones. File names come only from diff headers since `21ed3b0` (P2). |
| Fix 4 | A binary shown as text | **Done** in `6c1afc7`. The suggested `git diff --numstat -a` can't identify the file (with `-a`, binaries get line counts too), so the message gave an `od` loop; since `8f9030b` (P9) the script lists the files itself. Comments in `.gitattributes` are skipped since `6cdb890` (P10). |
| Fix 5 | Tag names | **Done** in `28d3b5c`. The rest of the output was checked: everything else from the repository passes `is_release`/`is_sha`, is a hash, or goes through `clean`. Hook names, git config values, the remote URL and key paths are printed raw, but they're local, not from the repository. |
| N1 | Script path | **Done** in `c1bab1b`. |
| N2 | Environment | **Done** in `1194ff8`; one grouped warning with advice, and `SSH_ASKPASS`, `SSH_ASKPASS_REQUIRE`, `GIT_PROXY_COMMAND`, since `ebd377e` (P6). |
| N3 | Config warnings, pager | **Done** in `985b36a`; grouped with advice in `ebd377e` (P6). |
| N4 | Key files outside the clone | **Done** in `32859a0`; symlinks refused since `cfa94e7` (P11). |
| N5 | fsck on fetch | **Done** in `c7589f3`. The existing history passes (130 commits of `main`, `v0.3.0`, `git fsck --strict`). A failed fetch has its own message since `bf1fe12` (P12). |
| Docs | Header, README, RELEASING, changelog | **Done** in `27a8a72`, kept up to date by the later commits. |
| Checks | `sh -n`, `dash -n`, tests | **Done**: both pass; every snippet was tested under dash with mawk in scratch repos in the session's scratchpad (under `/tmp`), nothing committed. Not available: gawk, busybox awk, shellcheck. Not run: the script itself, prompts in a real terminal, the host, macOS. |
