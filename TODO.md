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
- [ ] **#2 (Medium): check that the signed APK was built from this source.** Build again in an
  independent environment (CI or the signing machine), compare, and make `sign-release.sh` refuse an
  unsigned APK whose SHA-256 doesn't match the expected one.
- [ ] **#3 (Medium): bind a confirmed overwrite to the tag that was confirmed.** Carry its UID and
  existing text in the request, as `Lock` does, so a different tag gets a fresh "not empty" check.
- [ ] **#4 (Medium): adb and the phone used for real seeds.** README: no real seed on a phone with USB or
  wireless debugging enabled or authorised; revoke authorisations first. Consider a per-project adb
  volume.
- [ ] **#5 (Medium-Low): stray spaces in a new password.** Refuse or confirm leading, trailing or repeated
  whitespace when the password is set, in the app and in `seedtag.py encrypt`.
- [ ] **#6 (Medium-Low): the strength estimate over-credits typed BIP39 phrases.** Full 11 bits per word
  only for the generated passphrase; discount typed word phrases and flag runs of adjacent list indexes.
- [ ] **#7 (Medium-Low): `seedtag.py` KDF fallback.** Also fall back on `UnsupportedAlgorithm`, test the
  vectors with every installed backend, and say which backend was used.
- [ ] **#8 (Low): drop touches with `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`** (Android 10–11), at least on the
  confirm buttons, in the activity and in `GuardDialogWindow`.
- [ ] **#9 (Low): zeroable keypad prefix.** Keep it in a `CharArray`, show bullets while words are
  hidden, and make the README say plainly what's left in memory.
- [ ] **#10 (Low): `TagIo.perform` must never throw.** Final catch-all with a generic message; catch
  `OutOfMemoryError` around Argon2.
- [ ] **#11 (Low): overwrite safety at the edges.** A format guard for non-Type-2 tags, and a message that
  says an interrupted overwrite may have destroyed the old content.
- [ ] **#12 (Low): maintenance.**
  - [ ] Update AGP, Kotlin, Compose and Bouncy Castle; check the Play target-API requirement.
  - [ ] CI running `make test`, `make build` and `check-apk`.
  - [ ] Split `Ui.kt` by screen.
  - [ ] Move the tag-flow logic into a pure-Kotlin state machine with tests.
  - [ ] Fix the "Gradle 8.14" wrapper wording; remove or mark test-only `Bip39.normalize(String)`.

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
