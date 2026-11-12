# Review of `tools/release-tag.sh`

- **Date:** 2026-11-12
- **Reviewed:** `tools/release-tag.sh` as added in `274d9c8` (unchanged in `8cfae6e`, Release 0.4.1), with
  `tools/sign-release.sh`, the README's "Signing a release", `.devcontainer/README.md` ("What the container can
  reach on the host") and `.github/workflows/build.yml` for context.
- **By:** an expert review on the Fable model, as a software developer and software security expert of 30+ years.
- **Threat model:** the dev container may be compromised. It can write the mounted project folder, push to GitHub as
  the user (forwarded credentials) and use keys in the forwarded SSH and GPG agents. So everything on GitHub
  (branches, tags, commit contents, the files the script parses) is attacker-controllable input. The release
  clone on the host is trusted.
- **What was run:** `dash -n` (syntax OK) and read-only inspection of the repository. `shellcheck` isn't installed.
  The planned sandbox tests (temporary `HOME`, throwaway key, local bare remote) were refused by the permission
  system, so **none of the findings was confirmed by running the script**. They come from the code and from
  documented git and OpenSSH behaviour.
- **Not accessed:** `other/`, any keystore, the SSH agent, the network.

## 1. Verdict

The script's skeleton is sound: it resolves the commit to a SHA once and tags exactly that, requires the tag name to be typed, pushes only `refs/tags/$TAG`, and re-verifies after signing and pushing. The weak part is the review: what the user is shown depends on data the remote controls. A pushed tag can become the "previous release" and shrink the reviewed range to nothing, a tag named `origin/main` shadows the branch, and commit subjects reach the terminal unsanitised. Second, "signed" means "signed by any key in a global `allowed_signers`", not "signed by the release key", which undermines the claim that the container cannot produce an acceptable tag. I would fix findings 1-4 before relying on it.

## 2. Findings

| # | Severity | Category | Finding |
|---|---|---|---|
| 1 | High | Review honesty / ref spoofing | `fetch --tags` imports remote tags; the highest `v[0-9]*` tag becomes `prev`, so a pushed tag hides commits from the review. An unsigned `prev` is only a warning. |
| 2 | High | Signature trust | Any key in the (global) `allowed_signers` is accepted, here and in `sign-release.sh`; only `user.signingkey` is checked against the agent. |
| 3 | Medium | Terminal injection | Commit subject and author name are printed raw to the terminal (lines 71, 114, 167); the paged views pass SGR sequences through `less -R`. |
| 4 | Medium | Ref spoofing | `$REMOTE/main` is resolved by short name; a fetched tag `refs/tags/origin/main` takes precedence over the remote-tracking branch. |
| 5 | Medium | Review coverage | The focused diff omits `gradlew`, `gradle.properties`, `.gitattributes`, `.gitmodules`, `.lfsconfig`, new Groovy build files and `buildSrc/`. |
| 6 | Medium | Review honesty | Binary-classified files show only "Binary files differ"; `.gitattributes` is read from the old working tree. Nothing flags or blocks this. |
| 7 | Medium | Process / code execution | Nothing ensures the running script comes from a reviewed release; the README table says "Pull", and after a pull the line-131 warning stays silent. |
| 8 | Medium | End-to-end guidance | Step 6 does not tell the user to check that the CI run is for the tagged commit; the container can re-trigger CI for the same tag name with other content. |
| 9 | Medium | Robustness | Quitting the pager early probably kills the script (SIGPIPE, exit 141 under `set -e`) with no message. |
| 10 | Low | Agent detection | "✓ not in the SSH agent" is also printed when no agent is reachable or the shell's `SSH_AUTH_SOCK` differs from the one VS Code forwards. |
| 11 | Low | Fail-open | Hooks and `core.hooksPath` only warn; `gpg.ssh.program`, `core.sshCommand` and `remote.*.pushurl` are not shown. |
| 12 | Low | Parsing | The `versionName` / `versionCode` sed patterns can read a different value than Gradle uses; multi-line results are not rejected cleanly. |
| 13 | Low | Code execution surface | The step-6 checkout runs global smudge filters (git-lfs) on attacker-chosen paths. |
| 14 | Low | Correctness | `~user/` paths and literal keys without `key::` are mishandled; the `allowed_signers` grep also matches comments and wrong namespaces. |
| 15 | Low | Usability | "Reviewed?" can be answered `y` after declining every view; it is the fifth identical y/N prompt. |
| 16 | Low | Usability | The changelog pager ignores `GIT_PAGER` / `core.pager` and lacks `-F`; the prompt shows the fetch URL, not the push URL. |
| 17 | Info | Robustness | No first release possible; `GIT_*` environment not cleared; no tty check on stdin; a push race leaves the local tag behind. |

## 3. Details

### 1. A remote tag can become `prev` and hide commits (High)

**Location:** `tools/release-tag.sh:104`, `:112`, `:130`.

**What's wrong:** `git fetch --tags` copies every remote tag into `refs/tags/`. `prev` is then the highest-sorting `v[0-9]*` tag merged into `$sha`. Whether `prev` is signed is "only informational".

**Scenario:** The container pushes malicious commit M to `main`, then the "Release 0.4.2" commit R on top, then a lightweight tag `v0.4.2-rc1` (or `v0.4.1.1`) pointing at M. Both sort above `v0.4.1`. The script prints `Previous: v0.4.2-rc1` and `3. Review v0.4.2-rc1..v0.4.2`, and shows one commit: the version bump. M is in neither the log nor any diff. The only hint is a yellow "isn't signed … only informational" line. With finding 2, even that turns into a green "is signed". The existing `v0.3.0` tag object in this repository carries no signature, so that warning has probably been normal on every run so far.

**Verified:** No, code reading. Version sort placing those names above `v0.4.1` is standard git behaviour.

**Fix:**
- Do not import remote tags: `git fetch --no-tags "$REMOTE" "+refs/heads/main:refs/remotes/$REMOTE/main"`. Line 118 already checks remote existence with `ls-remote`.
- Require `prev` to match `^v[0-9]+\.[0-9]+\.[0-9]+$`.
- Fail closed unless `prev` verifies with the release key; allow an explicit `--unsigned-previous vX.Y.Z` for the transition.
- Drop `--quiet` on the fetch so forced updates of `main` are visible.

```sh
signed_by_me() { _o="$(git verify-tag "$1" 2>&1)" && printf '%s\n' "$_o" | grep -qF "$fp"; }
signed_by_me "refs/tags/$prev" || die "$prev isn't signed with $fp: the review range can't be trusted."
```

### 2. "Signed" means any key in `allowed_signers` (High)

**Location:** `tools/release-tag.sh:89`, `:97`, `:130`; `tools/sign-release.sh:92`; README setup (`git config --global gpg.ssh.allowedSignersFile ~/.ssh/allowed_signers`).

**What's wrong:** The agent check covers only `user.signingkey`. `git verify-tag` accepts every key in the file with namespace `git`. The README points at the common global file, which often already holds an everyday commit-signing key that lives in the agent. `sign-release.sh` does not pin a fingerprint at all.

**Scenario:** With such a key in the file, the container signs `v0.4.2` on a malicious commit through the forwarded agent and pushes it; CI builds it. The user runs `release-tag.sh`. Line 104 fetches that tag, and line 117 says "v0.4.2 already exists here", which reads like "I already tagged this". The user checks it out and runs `sign-release.sh --expect <CI hash>`. `verify-tag` passes, the hash matches, and the APK is signed. The README's claim that the container "can't make one" is false in this configuration.

**Verified:** No, judgement from the code and the README. It depends on the contents of the host's `allowed_signers`.

**Fix:**
- Use a dedicated file set in the clone's local config, e.g. `~/.ssh/seedtag_allowed_signers`, and die unless every non-comment line in it contains `$material`.
- Alternatively, or additionally, die if any key in `$asf` appears in `ssh-add -L`.
- In `sign-release.sh`, compare the signer fingerprint as lines 161-163 here already do.
- Reword lines 117-118: "If you didn't create it in this clone, something else pushed it: do not check it out or sign it."

### 3. Terminal escape injection (Medium)

**Location:** `:71`, `:114`, `:167` (direct to the terminal); `:141`, `:142`, `:147`, `:148` (via pager).

**What's wrong:** Git does not sanitise `%s` or `%an`. Tag names and quoted paths are safe; commit text is not. In the pager, `less -R` passes SGR sequences, and OSC 8 hyperlinks on the less 590 found here.

**Scenario:** A commit subject such as `Release 0.4.2<ESC>[1A<ESC>[2K\rCommit:  <fake line>` overwrites the "Commit:" line at 114. Conceal (`ESC[8m`) or alternate-screen sequences can hide the lines that follow, including the `!` warnings at 129-133, or draw fake "✓" lines. In a diff, `ESC[8m` in the middle of a source line conceals the rest of that line until git's end-of-line reset.

**Verified:** No (blocked). Raw output of `%s` is long-standing git behaviour.

**Fix:**
```sh
clean() { LC_ALL=C tr -d '\000-\010\013-\037\177'; }   # keeps TAB and LF
say "Commit:         $(git log -1 --format='%H%n                %s (%an, %ad)' --date=short "$sha" | clean)"
```
- For the paged views: `git --no-pager -c color.ui=never log … | clean | less -FX` (no `-R`).
- Optionally warn if the diff contains bidi controls (U+202A-202E, U+2066-2069).

### 4. `origin/main` is resolved by short name (Medium)

**Location:** `:105-107`, `:128`.

**What's wrong:** Git's lookup order puts `refs/tags/<name>` before `refs/remotes/<name>`. A fetched tag named `origin/main` therefore wins for the default `REV`, the ancestor check and the `ahead` count.

**Scenario:** The container pushes tag `origin/main` pointing at commit X that is on no branch. The script tags X; the "isn't on origin/main" check compares X with itself. GitHub's `main` never shows X. Git prints an "ambiguous refname" warning on stderr, which is easy to miss among the output.

**Verified:** No; the order is documented in gitrevisions(7).

**Fix:** Use full names everywhere: `main_ref="refs/remotes/$REMOTE/main"`, `refs/tags/$TAG`, `refs/tags/$prev`. The `--no-tags` fetch from finding 1 removes the vector as well.

### 5. Focused diff misses files that drive the build (Medium)

**Location:** `:139-140`.

**What's wrong:** Not in `REVIEW`: `gradlew`, `gradlew.bat`, `gradle.properties`, `.gitattributes`, `.gitmodules`, `.lfsconfig`, `buildSrc/`, and newly added `settings.gradle`, `build.gradle` or `app/build.gradle`. I believe Gradle prefers the Groovy files over `.kts`, but I did not check that.

**Scenario:** A changed `gradlew` (run by `make build` in the container and in CI) tampers with the APK identically in both places, so `--expect` matches. The prompt "what builds, checks and signs the release" shows nothing about it. It appears only in the whole diff, among all the Kotlin changes.

**Verified:** File list checked against `git ls-files`; the rest is judgement.

**Fix:** Invert the list so everything is shown except known source and docs:
```sh
git diff "$prev" "$sha" -- . ':(exclude)app/src' ':(exclude)core/src' ':(exclude)art' \
    ':(exclude)*.md' app/src/main/AndroidManifest.xml
```

### 6. Binary diffs and `.gitattributes` from the working tree (Medium)

**Location:** `:145-148`.

**What's wrong:** A file with a NUL byte in its first 8000 bytes, or one marked `-diff` / `binary`, produces only "Binary files differ". `git diff <a> <b>` reads attributes from the checked-out tree, which is the previous release, not from `$sha`.

**Scenario:** Release N adds a `.gitattributes` line such as `core/src/main/kotlin/**/SeedBox.kt -diff` among harmless ones (the file is outside `REVIEW`, finding 5). Step 6 checks out N. In release N+1 the crypto change shows as one "Binary files differ" line. Alternatively, a NUL in a comment at the top of a file achieves the same within one release.

**Verified:** No. Whether each toolchain tolerates a NUL was not tested.

**Fix:** Die on unexpected binaries, and take attributes from the commit being tagged (git ≥ 2.40):
```sh
bin="$(git diff --numstat "$prev" "$sha" | awk -F'\t' '$1 == "-" { print $3 }' \
    | grep -vE '^(art/|gradle/wrapper/gradle-wrapper\.jar$)' || true)"
[ -z "$bin" ] || die "Binary (undiffable) changes: $bin"
# and: git --attr-source="$sha" diff …
```

### 7. The running script may itself be unreviewed (Medium)

**Location:** `:71`, `:131-134`; README table row "Release clone … Pull, review, signed tag".

**What's wrong:** The header promises that the running copy is "from the last release", but nothing enforces it. `git clone` checks out `main`, and the README table says "Pull". After a pull, `here == sha`, so the line-131 comparison shows no difference and prints nothing.

**Scenario:** The user runs `git pull` in `~/seedtag-release` as the table suggests, then runs `tools/release-tag.sh`, which is now the container's version, on the host next to the signing key and keystore.

**Verified:** No, judgement. A check inside the script cannot stop a replaced script; it only catches the honest mistake.

**Fix:**
- Change "Pull" in the README table to "Fetch (never pull or check out main)".
- In the script, die unless `HEAD` is exactly a `v*` tag that passes `signed_by_me`, with an explicit flag for the first run.
- Warn when `here = sha`.

### 8. Step 6 does not bind the CI run to the tagged commit (Medium)

**Location:** `:185-188`.

**What's wrong:** After the push, the container can force-move the remote tag or dispatch the workflow by hand. Either way a later run carries the same tag name but a workflow, summary text and embedded `revision` that the attacker controls.

**Scenario:** The container force-pushes `v0.4.2` to a commit whose build forges `version-control-info.textproto` to name the real tagged commit. The user takes the newest "v0.4.2" run, copies its hash, and `sign-release.sh` accepts it: the hash matches and the revision "matches".

**Verified:** No, judgement. It needs GitHub and is outside the script proper.

**Fix:**
- Print the full `$sha` and state that the run's commit in GitHub's own run header (not the summary) must equal it.
- Have `sign-release.sh` check that `git ls-remote <remote> refs/tags/$TAG` still equals the local tag object.
- A tag ruleset forbidding updates to `v*` would help.

### 9. Quitting the pager aborts the script (Medium)

**Location:** `:141`, `:147`, `:148`.

**What's wrong:** A command in a `then` body is subject to `set -e`. If `q` is pressed while git still has output queued (more than the pipe buffer), git dies of SIGPIPE with status 141 and the script exits silently in the middle of the review.

**Scenario:** The user opens "the whole diff" for a release the size of 0.4.0, reads two pages, presses `q`, and is back at the shell prompt with no explanation. That teaches them to answer "n" to the big diff.

**Verified:** No (it needs a pty; blocked). I recall git keeps the 141 status in this case.

**Fix:**
```sh
show() { "$@" || [ $? -eq 141 ] || die "git failed: $*"; }
```

### 10. Agent check can pass vacuously (Low)

**Location:** `:89-93`.

**What's wrong:** If `ssh-add -L` cannot reach an agent (exit 2), the pipeline is simply empty and the script prints "✓ The signing key isn't in the SSH agent". A terminal whose `SSH_AUTH_SOCK` differs from the desktop session's gives the same result.

**Scenario:** The script runs from tmux or an SSH session, while VS Code forwards gnome-keyring's agent, which offers every key pair in `~/.ssh`.

**Verified:** No, judgement.

**Fix:** Distinguish exit 0, 1 and 2. On 2 print "no agent reachable from this shell; can't check the one VS Code forwards" as a warning, not a ✓. Print `$SSH_AUTH_SOCK`.

### 11. Hooks only warn (Low)

**Location:** `:73-75`.

**What's wrong:** `reference-transaction`, `pre-push` and `post-checkout` hooks would run during fetch, tag, push and checkout. `.git` may be a file in a worktree, which breaks the `ls`. `gpg.ssh.program`, `core.sshCommand` and `remote.*.pushurl` are not shown.

**Scenario:** A leftover or planted hook runs at the fetch on line 104, one screen after a yellow warning.

**Verified:** No.

**Fix:** `git() { command git -c core.hooksPath=/dev/null "$@"; }`, or die on hooks. Print `gpg.ssh.program` if it is set.

### 12. Version parsing (Low)

**Location:** `:108-110`, `:119-120`.

**What's wrong:** `^ *versionName = "…"` misses tab-indented or differently spaced lines and matches lines inside comments or other blocks. `grep -q` passes if any line of a multi-line result matches; the run then fails later with git's or dash's own error ("Illegal number").

**Scenario:** A decoy `versionName = "0.4.2"` in a block comment, with the real value written as `versionName="9.0.0"`. The tag says `v0.4.2` while the APK says 9.0.0. The `versionCode` downgrade check can be fooled the same way.

**Verified:** No. The file is in the focused diff, hence Low.

**Fix:** Require exactly one match: `[ "$(printf '%s\n' "$ver" | wc -l)" -eq 1 ]`. Count `versionName` occurrences with a loose pattern and die if there is more than one. Validate `code` and `pcode` with a `case` for digits only.

### 13. Checkout runs smudge filters (Low)

**Location:** `:183`.

**What's wrong:** The container's `~/.gitconfig` has `filter.lfs.*`. If that was copied from the host, as VS Code usually does, the host has git-lfs set up as well.

**Scenario:** `.gitattributes` and `.lfsconfig` in the tagged commit make `git checkout` run `git-lfs smudge` against a URL chosen by the attacker. Both files are outside `REVIEW`.

**Verified:** The container config was observed; the host is judgement.

**Fix:** `GIT_LFS_SKIP_SMUDGE=1 git -c filter.lfs.smudge= -c filter.lfs.process= -c filter.lfs.required=false checkout …`, and add both files to the review (finding 5).

### 14. Key and path handling (Low)

**Location:** `:80-83`, `:96-97`.

**What's wrong:**
- Git expands `~user/` in these settings; the sed only handles `~/`, and breaks if `$HOME` contains `|` or `&`.
- Git accepts a literal `ssh-ed25519 AAAA…` without `key::`; the script treats it as a file name.
- The grep at 97 matches commented-out lines, lines with `namespaces="file"`, and expired `valid-before` entries.

**Scenario:** `user.signingkey = ~nikola/.ssh/k.pub` gives "file doesn't exist". A commented-out key passes line 97, and the failure only appears after the tag is created (it is deleted again, so this fails closed).

**Verified:** No.

**Fix:** `git config --path user.signingkey` and `git config --path gpg.ssh.allowedSignersFile`. Accept `ssh-*` literals. Check with `ssh-keygen -Y find-principals`, or use the single-key file from finding 2.

### 15. The review confirmation is weak (Low)

**Location:** `:141-149`.

**What's wrong:** Four "Show …? [y/N]" prompts are followed by "Reviewed …? [y/N]" in the same form. Answering n, n, n, n, y passes.

**Scenario:** A hurried patch release: the user skips every view and confirms out of habit.

**Verified:** Code reading.

**Fix:** Track what was viewed. If the focused diff was not shown, say so ("You did not view: …") and require a typed word, or the short hash of `$sha`, for the review confirmation.

### 16. Pager and URL display (Low)

**Location:** `:142`, `:102`, `:172`.

**What's wrong:** `${PAGER:-less -R}` ignores `GIT_PAGER` and `core.pager`, and without `-F` a ten-line section needs a `q`. The push prompt shows `git remote get-url`, which is the fetch URL.

**Scenario:** With `remote.origin.pushurl` set, the tag goes to a different place than the prompt shows.

**Verified:** No.

**Fix:** `git remote get-url --push "$REMOTE"`. Use one `show` helper with the same pager for every view.

### 17. Informational

- `:113` — no first release is possible without a `v*` tag. Not relevant at 0.4.x.
- Clear `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE` and `GIT_CONFIG_*` at the top, so a stray environment cannot point git at the mounted folder.
- Add `[ -t 0 ] || die`. Today a non-tty stdin fails closed anyway, because every `read` defaults to "no".
- `:176` — if the tag appears on the remote between lines 118 and 176, the push is rejected and `set -e` exits, leaving the local tag with no hint.
- A moved remote tag makes the fetch fail ("would clobber existing tag") and the script stops. That is the right outcome, but the message is git's only.

## 4. What the script does well

- It resolves `$sha` once and uses the SHA for every later step: no time-of-check/time-of-use gap between review and `git tag`.
- It diffs two endpoints (`prev` and `sha`), not a three-dot range.
- It requires the tag name to be typed, builds the tag message from a regex-validated version, and pushes only `refs/tags/$TAG`.
- After signing it checks `verify-tag`, the fingerprint and the peeled commit, and deletes the tag on any mismatch. After pushing it compares `ls-remote` with the local tag object.
- It never touches the working tree until the optional final checkout.
- Every prompt defaults to "no", and EOF counts as "no".
- Ref names cannot carry control characters and git quotes odd paths, so `--stat` output and tag names are safe.
- Quoting is correct throughout; the only unquoted expansion (`$REVIEW`) is a constant.
