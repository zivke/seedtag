# TODO

## Security (open items from [the security review](reports/2026-11-07-security-review.md))

- [x] **#1 (High): move release signing out of the dev container.** The key and `KEY_PASS` can be reached
  by anything that runs commands in it, and the `android-keys` volume is shared by every Android project.
  Sign on a separate offline machine, or at least stop mounting the key by default and give each
  project its own volume.
- [x] **#5 (Medium): block accessibility services on Android 14+.** `setAccessibilityDataSensitive(YES)`
  on the window and dialogs, plus a banner naming apps with accessibility access on every version.
- [x] **#7 (Medium): verify the build supply chain.**
  - Add Gradle dependency verification and `distributionSha256Sum`.
  - Pin the Docker image digest and checksum the Android tools zip.
  - Publish each release APK's SHA-256.
- [ ] **#11 (Medium): a "read-only" tag can still be locked against reading by a stranger.** Options are
  in [the tag write-protection report](reports/2026-11-07-tag-write-protection.md). In order:
  - [ ] After locking, read the lock and settings bytes back, and warn if the settings are still open.
  - [x] Correct the README's claim that locking protects the tag. Recommend a metal box or shielding
    sleeve, and not carrying the tag around.
  - [ ] Proper lock (settings frozen too) or a write password, on a separate branch, tested with spare
    tags.
  - [ ] Rescue for Ultralight C tags already locked with `FF 0F`.

## Project review (open items from [the 2026-11-10 project review](reports/2026-11-10-project-review.md))

- [ ] **#1 (Medium): the Lock text promises more than the lock does.** Same issue as #11 above.
  - [x] Corrected wording in the app and README.
  - [ ] Lock read-back (#11's first step): after the `tag-write-password` branch, which already detects the
    chip type.
- [x] **#2 (Medium): check that the signed APK was built from this source.** Build again in an
  independent environment (CI or the signing machine), compare, and make `sign-release.sh` refuse an
  unsigned APK whose SHA-256 doesn't match the expected one.
- [x] **#3 (Medium): bind a confirmed overwrite to the tag that was confirmed.** Carry its UID and
  existing text in the request, as `Lock` does, so a different tag gets a fresh "not empty" check.
- [x] **#4 (Medium): adb and the phone used for real seeds.** README: no real seed on a phone with USB or
  wireless debugging enabled or authorised; revoke authorisations first. The shared adb volume is kept;
  `.devcontainer/README.md` explains the risk and how to scope it per project.
- [x] **#5 (Medium-Low): stray spaces in a new password.** Refuse or confirm leading, trailing or repeated
  whitespace when the password is set, in the app and in `seedtag.py encrypt`.
- [x] **#6 (Medium-Low): the strength estimate over-credits typed BIP39 phrases.** Full 11 bits per word
  only for the generated passphrase; discount typed word phrases and flag runs of adjacent list indexes.
- [x] **#7 (Medium-Low): `seedtag.py` KDF fallback.** Also fall back on `UnsupportedAlgorithm`, test the
  vectors with every installed backend, and say which backend was used.
- [x] **#8 (Low): drop touches with `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`** (Android 10–11), at least on the
  confirm buttons, in the activity and in `GuardDialogWindow`. Still to try on an Android 10 or 11 phone.
- [x] **#9 (Low): zeroable keypad prefix.** Keep it in a `CharArray`, show bullets while words are
  hidden, and make the README say plainly what's left in memory.
- [x] **#10 (Low): `TagIo.perform` must never throw.** Final catch-all with a generic message; catch
  `OutOfMemoryError` around Argon2.
- [x] **#11 (Low): overwrite safety at the edges.**
  - [x] A format guard for Type 5 tags (MIFARE Classic isn't covered). Still to try on a Type 5 tag.
  - [x] A message that says an interrupted overwrite may have destroyed the old content.
- [ ] **#12 (Low): maintenance.**
  - [x] Bouncy Castle 1.78.1 → 1.86.
  - [ ] Update AGP (8.7.3; 8.13.2 / 9.5.1 out), Kotlin (2.0.21; 2.4.21 out), the Compose BOM (2024.12.01;
    2026.09.00 out), activity and lifecycle; check the Play target-API requirement. Needs a newer
    platform and build-tools in the Dockerfile, so a container rebuild.
  - [x] CI running `make test`, `make build` and `check-apk` (`.github/workflows/build.yml`).
  - [ ] Split `Ui.kt` by screen, after `tag-write-password` is merged (it changes `Ui.kt` a lot).
  - [ ] Move the tag-flow logic into a pure-Kotlin state machine with tests, after `tag-write-password` is
    merged (it changes the view model and `TagIo`).
  - [x] Clarify the wrapper wording (8.14 jar, 8.11.1 distribution: both were right); remove the unused
    `Bip39.normalize(String)`.

## Expert review (open items from [the 2026-11-10 expert review](reports/2026-11-10-expert-review.md))

- [x] **#1 (Medium): the strength meter rates well-known weak passwords "Strong".** Only the generated
  passphrase earns "Strong"; always confirm a typed password. If an estimate stays, fold case before the
  BIP39 lookup, split on any non-letter and count repeats once. Add the report's examples to the tests.
- [x] **#2 (Medium): a generated passphrase is never checked against the written copy.** Leave Repeat
  empty and have the user type it back from paper; confirm before Generate replaces one.
- [ ] **#3 (Medium): `--expect` proves the build, not the source, and the container can push to GitHub.**
  - [ ] Sign from a fresh clone on the signing machine, and review `git diff <last tag>..<new tag>` there
    (README).
  - [ ] Signed release tags (`git tag -s`, key outside the container), checked by `sign-release.sh`.
  - [ ] Make `--expect` mandatory, with an explicit `--no-expect`.
  - [ ] No push credentials in the container (turn off VS Code's credential forwarding); protect `v*`
    tags on GitHub.
- [ ] **#4 (Medium-Low): recovery needs third-party packages.**
  - [x] Catch `ImportError` in `seedtag.py` and say what to install; state the minimum Python version.
  - [x] README and Done screen: keep a copy of `seedtag.py` with the written password.
  - [ ] Pure-Python decrypt-only fallback (Argon2id and AES-GCM), tested against the vectors.
- [ ] **#5 (Low): the `cryptography` Argon2id backend is never tested.** CI step with a hash-pinned
  `cryptography` ≥ 44 that fails on a skipped backend; add 18- and 21-word vectors.
- [x] **#6 (Low): Argon2's 64 MiB on the Java heap.** `android:largeHeap="true"`, tried on an old phone.
- [ ] **#7 (Low): screen timeout or Back wipes everything mid-entry.** Keep the screen on while secrets
  are shown, with the app's own idle timeout; map Back to the step's Back button.
- [x] **#8 (Low): an old app meeting a newer format.** "Written by a newer version" message, and treat any
  plausible SeedTag blob version as a backup in the overwrite warning, in the app and `seedtag.py`.
- [ ] **#9 (Low): Lock says "now permanently read-only" for a tag that was already not writable.**
  - [x] Separate result and message; the "can't be made read-only" message and the README say that Android
    usually only locks Type 1 and 2 tags.
  - [ ] Test Lock on a Type 4 and a Type 5 tag, and note the result in the README table.
- [x] **#10 (Low): no warning about adb.** Preflight item for USB / wireless debugging; listen for
  accessibility changes while in the foreground.
- [x] **#11 (Low): a BIP39 passphrase ("25th word") isn't stored.** Say so on the Words step and in the
  README; mention English phrases only.
- [x] **#12 (Low): late tag results can leave a scan armed with no prompt.** Close the prompt only when
  nothing is pending; ignore a second Encrypt while busy. Pin it down with the tag-flow state machine,
  extracted before `tag-write-password` is merged.
- [x] **#13 (Low): Kotlin and Python disagree on two password-variant edge cases.** Explicit whitespace
  list in both, two new `password-variants.tsv` rows, wrap the encoder failure in a `SeedTagException`.
- [ ] **#14 (Info): an unreadable tag is treated as empty.** Optional Type 2 hardening: check the NDEF TLV
  length before writing without confirmation.
- [ ] **#15 (Info): build checks.** `check-apk.sh` could reject `debuggable` / `testOnly` and extra
  components; check the two action SHAs by hand against their release tags.

## App and docs

- [x] Make the on-screen warnings more prominent.
- [x] Warn that if the encryption password is lost, there's no way to restore the seed phrase.
- [x] Warn users to read the on-screen instructions carefully.
- [x] Explain that users need to have their NFC tag ready beforehand, and to hold it against the back of
  the phone when the app asks.
- [x] Add haptic feedback.
- [x] Explain the verification procedure in more detail on screen.
- [x] Make Verify and Restore multi-step procedures like Back up:
  - remove the password field once it has done its job;
  - add "Verify another tag" and "Restore another tag" options;
  - etc.
- [x] Add an About page.
- [x] Add a separate option that helps users find their phone's NFC antenna beforehand.
- [x] Add a disclaimer, in the app and the README:
  - The app comes without any guarantees, and the developer is not responsible for any damage.
  - Users must make sure for themselves that no malware is already installed on their phone. The app
    can't cover every possible attack vector.
