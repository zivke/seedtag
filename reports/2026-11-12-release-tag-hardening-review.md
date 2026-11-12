# Review of the hardening of `tools/release-tag.sh` and `tools/sign-release.sh`

- **Date:** 2026-11-12
- **Reviewed:** commit `402663a` on `release-tag-hardening` against `fe5e4d0`: both scripts, README ("Signing a
  release"), CLAUDE.md, `.devcontainer/README.md`, changelog.md and TODO.md, with `.github/workflows/build.yml` and
  the `Makefile` for context. This is a follow-up to [the first review](2026-11-12-release-tag-review.md).
- **By:** an expert review on the Fable model, as a software developer and software security expert of 30+ years.
- **Threat model:** as in the first review. Everything on GitHub is attacker input; the release clone's config,
  `~/.ssh` and the keys are trusted.
- **What was run:**
  - `dash -n` on both scripts: syntax OK.
  - Pure-text checks under dash and mawk 1.3.4 of the scripts' own snippets on sample input: the allowed-signers
    awk, `gradle_value`, the version comparison, the `verify-tag` awk, the binary allowlist, the `.gitattributes`
    greps, `clean`, the bidi awk, `page`, and the `set -e` behaviours.
  - A read-only `git diff --name-only v0.3.0 402663a` with the `focus` pathspecs on the real repository.
- **What was not run:** the reviewer's end-to-end sandbox was refused twice by the permission system, so neither
  script was executed in this review. Everything involving real git signatures, tags, fetch/push, the tty prompts and
  the pager comes from reading the code and from documented git/OpenSSH behaviour. Nothing was tested on macOS or
  with BSD tools.
- **Not accessed:** `other/`, any real key, the SSH agent, the network.
- **Status:** every finding below was addressed in the commit after `402663a` on the same branch, except where the
  table says otherwise.

## 1. Verdict

The commit is a real improvement and closes both High findings: the review range is now anchored on a tag signed with the pinned key, and "signed" means that key's fingerprint in both scripts. Of the 17 findings, 14 are fixed, 3 are partly fixed (3, 5, 13), and none is unfixed, so the "all 17 findings" claim in the commit, changelog and TODO is slightly too strong. I found no new High issue and no happy-path regression by reading.

Two new Medium issues are worth fixing before the first signed release:
- **N1:** the one-time `--bootstrap` anchor is confirmed against evidence the attacker controls.
- **N2:** the "what builds" view excludes `core/src/test`, which CI executes in the build tree before building the APK.

Because nothing was executed, run `tools/release-tag.sh --dry-run` on the host before relying on it.

## 2. The 17 original findings

| # | Status | Why (lines in `402663a`) |
|---|---|---|
| 1 | **Fixed** | `release-tag.sh:187` fetches only `+refs/heads/main` with `--no-tags`, not quiet. `:213-226` takes `prev` as the newest tag passing `signed_release` (`:156-161`: `vX.Y.Z`, tag object, name inside the object, fingerprint). Others are listed at `:231`. Residual: `--bootstrap` (N1). |
| 2 | **Fixed** | `:143-152` dies unless every entry in the allowed-signers file is the signing key. `:160` pins the fingerprint. `sign-release.sh:109-132` does the same. The "tag exists" messages are reworded (`:240-246`). |
| 3 | **Partly** | `clean` (`:79`) strips C0 and DEL and is applied to every repository-derived output found (`:82, 92, 277, 281, 284, 304, 330`; `sign-release.sh:124`). It lets through C1 controls in UTF-8 form and bidi overrides in subject and author (N4). |
| 4 | **Fixed** | `main_ref="refs/remotes/$REMOTE/main"` (`:184`), `refs/tags/…` everywhere, and no tags are fetched. `--commit` is still user-typed, but it is checked against `main_ref` (`:196`). |
| 5 | **Partly** | The inverted list (`:292-295`) now covers `gradlew`, `gradle.properties`, `.gitattributes`, `buildSrc/`, new build files, the manifest and all of `app/src/main/res`. It excludes `core/src/test`, which runs in CI's build tree (N2). |
| 6 | **Fixed** | `:275-280` dies on any `-`/`-` numstat entry outside `art/*.png` and the wrapper jar. Attributes come from the checked-out tree, which is now a signed release (`:162-177`); this fails closed. |
| 7 | **Fixed** (as far as a script can) | `HEAD` must be a signed release tag (`:162-177`); it warns when `here = sha` (`:265`); the README table no longer says "Pull". Residual: N6. |
| 8 | **Fixed** (as far as the scripts can) | `:359-363` prints the full SHA and what to check on the run page. `sign-release.sh:136-150` compares the remote tag. That check is point-in-time: a tag moved, built and moved back passes. The binding still rests on the user reading GitHub's run header, which the text says. |
| 9 | **Fixed** | `page` (`:90-93`) writes to a file first, and the pager's status is ignored. Checked with a pager that quits early: the script continues; a failing producer dies with a message. |
| 10 | **Fixed** | `:128-138` distinguishes exit 0, 1 and 2 and prints `SSH_AUTH_SOCK`. No agent is a warning, not a ✓. |
| 11 | **Fixed** | The `git()` wrapper sets `core.hooksPath=/dev/null` and `core.fsmonitor=false` (`:45`; `sign-release.sh:61`). Hooks are listed via `--git-common-dir` (`:110`). `sshCommand`, `gpg.ssh.program`, `pushurl` and `insteadOf` are shown (`:112`). |
| 12 | **Fixed** | `gradle_value` (`:199-204`) requires exactly one line mentioning the name, alone on its line. Digits-only `case` at `:208, 248`. A reflective setter in the same file still passes, but it is in the focused diff. |
| 13 | **Partly** | The script's own checkout disables LFS (`:353-354`). The filter refusal meant to cover a manual `git checkout` can be bypassed (N3). |
| 14 | **Fixed** | `git config --path` (`:116, 139`), literal keys accepted (`:119-120`), awk parser instead of grep (comments skipped). Namespace and validity options are not checked; a wrong one fails closed at `:331`. See N8. |
| 15 | **Fixed** | `unseen` tracking and a typed `reviewed` (`:296-318`). |
| 16 | **Fixed** | Pager from `git var GIT_PAGER` (`:87`), `LESS` defaults to `FRX` (`:92`), push URL shown (`:183, 339-340`). |
| 17 | **Fixed** | `--bootstrap`; `GIT_*` unset (`:41-43`); tty check (`:97`); push failure explained (`:344-345`). Not cleared: `GIT_COMMON_DIR`, `GIT_CONFIG_GLOBAL`/`SYSTEM`, `GIT_ATTR_SOURCE`, `GIT_SSH_COMMAND` (stray environment only). |

## 3. New findings

| # | Severity | Location | What's wrong | Scenario | Verified | Fix |
|---|---|---|---|---|---|---|
| N1 | Medium (one-time) | `release-tag.sh:230-236` | The `--bootstrap` anchor is an unsigned local tag that came with `git clone`. The confirmation shows its commit and the author date, and suggests "GitHub's tag page" as evidence. Both the date and that page are attacker-controlled. | Before the clone is made, the container moves the last tag on GitHub to a later commit M on `main`. `--bootstrap` then reviews M..R only. The first signed tag certifies M unreviewed, and every later review starts after it. | Reading only | Drop "GitHub's tag page". Name evidence the attacker can't change: the signed APK's `META-INF/version-control-info.textproto`. Require typing the first 12 hex digits of `$prev_sha` instead of `y`. |
| N2 | Medium | `release-tag.sh:293` (`:(exclude)core/src/test`); `build.yml:42-45` | CI runs `make test build` in one container over the same writable checkout. Test code and `core/src/test/resources` therefore execute before the APK is built, and are excluded from the view labelled "what builds, checks, signs and configures". | A test's `@BeforeClass` rewrites a file under `core/src/main` or a Gradle file. CI's APK is tampered and its hash is the one passed to `--expect`. The change appears only in the whole diff. | Pathspec behaviour run read-only; exploit not run | Remove the `core/src/test` exclude (the tests are small). |
| N3 | Low | `release-tag.sh:256-261` | The `.gitattributes` filter refusal has three holes. (a) `ls-tree` without `-z` C-quotes non-ASCII paths, so `"art/\303\251/.gitattributes"` ends in `"` and misses `\.gitattributes$`. (b) `git show "$sha:$a" \| grep -q` treats a failing `git show` as "no filter". (c) The match is case-sensitive; on a case-insensitive macOS volume `.GitAttributes` may be read as `.gitattributes`. | `art/é/.gitattributes` with `* filter=lfs`. The script prints "no filters". On a host with git-lfs, the manual `git checkout vX.Y.Z` runs the smudge filter. | The grep miss was reproduced on a quoted name | Select by tree listing, case-insensitively, read by object id, and die if it can't be read. |
| N4 | Low | `release-tag.sh:79` | `clean` passes C1 controls encoded as UTF-8 (U+009B CSI, U+009D OSC) and bidi overrides. xterm acts on U+009B by default. The bidi warning covers only added diff lines, not subjects or author names. | A subject with U+009B sequences redraws the "Commit:" line in xterm. U+202E in a subject reorders it. | Confirmed the bytes pass `clean`; terminal behaviour not tested | Add an awk stage removing `\302[\200-\237]` and marking bidi controls. |
| N5 | Low | `release-tag.sh:244, 346` | `[ -n "$(git ls-remote …)" ]` ignores a failing `ls-remote`, so a network or auth error reads as "tag doesn't exist yet". Both lines use ls-remote's tail matching instead of an exact match. | The container pushes `refs/tags/x/refs/tags/v0.4.2`: misleading "already exists" / "doesn't show as pushed". | dash semantics confirmed; tail matching from the documentation | Check the exit status; match `$2 == "refs/tags/$TAG"` exactly. |
| N6 | Low | `release-tag.sh:101-102, 168` | `ROOT` comes from the current directory, not from `$0`. "this script is from that release" is asserted about the clone you stand in, not the file that runs. | `cd ~/seedtag-release && ~/dev/seedtag/tools/release-tag.sh`: all checks pass and the ✓ is printed. | Reading only | Die unless the script's directory is `$ROOT/tools`. |
| N7 | Low | `release-tag.sh:150, 304` | (a) When the signing key is absent and another key is listed, or the file has CRLF line endings, the message is "also lists other keys", and it prints `line(s)2 5` without a space. (b) In `focus git diff --stat … \| clean \| sed`, a git failure is swallowed. | A stale key in the file sends the user looking for a second key that isn't there. | Both reproduced on samples | Test `good = 0` first; add the space; strip CR; write the stat to a file first. |
| N8 | Info | `release-tag.sh:119-120` | `key::…` and literal keys are accepted, but git can only sign with those through the agent, which the script forbids. | Fails closed at `git tag -s` with git's own error. | Reading only | Die early: "user.signingkey must be the path of the .pub file". (`sign-release.sh` only verifies, where a literal key works, so it still accepts one.) |
| N9 | Info | `release-tag.sh:86` | `trap 'exit 130' INT`: Ctrl-C in the pager ends the run with no message. | The user loses the review position without knowing why. | Not run (needs a pty) | Print a message in the trap and ignore INT around the pager. |
| N10 | Info | README ("The same key and file can serve other projects"); `signed_release` | A `vX.Y.Z` tag signed for another project is a valid "signed release" object here; the tag message is not checked. No way found to use it to shorten a review or get an APK signed. | None practical found. | Reading only | Require the tag message `SeedTag X.Y.Z`. |
| N11 | Info | `release-tag.sh:79, 284, 287`; `sign-release.sh:70` | Not tested on macOS: octal byte ranges in `/usr/bin/awk` regexes and BSD `tr`. `sha256sum` is not on older macOS. | A bidi warning that silently never fires on macOS awk. | Not tested | Self-test the filter at start and die if it doesn't work; fall back to `shasum -a 256`. |

## 4. Docs against the scripts

- **"All 17 findings"** (commit message, changelog, `TODO.md`): 3, 5 and 13 are partly fixed.
- **Binary allowlist:** the README and the script header say binary changes are refused "outside `art/`". The code allows only `art/<name>.png`.
- **"By hand, it amounts to `git fetch` …"** (README): a plain `git fetch` imports remote tags. It should read `git fetch --no-tags origin main`.
- **Bootstrap evidence:** the README says to check the tag "against your own records"; the script suggests GitHub's tag page (N1).
- **Bootstrap example:** the review suggested `v0.4.1` instead of `v0.3.0`. Not changed: 0.4.0 and 0.4.1 were never tagged, so `v0.3.0` is the last release tag.
- **"Fresh clone":** the `sign-release.sh` header and CLAUDE.md still say it runs from a fresh clone. It now needs the release clone's `user.signingkey`.

## 5. What is done well

- **One trust anchor:** the fingerprint of `user.signingkey` from the clone's local config is used for `prev`, for the `HEAD` check, for the new tag and in `sign-release.sh`. The `verify-tag` awk requires the `git` namespace and an exact last-field match.
- **Tag identity:** the tag name inside the object must equal the ref name, so a signed tag can't be re-used under another name.
- **Previous-release loop:** `--bootstrap` is refused as soon as any signed release exists in the history, so it can't be used later to pick an unsigned anchor. The loop fails closed in every branch traced.
- **Binary check:** it uses the same attribute source as the diff that is shown, and that source is a reviewed release.
- **`set -e` handling:** `die` inside `gradle_value` runs only in plain assignments, where dash aborts the script (confirmed). No `die` sits inside a command-substitution argument or a pipeline subshell.
- **Focus pathspecs:** `:(exclude)*.md` excludes Markdown at any depth, and the manifest and `app/src/main/res` (including `res/xml`) stay in the focused view.
- **Carried over:** the SHA is resolved once, the tag name is typed, only `refs/tags/$TAG` is pushed, the tag is re-verified after signing and after the push, and every prompt defaults to "no".
- **`sign-release.sh`:** deriving the tag from `versionName` at `HEAD` removes the "any `v*` tag pointing here" ambiguity. The remote comparison uses an exact ref match and fails closed when the remote is unreachable.
