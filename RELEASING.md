# Releasing SeedTag

Every step from the version bump to the published APK. Why each step is there and what the scripts check is in the
README: [Signing a release](README.md#signing-a-release) and [Build integrity](README.md#build-integrity). Releases
are made only by the maintainer; nothing here runs automatically.

| Where | What happens there |
|---|---|
| Dev container | Changelog, version, release commit, `make build`, test builds for the phone. Push `main` with VS Code. **No tags, no signing keys.** |
| GitHub Actions | Builds the unsigned APK of `main` (by hand) and of every pushed `v*` tag, and shows its SHA-256. |
| Release clone on the host (`~/seedtag-release`) | Review, signed tag, push of the tag, signing the CI-built APK, publishing. Never opened in a container; never `git pull` there. |
| Your phone | Release-candidate test, then the signed APK over the previous release. |

## Once: set up the host

**Tools on the host:**
- git 2.34 or later;
- OpenSSH's `ssh-keygen`;
- `unzip`, `iconv`, `awk`, `tr`, `sed` and a POSIX `sh` (all standard on Linux and macOS);
- `sha256sum`, or `shasum` on macOS;
- the Android SDK build-tools, for `apksigner` and `aapt2`, through `ANDROID_HOME` or on `PATH`, plus the JDK that
  `apksigner` needs;
- `adb`, to install on the phone;
- optionally `gh`, the GitHub CLI, to publish from the terminal.

**The Android release keystore:** the `.jks` file that signs every SeedTag APK. Its certificate digest is in
`tools/release-cert.sha256`:

```
29370bf9dc6a272f18b3fbac01837c262bcec4035de162dad4fc5175291ae82c
```

It stays on the host and never enters the container. Keep a backup somewhere else: without it, no update can ever be
installed over the app.

**The tag-signing key, its allowed-signers file and the release clone:**

```sh
# a signing key for releases only, with a passphrase; never ssh-add it
ssh-keygen -t ed25519 -C "release signing" -f ~/.ssh/seedtag_signing
# an allowed-signers file with only that key (git verify-tag accepts every key in it)
printf '%s namespaces="git" %s\n' "<your git email>" "$(cut -d' ' -f1,2 ~/.ssh/seedtag_signing.pub)" \
    > ~/.ssh/release_allowed_signers

git clone https://github.com/zivke/seedtag.git ~/seedtag-release && cd ~/seedtag-release
git config gpg.format ssh
git config user.signingkey "$HOME/.ssh/seedtag_signing.pub"
git config gpg.ssh.allowedSignersFile "$HOME/.ssh/release_allowed_signers"
git config tag.gpgSign true
```

Notes on this setup:
- **Local settings only:** all of these are set in the release clone, not with `--global`.
- **Keep the key out of every agent:** VS Code forwards the SSH agent into the container, so a key in it could sign a
  tag "as you" from there. Git reads the key file and asks for its passphrase instead. Make sure nothing loads it for
  you: `AddKeysToAgent` in `~/.ssh/config`, or a desktop keyring. A hardware key (`-t ed25519-sk`, a touch per
  signature) works too.
- **Other projects:** the same key and file can serve them, if they're released the same way, never from a container
  or CI.
- **On GitHub (Settings › SSH and GPG keys):** add `~/.ssh/seedtag_signing.pub` as a **Signing Key**, so the tags
  show as verified. It grants no access.
- **Pushing from the host:** the release clone needs its own way to push the tag, HTTPS with your credential
  manager or an SSH login key.
- **Runners:** once, look at the repository's Settings › Actions › Runners. There should be no self-hosted runner;
  the workflow is meant to run on GitHub's own machines.

**After any fresh clone** of the release clone (a new machine, a deleted folder), it is on `main`, which the container
can write. Before running anything in `tools/`, check out the last release:

```sh
git verify-tag vX.Y.Z && git checkout vX.Y.Z
```

The first signed release is the exception (see below).

## 1. Prepare the release (dev container)

1. **Pick the version.** Read `## [Unreleased]` in `changelog.md` and apply the semver rules in CLAUDE.md
   (Conventions):
   - any `Added` entry is a MINOR release;
   - only fixes and changes are a PATCH;
   - something older versions can't read is a MAJOR (MINOR while 0.x).
2. **Test a release candidate on the phone.** `make build-test` builds the release build (R8 on), signed with the
   debug key; `make run-test` installs it. A phone with the release-signed app needs `make uninstall` first: the
   signatures differ. SeedTag stores nothing, so nothing is lost. Run:
   - the [short regression check](reports/2026-11-11-manual-test-plan.md#short-regression-check-for-every-release);
   - the test plan rows for what changed since the last release.

   Record the results in the test plan.
3. **The release commit.** Ask Claude for it, or do it yourself:
   - rename `[Unreleased]` to `[X.Y.Z] - YYYY-MM-DD`;
   - in `app/build.gradle.kts`, add 1 to `versionCode` and set `versionName` to `X.Y.Z`;
   - commit as "Release X.Y.Z";
   - `make build`, and note the SHA-256 it prints for `seedtag-unsigned.apk`.
4. **Push `main`** with VS Code's Source Control. Don't create or push any tag from the container.

## 2. Check CI before tagging

In the GitHub repository: Actions › build › **Run workflow** on `main`. When it's done, its summary shows the
SHA-256 of `seedtag-unsigned.apk` for the release commit.
- **Same as the container's `make build`:** the build is reproducible, so the tag's run will give the same hash.
- **Different:** stop and don't tag. Download the run's artifact, unzip both APKs and `diff -r` them (or use
  diffoscope), fix the cause, and make a new release commit.

## 3. Create and push the signed tag (release clone, host)

```sh
cd ~/seedtag-release
git describe --exact-match HEAD          # should name the last release tag
tools/release-tag.sh --dry-run           # every check and the review, no tag
tools/release-tag.sh                     # the same, then: type the tag name, passphrase, push
```

Add `--commit <sha>` if the release commit isn't the tip of `main`, and `--remote <name>` if the GitHub remote isn't
`origin`. The script asks before every step. Read the review: it shows everything since the last signed release. At
the end it offers to check out the new tag (say yes) and prints the commit to compare in the next step. If it stops,
its message says why and what to do.

## 4. The tag's CI run

The pushed tag starts the workflow. On GitHub's run page for it:
- **Event:** the header says a push of `vX.Y.Z`.
- **Commit:** the header shows the commit `release-tag.sh` printed. Use the header, not the summary text, which the
  build itself writes.
- **One run:** there is exactly one run for the tag. Several runs, or none, means stop: something re-ran, moved or
  deleted it.

This check is the only link between your tag and the APK; no script can make it for you. Then:
- **Hash:** copy the SHA-256 from the summary. It should also equal the container's from step 1.
- **APK:** download the artifact (`seedtag-unsigned-<commit>.zip`) into the release clone and unzip it there; it
  holds `seedtag-unsigned.apk` and its `.sha256`.

## 5. Sign (release clone, host)

```sh
git describe --exact-match HEAD          # vX.Y.Z: the new tag
tools/sign-release.sh --expect <SHA-256 from the run> /path/to/release-key.jks
```

It checks the APK's SHA-256, the clone, the tag (signed with your key, unchanged on GitHub), the commit the APK was
built from and its permissions, all before `apksigner` asks for the keystore password. Then it signs, refuses any
certificate but the release one, and writes `seedtag.apk` and `seedtag.apk.sha256`.

## 6. Test the signed APK

Install it over the previous release on your phone, for example `adb install -r seedtag.apk` from the release
clone. It must install as an update: the same certificate as before. Run the short regression check again, on this
APK, and record it in the test plan.

## 7. Publish

For now, releases are published only as a **GitHub Release**. Publish from the release clone on the host, never
from the container: the files come from `sign-release.sh` there.

**With `gh`:**

```sh
gh release create vX.Y.Z seedtag.apk seedtag.apk.sha256 --verify-tag --title "SeedTag X.Y.Z" --notes-file notes.md
```

`--verify-tag` refuses if the tag isn't on GitHub, so GitHub never creates a tag of its own.

**Or on the web:** Releases › Draft a new release:
1. Choose the existing tag `vX.Y.Z`. Never "create new tag".
2. Title: `SeedTag X.Y.Z`.
3. Notes: as below.
4. Attach `seedtag.apk` and `seedtag.apk.sha256`, then publish.

**The notes:**
- the changelog section, plus those of any versions that were never published (see below);
- the SHA-256 of `seedtag.apk`, from `seedtag.apk.sha256`;
- the release certificate digest above, with "check it with `apksigner verify --print-certs seedtag.apk`";
- the beta notice: it has been tested only with MIFARE Ultralight C tags.

**Then check what GitHub serves.** Download `seedtag.apk` from the release page and compare its SHA-256 with your
`seedtag.apk.sha256`. The container has your GitHub credentials, so it could replace a release asset later. The
published hash and certificate digest are what let anyone, you included, notice that. Keep your copies of
`seedtag.apk` and `seedtag.apk.sha256`.

## 8. After the release

- **Stay at the new tag:** leave the release clone checked out at it. It's where the next review starts, and the copy
  of `release-tag.sh` the next release runs.
- **Test plan:** commit the results recorded in it.
- **Changelog:** new changes go under a fresh `## [Unreleased]` at the top of `changelog.md`.

## The first signed release (0.4.2)

Tags up to `v0.3.0` aren't signed; 0.4.0 and 0.4.1 were never tagged or published. So for 0.4.2:

1. **Set up the host** as above. The fresh release clone is on `main`, and that's correct this one time:
   `v0.3.0` doesn't contain `release-tag.sh`. Read `tools/release-tag.sh` there before running it.
2. **Find the commit 0.3.0 was built from.** Check the 0.3.0 APK you signed or installed, your own copy, not one from
   the container's folder:

   ```sh
   apksigner verify --print-certs seedtag-0.3.0.apk | grep 'SHA-256'           # the digest above
   unzip -p seedtag-0.3.0.apk META-INF/version-control-info.textproto          # revision: "…"
   ```

   The signed 0.3.0 APK in the dev folder records `a8bff1674849bda3c24223ce24b6b33c986d5ffd`. That's 17 commits after
   the `v0.3.0` tag (`38cb4db…`).
3. **Run with `--bootstrap`.** If your APK shows that commit too:

   ```sh
   tools/release-tag.sh --dry-run --bootstrap a8bff1674849bda3c24223ce24b6b33c986d5ffd
   tools/release-tag.sh --bootstrap a8bff1674849bda3c24223ce24b6b33c986d5ffd
   ```

   If it shows `38cb4db…`, use `--bootstrap v0.3.0` instead. The script shows the commit and asks you to type its
   first 12 characters, after checking it against your evidence.
4. **Steps 4–8 as usual.** The release notes should include the 0.4.0 and 0.4.1 sections of the changelog: anyone
   updating comes from 0.3.0.
5. **From now on,** the clone sits at `v0.4.2`, signed with your key, and later releases need no `--bootstrap`.

## If something goes wrong

- **CI's hash differs from the container's:** don't sign. Compare the two APKs and find out why first.
- **A wrong commit was tagged and pushed, or a release was published with a problem:** don't move, delete or reuse a
  tag that was pushed. Fix it on `main` and release the next PATCH version.
- **A script stops or is interrupted:** its message says what exists at that point (for example a local tag that
  wasn't pushed) and what to do.
- **`sign-release.sh` deleted its output:** it was signed with another certificate. Check which keystore and alias
  you gave it.

## Other channels (not set up)

If SeedTag is published elsewhere later, these change the steps above:

- **Google Play:**
  - Play expects an Android App Bundle, so the build would need a bundle target.
  - With Play App Signing, Google signs what users install, so the certificate digest differs from the one above.
    A Play install and a GitHub APK can then never update each other, and the published digest has to say which
    channel it belongs to.
- **F-Droid:**
  - F-Droid builds from source and normally signs with its own key: the same split as with Play.
  - Because SeedTag's release build is reproducible, F-Droid can instead check its build against the APK signed
    here and ship that one, so one certificate covers both channels.
  - It needs F-Droid metadata in the repository and stable tags. Both already fit this flow.

Whatever the channel, the APK must come out of this flow (tag, CI, `sign-release.sh`) or an independent build of the
same tag, never out of the dev container.
