# TODO

## Security (open items from [the security review](reports/2026-11-07-security-review.md))

- [ ] **#1 (High): move release signing out of the dev container.** The key and `KEY_PASS` can be reached
  by anything that runs commands in it, and the `android-keys` volume is shared by every Android project.
  Sign on a separate offline machine, or at least stop mounting the key by default and give each
  project its own volume.
- [ ] **#5 (Medium): block accessibility services on Android 14+.** Set
  `View.setAccessibilityDataSensitive(YES)` on the root view and check on a device that Compose honours
  it. The risk is already documented in the README.
- [ ] **#7 (Medium): verify the build supply chain.**
  - Add Gradle dependency verification and `distributionSha256Sum`.
  - Pin the Docker image digest and checksum the Android tools zip.
  - Publish each release APK's SHA-256.
- [ ] **#11 (Medium): a "read-only" tag can still be locked against reading by a stranger.** Options are
  in [the tag write-protection report](reports/2026-11-07-tag-write-protection.md). In order:
  - [ ] After locking, read the lock and settings bytes back, and warn if the settings are still open.
  - [ ] Correct the README's claim that locking protects the tag. Recommend a metal box or shielding
    sleeve, and not carrying the tag around.
  - [ ] Proper lock (settings frozen too) or a write password, on a separate branch, tested with spare
    tags.
  - [ ] Rescue for Ultralight C tags already locked with `FF 0F`.

## App and docs

- [x] Make the on-screen warnings more prominent.
- [x] Warn that if the encryption password is lost, there's no way to restore the seed phrase.
- [x] Warn users to read the on-screen instructions carefully.
- [x] Explain that users need to have their NFC tag ready beforehand, and to hold it against the back of
  the phone when the app asks.
- [ ] Add haptic feedback.
- [ ] Explain the verification procedure in more detail on screen.
- [ ] Make Verify and Restore multi-step procedures like Back up:
  - remove the password field once it has done its job;
  - add "Verify another tag" and "Restore another tag" options;
  - etc.
- [ ] Add an About page.
- [ ] Add a separate option that helps users find their phone's NFC antenna beforehand.
- [ ] Add a disclaimer, in the app and the README:
  - The app comes without any guarantees, and the developer is not responsible for any damage.
  - Users must make sure for themselves that no malware is already installed on their phone. The app
    can't cover every possible attack vector.
