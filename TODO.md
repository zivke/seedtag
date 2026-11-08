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
  - [ ] Correct the README's claim that locking protects the tag. Recommend a metal box or shielding
    sleeve, and not carrying the tag around.
  - [x] Write password for MIFARE Ultralight C and NTAG213/215/216 (**Protect tag** after Verify).
  - [ ] Test the write password on spare Ultralight C and NTAG tags: protect, verify, remove, back up again;
    a second phone shouldn't be able to write to a protected tag.
  - [ ] Proper lock (settings frozen too), tested with spare tags.
  - [ ] Rescue for Ultralight C tags already locked with `FF 0F`.

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
