# Review of the release flow as a whole, `8cfae6e..eac4837` (candidate 0.4.2)

- **Date:** 2026-11-13
- **Reviewed:** the end state at `eac4837`: `tools/release-tag.sh`, `tools/sign-release.sh`, `tools/check-apk.sh`,
  README ("Signing a release", "Build integrity"), CLAUDE.md, `.devcontainer/README.md`, changelog.md, TODO.md and the
  three earlier reports. `Makefile` and `.github/workflows/build.yml` were read for context; neither changed. No app,
  core, `seedtag.py`, Gradle, Makefile or workflow file is in the diff.
- **By:** an expert review on the Fable model, as a software developer and software security expert of 30+ years.
- **Threat model:** as in the earlier reports. Everything on GitHub is attacker input; the release clone's config,
  `~/.ssh` and the keys are trusted.
- **Not accessed:** `other/`, any real key, the forwarded agent, the network. The repository's working tree, refs and
  config were not modified, and the sandbox was deleted.
- **Status:** every finding below was addressed in the next commit on `main`.

## 1. What was run

Unlike the three earlier reviews, both scripts were executed end to end. This was in a `mktemp -d` sandbox with its own `HOME`, throwaway ed25519 keys, local bare remotes and a pty driver, under dash, mawk 1.3.4, git 2.51.1 and glibc 2.39 iconv. The only change to the script under test was replacing the container test with `if false`, committed in the sandbox repo so the "as committed at HEAD" check still applied.

- **First release, synthetic repo:** `--bootstrap v0.3.0` from review through sign, verify, push and checkout.
- **First release, real history:** `--bootstrap v0.3.0 --dry-run` on a sandbox clone of this repository. It passed every check and showed 56 commits, 43 files, a whole diff of about 6,650 lines and a build view of about 2,190 lines. No binary changes, no `.gitattributes`, no control-character warnings.
- **Second, non-bootstrap release (0.4.2 → 0.4.3 → 0.4.4):** default commit, `--commit <sha>`, a short sha, a bogus rev, and a commit whose version isn't newer; `--remote up` with no tracking ref yet; `--bootstrap` once a signed release exists (refused); running from a subdirectory, a copy outside the clone, and an edited copy (all refused).
- **Every prompt declined:** "Have you read", all four views, a wrong word at `reviewed`, a wrong tag name; push declined, then delete or keep; checkout declined; Ctrl-C at a prompt (correct state message, exit 130) and EOF at a prompt.
- **Hostile input:**
  - a commit subject with ESC, CR and U+202E;
  - added lines with a lone CR, bidi, C1 and ESC (marked, and both warnings fired);
  - a fresh clone carrying `v0.4.3-rc1`, an unsigned annotated `v0.4.9`, and `v0.4.25` / `v0.4.35` signed with another key (all listed as ignored; `HEAD` on one of them refused);
  - a tag already on the remote (refused);
  - the signed `v0.4.3` tag object replayed as `refs/tags/v0.9.9` and as `refs/tags/v0.4.4` (rejected by both scripts).
- **Agent check** with a private `ssh-agent -a <sandbox>/sock`: empty agent, another key, the signing key (refused).
- **Pager:** `cat`, a missing pager (falls back to printing), `less` quit with `q`.
- **Signing failure:** private key unreadable at `git tag -s`.
- **`sign-release.sh`** with stub `apksigner` / `aapt2` in `BUILD_TOOLS`:
  - missing, wrong and upper-case `--expect`;
  - wrong certificate (output deleted);
  - `HEAD` on the previous tag, a dirty tree, an APK recording another commit;
  - remote tag moved, remote tag deleted, `--no-remote-check`, `REMOTE=` set to an unknown remote;
  - an allowed-signers file that doesn't list the key.
- **`check-apk.sh`:** with the real `aapt2` and `apksigner` on the existing `seedtag-unsigned.apk`, read-only (exit 0, hash printed); the `shasum` fallback, and neither tool present, both with stub `aapt2` / `apksigner`.
- **Other:** `clean` on 60,000 lines with scattered invalid UTF-8 (no line lost) and a 2 MB line; `dash -n` on the three scripts; `tools/check-workflows.py` (OK).

## 2. What was not run

`make build` / `debug` / `build-test` / `test` (they write into the working tree); GitHub Actions and anything on GitHub; macOS, BSD tools, or bash as `sh`; a key with a passphrase or a hardware key; Ctrl-C inside `less` or during `git push`; real `apksigner sign` with a keystore.

## 3. Verdict

**The flow holds under the threat model.** In every case run, nothing the remote controlled changed the review range, the choice of previous release, or what `sign-release.sh` accepts. No attack or mistake tried was accepted, and every failure was closed. No High or Medium issue and no regression in the normal build.

**Ready to release as 0.4.2: yes, as a PATCH.** Three things first, none a code blocker:
- **Finding 3:** check which commit your 0.3.0 APK records before running `--bootstrap v0.3.0`.
- **Finding 1:** the one link no script enforces is "this CI run is the run of my tag". The README says so, but the texts around `--expect` suggest more protection than there is (finding 2).
- **Host run:** do one `--bootstrap … --dry-run` on the real host. macOS tools, the passphrase prompt and `less` are still untested by any review.

## 4. Findings

| # | Severity | Location | What's wrong | Scenario | Verified | Fix |
|---|---|---|---|---|---|---|
| 1 | Low (residual, by design) | `release-tag.sh:413-417`; `sign-release.sh:84-96, 145-167`; README "For each release" | Nothing mechanical binds the APK to the CI run of the signed tag. The APK and the `--expect` hash come from the same run, the recorded commit is written by the build, and the remote-tag check is point-in-time. The only binding is the user reading the commit on GitHub's run page. | After the tag is pushed, the container moves `v0.4.2` to a commit M, lets CI build it, and restores the tag; with API access it could also delete the legitimate run. M's build writes your commit into the textproto. If the run page's commit isn't compared, all `sign-release.sh` checks pass. | Partly: moved and deleted tags are caught only while moved; the rest by reading. | Say in step 6 and the README that the run-page commit is the only binding and that a missing run proves nothing. Better: a second build the container doesn't control. Worth a one-time look at Settings › Actions › Runners for self-hosted runners. |
| 2 | Low | `sign-release.sh:13-15, 92`; `build.yml:57`; README "Signing outside protects the key, not the build"; `check-apk.sh:15` | These texts describe the older flow (sign the container's APK, compare with CI's hash). The README now has you download CI's APK and pass CI's hash, so the mandatory `--expect` only checks the download. ">> matches the independent build" overstates it. | A user reads "`--expect` catches a tampered build" and treats the run-page check as optional. | Reading; the message was printed in the sandbox for an APK and hash from one source. | Pick one description; reword `build.yml:57`. |
| 3 | Low (this release) | `release-tag.sh:259-268`; README first-release paragraph | The bootstrap evidence may not match. The signed `seedtag.apk` in the dev folder (versionName 0.3.0, versionCode 4) records `a8bff16`, 17 commits after the `v0.3.0` tag (`38cb4db`). `--bootstrap` takes only a tag, and nothing says what to do when the evidence shows another commit. | You check your 0.3.0 APK as told and see `a8bff16…`, not `38cb4db…`. The correct action is to stop, and then you're stuck. | APK facts: yes, read-only. Whether that file is the APK you installed as 0.3.0: unknown. | Let `--bootstrap` take a commit, or document re-pointing the local `v0.3.0` in the release clone. |
| 4 | Low | README; `release-tag.sh:23-24, 189-204` | The "HEAD is a signed release" check lives in the script it protects. It stops an honest but unreviewed copy; a hostile `main` wouldn't contain it. The README has no steps for a later fresh clone, which lands on `main`. | New machine: `git clone`, then `tools/release-tag.sh` runs `main`'s copy. A tampered one runs next to the signing key. | The honest refusal: yes. The rest is inherent. | README: after any fresh clone, `git verify-tag vX.Y.Z` and `git checkout vX.Y.Z` before anything in `tools/`. |
| 5 | Low | `release-tag.sh:270-276` | The "isn't newer" check runs before "tag exists already". A tag you created and kept is now the previous release, so the helpful message is never reached. | Decline the push, keep the tag, re-run: `✗ 0.4.3 isn't newer than v0.4.3.` | Yes | Check "tag exists" first; if the local tag is signed and points at `$sha`, say "push it or delete it". |
| 6 | Low | `release-tag.sh:60, 418-419`; `sign-release.sh:40` | The remote is `--remote <name>` in one script and `REMOTE=<name>` in the other. Step 6 prints the `sign-release.sh` command without it. Neither is in the README or CLAUDE.md. | `release-tag.sh --remote up`, then the printed command: "v0.4.3 on origin is missing … Not signing". | Partly | Give `sign-release.sh` a `--remote` and print it in step 6. |
| 7 | Low | `.devcontainer/README.md:53-57` | `git fetch <bundle> main:incoming` also imports tags from the bundle, which the container makes. | A bundle carrying `v0.4.4`: the next run stops with "A tag v0.4.4 exists in this clone already". Fails closed. | Yes | `git fetch --no-tags <path>/seedtag.bundle main:incoming`. |
| 8 | Info | `sign-release.sh:112-115, 163-166` | With `HEAD` still on the previous tag, it prints three reassuring lines about the old tag, then "built from commit …, but v0.4.2 is …", without saying to check out the new tag. | Decline step 6's checkout and run the printed command. | Yes | Add the hint to the message. |
| 9 | Info | `sign-release.sh:172, 183` | A failed run deletes `seedtag.apk` but leaves the previous release's `seedtag.apk.sha256`. | A wrong keystore after an earlier release. | Reading | `rm -f "$OUT" "$OUT.sha256"`. |
| 10 | Info | `release-tag.sh:379` | A failing `git tag -s` ends the run with git's error only (exit 128). | Wrong passphrase. | Yes | `\|\| die "git tag failed; no tag was created."` |
| 11 | Info | `release-tag.sh:81-91` (comment) | glibc `iconv -c` passes out-of-range sequences (`F4 90 9B 9B`, `F8 …`), so "bytes that aren't valid UTF-8 are removed" isn't quite true. Harmless on a UTF-8 terminal. | — | Yes | Reword the comment. |

## 5. Complexity

- **Allowed-signers check:** no longer load-bearing for the scripts, since both pin the fingerprint; `sign-release.sh` accepted a tag while `git verify-tag` itself failed with "No principal matched". It still protects a manual `git verify-tag` (finding 4), so keep it, but the header's reason is outdated.
- **Duplicated signature check** (`signed_release` and `sign-release.sh`): acceptable for two standalone scripts; they agree today.
- **`release-tag.sh` as a whole:** 419 dense lines that the first release asks you to read. Nothing in it is dead, but it is at the limit of what can be reviewed by eye. Don't add to it.

## 6. Effect on the normal build

None found. The only build-path change is the `check-apk.sh` hash line, and both branches were exercised. The `Makefile` and workflow are untouched; `wrapper-check` still needs `sha256sum`, which is fine because builds run only in the container and CI.

## 7. Docs against the scripts

- **CLAUDE.md "Releasing":** still says the user "creates the signed tag (`git tag -s` …)" and "signs `seedtag-unsigned.apk`"; it doesn't name `release-tag.sh` or say the APK is the CI run's.
- **CLAUDE.md and README:** `--remote` / `REMOTE=` aren't mentioned (finding 6).
- **`build.yml:57`:** "Sign only if your own build matches" contradicts the README's "download the run's APK" (finding 2).
- **README:** "the script refuses to run unless `HEAD` is a signed tag" needs the caveat in finding 4.
- **`.devcontainer/README.md`:** the bundle fetch (finding 7).
- **`--dry-run` "Nothing was changed":** true except that `origin/main` was fetched. Trivial.

## 8. Changelog

- **PATCH (0.4.2) is right.** No `Added` entry, no app, core, format or `seedtag.py` change; the APK differs from 0.4.1 only in its version. The 0.4.1 entry is the precedent for filing release tooling under `Changed`.
- **Accurate:** every claim matched the scripts' behaviour in the sandbox.
- **Not complete (minor):** the `shasum` fallback, the committed-copy check, and the changed one-time setup (a dedicated allowed-signers file set in the clone) are missing or only implied.
- **Form:** one 16-line bullet, with `--bootstrap` explained twice. Two bullets, one per script, would read better.

## 9. What is done well

- **One anchor everywhere:** the fingerprint from the clone's `user.signingkey` decides the previous release, the `HEAD` check, the new tag and what `sign-release.sh` accepts. Tag name and message are checked inside the object, so the replayed tag object was rejected by both scripts.
- **Hostile tags are inert:** only `main` is fetched, and tags that came with a clone are listed and ignored.
- **Fails closed:** every failure provoked ended in a refusal with a usable message; finding 5 is the one misleading one.
- **State messages:** the interrupt handler reports what exists, and the decline paths leave exactly what they say.
- **Display filter:** `clean` and its self-test work under dash and mawk, lose no lines on invalid input, and the CR, C1 and bidi marking fires where it should.
- **`sign-release.sh` order:** every check runs before the keystore is touched, and the certificate pin deletes a wrongly signed output.
- **Bootstrap text:** honest about what the tag proves, and the typed 12-character confirmation is better than a `y`.
