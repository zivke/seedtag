# Review of `dc01403` (fixes of the hardening review)

- **Date:** 2026-11-12
- **Reviewed:** commit `dc01403` on `release-tag-hardening` against `402663a`, plus the whole current state of
  `tools/release-tag.sh` and `tools/sign-release.sh`, README ("Signing a release"), CLAUDE.md, changelog.md,
  TODO.md, and the two earlier reports ([first](2026-11-12-release-tag-review.md),
  [second](2026-11-12-release-tag-hardening-review.md)).
- **By:** an expert review on the Fable model, as a software developer and software security expert of 30+ years.
- **What was run:**
  - Pure-text tests under dash and mawk 1.3.4 of snippets copied from the scripts: `clean` and its self-test, the
    allowed-signers awk, the tag-message `sed`, the `.gitattributes` awk and filter grep, the binary allowlist,
    `remote_tag` under `set -e` with a stubbed `git`, `cd "$(dirname "$0")"` with `CDPATH` set, and trap inheritance
    for `trap '' INT`.
  - Read-only git queries on the real repository: the `versionCode`/`versionName` lines at `v0.3.0` and `dc01403`,
    binary numstat entries for `v0.3.0..dc01403` (none), `.gitattributes` in the tree (none), and the `v0.3.0` tag
    object (message `SeedTag 0.3.0`).
- **What was not run:** the reviewer's end-to-end sandbox was refused by the permission system, so neither script was
  executed in this review. Real signatures, `verify-tag` output, fetch/push, tty prompts, Ctrl-C and the HERE/ROOT
  check in a real clone come from reading. Nothing was tested on macOS, BSD tools or bash-as-sh.
- **Not accessed:** `other/`, any real key, the forwarded agent, the network.
- **Status:** every finding below was addressed in the next commit on the same branch.

## 1. Verdict

`dc01403` fixes what it claims, with two exceptions: the new C1 filter in `clean` can be bypassed with a nested byte sequence (confirmed), and the `sha256sum` fallback stops short of `check-apk.sh`. No High issue and no happy-path regression were found by reading; the real repository's `v0.3.0` and `dc01403` pass the `gradle_value`, binary and `.gitattributes` checks.

Worth fixing before the first signed release:
- **M1:** the nested C1 bypass, a one-line fix.
- **M2:** a lone CR is deleted from the shown diff without any warning, so code after a comment reads as part of the comment in Kotlin/Gradle, YAML and Python files. This predates `dc01403`.

Because nothing was executed, run `tools/release-tag.sh --dry-run --bootstrap v0.3.0` on the host before relying on it, including one Ctrl-C at a prompt and one inside `less`.

## 2. Status of N1–N11 and findings 3, 5, 13

| # | Status | Why (lines in `dc01403`) |
|---|---|---|
| N1 | **Fixed** | `release-tag.sh:250-260`: no "GitHub's tag page"; names the APK certificate and `version-control-info.textproto`; requires the first 12 characters of `$prev_sha`. Inherent residual: the SHA is on screen and can be copied without checking. Whether the 0.3.0 APK contains the textproto was not checked (AGP 8.7.3 includes it by default for release builds). |
| N2 | **Fixed** | `:327`: the `core/src/test` exclude is gone; the comment at `:324-325` says why. |
| N3 | **Fixed** | `:286-294`: tree listing to a file with a die, case-insensitive, quotes stripped, read by object id, dies if unreadable. Residual: R5. |
| N4 | **Partly** | `:83-86` removes `\302[\200-\237]` and marks bidi controls. A nested sequence survives (M1). |
| N5 | **Fixed** | `:270-275, 380-382`: status checked, exact `$2 == ref`. Confirmed with a stub. |
| N6 | **Fixed** | `:49, 116-117`. Residuals: R3, R4. |
| N7 | **Fixed** | `:157-167`, `:337-338`. Reproduced on samples: CRLF, only another key, empty file, README-style line. |
| N8 | **Fixed** | `:131-137`. `sign-release.sh:118-123` still accepts a literal key, as intended. |
| N9 | **Fixed** (not run on a pty) | `:93-94, 100-102`. Confirmed in dash that a child of `trap '' INT` can't be killed by INT. Residual: R2. |
| N10 | **Fixed** | `release-tag.sh:177`, `sign-release.sh:133-134`. The `{n;p;q;}` form is valid for BSD sed (by reading). |
| N11 | **Partly** | Self-test at `release-tag.sh:108-109`; `sha256` at `sign-release.sh:75-78, 83, 187`. `check-apk.sh:111`, which `sign-release.sh` calls, still uses bare `sha256sum` (R1). |
| 3 | **Partly** (nearly) | Same as N4: only the nested-C1 case (M1) is left. |
| 5 | **Fixed** | Via N2. |
| 13 | **Fixed** | Via N3, plus the LFS-disabled checkout. Residual: R5. |

## 3. New and remaining findings

| # | Severity | Location | What's wrong | Scenario | Verified | Fix |
|---|---|---|---|---|---|---|
| M1 | Low–Medium (new) | `release-tag.sh:85` | `gsub(/\302[\200-\237]/, "")` runs once, and deleting a match can join the bytes around it into a new C1 sequence. | A commit subject containing `\302\302\233\233` + `31m` comes out of `clean` as U+009B (CSI) followed by `31m`. | Yes: `printf 'x\302\302\233\23331mRED\n' \| clean \| od -c` gives `x 302 233 3 1 m R E D`. | Repeat until nothing matches, or replace with a visible marker. Add the nested case to the self-test. |
| M2 | Low–Medium (since `402663a`) | `release-tag.sh:84, 320` | `clean` deletes CR, and the control-character count skips `\015`, so nothing warns. A lone CR ends a line in Kotlin, Java, Groovy, YAML and Python, but not on screen. | `build.gradle.kts` or a workflow gets `// harmless note<CR>exec("…")`. The review shows one `+` line that reads as a comment; Gradle or CI runs the second half. | The display half, yes. That the languages treat a lone CR as a line end is from their specifications. | Drop only a CR at the end of a line; show any other as `<CR>`; warn for added lines with one. |
| M3 | Low (new) | `release-tag.sh:85, 320` | C1 controls are removed silently: not in the `ctl` count. | A string literal with U+0085 is shown as if the character weren't there. | Yes, on a sample. | Count them too, or show a marker. |
| R1 | Low | `check-apk.sh:111` | Bare `sha256sum`. On a host without it, `sign-release.sh` prints "sha256sum: not found" and an empty `>> SHA-256:` line. | macOS without coreutils. | Reading only. | The same fallback in `check-apk.sh`. |
| R2 | Info | `release-tag.sh:93` | "Steps above that say they changed something did; nothing else was" is not true for an interrupt during `git push`, or between `git tag -s` and its ✓. | Ctrl-C while the push hangs: the tag may be on the remote. | Reading only. | Keep a state and print what may exist. |
| R3 | Info | `release-tag.sh:116-118` | The N6 check compares directories, not the file. An untracked `tools/<other name>.sh` in the clone passes. | A stray edited copy in the clone's `tools/`. | Reading only. | `[ "$(git hash-object "$0")" = "$(git rev-parse HEAD:tools/release-tag.sh)" ]`. |
| R4 | Info | `release-tag.sh:49`; `sign-release.sh:59` | `cd "$(dirname "$0")"` honours an exported `CDPATH`; `HERE` can get two lines. Fails closed, with a confusing message. | `CDPATH=~/dev/seedtag` exported in the shell profile. | Yes, the snippet. | `CDPATH= cd -- "$(dirname -- "$0")"`. |
| R5 | Info | `release-tag.sh:287-288` | A name that only the file system maps to `.gitattributes` is not selected: on HFS+ `.git<U+200C>attributes`. | A legacy HFS+ volume with git-lfs installed. | The awk miss was reproduced; the file-system side was not. | Treat names that match once non-ASCII is ignored as `.gitattributes` too. |
| R6 | Info | `release-tag.sh:101` | `\|\| true` also hides a pager that can't start; the view still counts as seen. | `core.pager` names a program that isn't installed. | Reading only. | Fall back to printing on exit status 126/127. |
| R7 | Info | `release-tag.sh:160` | A principal that starts with `ssh-` is taken for the key type. Fails closed. | `ssh-release@host` in the file. | Yes, on a sample. | Start the loop at `i = 2`. |
| R8 | Info | `release-tag.sh:133` | A relative `user.signingkey` path that starts with `ssh-`, `ecdsa-` or `sk-` is reported as a literal key. | `ssh-keys/seedtag.pub`. | Reading only. | Match literal keys with a space after the type. |

Checked and found sound: `remote_tag` and `gradle_value` are used only in plain assignments; the bootstrap loop can't pick an unsigned newer tag and refuses `--bootstrap` once a signed release exists; the AND-lists under `set -e`; the tag created by the script has exactly the message `signed_release` requires, and `v0.3.0` already uses that form.

## 4. Docs against the scripts

- **N4 claim** (commit message, header, `clean` comment, README): not quite true until M1 is fixed.
- **N11 claim:** true for `sign-release.sh` itself; `check-apk.sh` still needs `sha256sum` (R1).
- **Where the APK comes from** (predates this commit): the `sign-release.sh` header says "from `make build`" and an error says "run make build first"; the README says to download the CI run's APK and not to build in the release clone.
- **"Clean clone"** (`sign-release.sh:100`): the last trace of the "fresh clone" wording.
- **First release** (README): one sentence would help: for `--bootstrap`, stay on the commit the fresh clone gave you, because `v0.3.0` has no `tools/release-tag.sh`.
- **Matching:** the `art/*.png` allowlist, `git fetch --no-tags origin main`, bootstrap evidence, `SeedTag X.Y.Z`, tests in the build view, the copy check, CLAUDE.md's lines, the changelog under `[Unreleased]`.

## 5. What is done well

- **Self-test:** it tests the real `clean` function, fails closed and names the awk in use.
- **`.gitattributes` check:** paths and contents are separated (tree listing, then blob by id), and every failure is a `die`.
- **`remote_tag`:** one function, exact match, and a comment stating the `set -e` constraint it depends on.
- **Bootstrap text:** it says plainly that the tag, GitHub's page and the date prove nothing, and gives the two commands that produce evidence the container can't forge.
- **Pager handling:** INT is ignored only around the pager and restored right after; Ctrl-C during a slow `git diff` still stops the run.
- **Allowed-signers messages:** the three cases are distinct and correct.
- **Tag message check:** identical in both scripts and applied to `prev`, to `HEAD` and to the new tag through one function.
- **Environment:** `GIT_COMMON_DIR` and `GIT_ATTR_SOURCE` are cleared in both scripts.
