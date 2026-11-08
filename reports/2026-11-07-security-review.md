# SeedTag security review

- **Date:** 2026-11-07
- **Reviewed:** `main` at `f0b732d` (unreleased changes after 0.1.1)
- **Scope:** all source in `core/`, `app/` and `tools/`; the build, signing and devcontainer setup; the
  merged manifest of the built APK. Nothing was tested on a device.

**Verdict:** the cryptography is sound. The real risks are around it: weak passwords the app accepts and
rates as strong, a password typed on the system keyboard, malware that reads the screen, and the release
signing key living in a dev container that an AI agent can run commands in.

## Status since the review

| # | Finding | Status |
|---|---|---|
| 1 | Release signing key reachable from the dev container | **Fixed**: release APKs are built unsigned and signed outside the container with `tools/sign-release.sh`; the `android-keys` volume is no longer mounted. Takes effect once the container is rebuilt and `.env` no longer holds the key's password. |
| 2 | Weak passwords accepted and rated strong | **Fixed** in `a21d200` (honest estimate, confirmation below 60 bits or with seed words) |
| 3 | Password goes through the system keyboard | **Fixed** in `51afe6a` (in-app keyboard, no text fields, `FLAG_ALT_FOCUSABLE_IM`) |
| 4 | A revealed password can be copied to the clipboard | **Fixed** in `51afe6a` (no text fields left) |
| 5 | Accessibility services can read the screen | **Documented** in the README warnings (`776588e`); the technical fix is open |
| 6 | No decrypt check before writing | **Fixed** in `9d90fc0` |
| 7 | Build supply chain unverified | Open |
| 8 | Task hijacking and overlays | **Fixed**: task affinity in `b976e94`; overlays hidden (Android 12+) and obscured taps ignored (all versions) in `776588e`. Android 8–11 can't hide overlays; the README warns. |
| 9 | Autofill after a Compose update | **Resolved** by `51afe6a` (no text fields left) |
| 10 | Secret copies that aren't zeroed | **Fixed** for passwords (zeroable in-place buffer, no `String`s; branch `fix/zeroable-secrets`); the AES key copies are **documented** in the README and kept on purpose (platform AES is hardware-accelerated and constant-time) |
| 11 | Skimming, destruction and lockout of a "read-only" tag (raised from Low to Medium) | Open; options analysed in [the tag write-protection report](2026-11-07-tag-write-protection.md) |
| 12 | The passphrase looks like a seed | **Fixed**: the app and README tell users to label it "SeedTag password" |
| 13 | Whitespace and case must match exactly | **Fixed**: wrong passwords are retried with spaces collapsed, then lower-cased, in the app and `seedtag.py` |
| 14 | Fingerprint as a guess checker for a mostly known seed | **Documented** in the README (informational, no code change) |

## What's done well

- **Format:**
  - The Argon2 settings are fixed in code, not stored on the tag, so a crafted tag can't weaken them or
    make the phone run out of memory.
  - The blob length is checked before the slow key derivation runs.
  - The version byte is tamper-protected as part of the encryption (AAD).
  - Every write gets a fresh random salt and nonce.
- **Platform:**
  - `INTERNET` is removed, and `check-apk.sh` re-checks every build.
  - Reader mode keeps tags away from other apps while SeedTag is open.
  - `FLAG_SECURE` blocks screenshots, backups are off, and the in-app keypad keeps seed words away from the
    system keyboard.
  - Leaving the app wipes everything typed or decrypted.
- **Flows:**
  - Overwriting a tag that has data asks first.
  - Lock only touches the tag you just wrote or verified.
  - Stale scan results are dropped, and error messages give a word's position only, never the word.

## High

### 1. The release signing key can be reached by an AI agent with shell and network access — *fixed: signing moved outside the container*

- **The setup:** the key and `KEY_PASS` sit in the same container where Claude Code runs with `Bash(*)`
  allowed (`.claude/settings.json:4`).
- **The deny list isn't a barrier:** it blocks `cat .env`, but `grep . .env`, `sed`, `python` or a Gradle
  task all read it.
- **The volume is shared:** `android-keys` (`.devcontainer/devcontainer.json:41`) is mounted into every
  Android project's container, so any project's build scripts or dependencies can reach it too.
- **The impact:** whoever has the key can sign an "update" that installs over SeedTag and takes the seed
  the next time it's typed.
- **Fix:** sign releases outside the dev container (a separate offline machine is best). Otherwise, at
  least stop mounting the key by default and give each project its own volume.

### 2. The app accepts any password, and its strength meter can be badly fooled — *fixed in `a21d200`*

`core/src/main/kotlin/rs/zivke/seedtag/core/Passphrase.kt:20`

- **The flaw:** any 4 or more BIP39 words count as 11 random bits each, with no check for repeats and no
  comparison with the seed.
  - `abandon abandon abandon abandon abandon abandon abandon` is rated "Strong (≈77 bits)".
  - So is a password made of 7 words from the user's own seed.
  - Typed passwords only get a length × alphabet estimate: `Password1234567890!` comes out "Strong".
- **Why it matters:** the attack is offline guessing against the ciphertext. As a rough guide, a ~40-bit
  password is within reach of a well-funded attacker with many GPUs; 77 random bits isn't.
- **Fix:**
  - Count only distinct words.
  - Treat any word from the seed being backed up as worthless, with a warning.
  - Below about 60 bits, require an explicit "I understand" before **Encrypt & write tag**.

## Medium

### 3. The password goes through the system keyboard — *fixed in `51afe6a`*

- **The gap:** the README calls the password "the whole security", yet it was typed on the system
  keyboard, which the seed words deliberately avoid. `KeyboardType.Password` stops well-behaved keyboards
  from learning it. A malicious or cloud-syncing third-party keyboard still saw every key.
- **Fix:** the recommended passphrase is BIP39 words, which the existing keypad and suggestions already
  handle. Offer it for the password, at least on Verify and Restore.

### 4. A revealed password can be copied to the clipboard — *fixed in `51afe6a`*

`app/src/main/java/rs/zivke/seedtag/Ui.kt:431` (at the reviewed commit)

- **The gap:** Compose disables Copy only while the field is masked. The generated passphrase was shown
  unmasked by default, so it could be long-pressed and copied into clipboard history, which Gboard can
  sync to the cloud. This was inferred from Compose's text-field code, not tried on a phone.
- **Fix:** give `SecretField` a no-op text toolbar (`LocalTextToolbar`).

### 5. Accessibility services can read the restored words and your keypad taps — *documented in the README (`776588e`), fix open*

`FLAG_SECURE` doesn't stop them, and abusing accessibility is how most Android banking malware works.

- **Fix:** on Android 14+, set `View.setAccessibilityDataSensitive(YES)` on the root view, which blocks
  every accessibility service not flagged as an accessibility tool. This needs checking on a device, since
  Compose's support for it isn't certain. Also document the risk.

### 6. The app doesn't decrypt the blob before writing it — *fixed in `9d90fc0`*

`app/src/main/java/rs/zivke/seedtag/SeedTagViewModel.kt:150`

- **The gap:** "✓ Written and read back" only proves the bytes survived the tag. If the key derivation
  went wrong on a device (a library bug or a memory fault), the backup would be unrecoverable and still
  reported as fine. The Python tool checks this; the app relies on the user choosing to Verify.
- **Fix:** before writing, decrypt the blob with a second, independent key derivation. It costs one more
  Argon2 run, a few seconds.

### 7. Build supply chain is unverified

- **The gaps:**
  - No Gradle dependency verification (`gradle/verification-metadata.xml`).
  - No `distributionSha256Sum` in `gradle-wrapper.properties`.
  - The Android command-line tools zip and the Docker base image aren't checksum-verified or pinned, and
    Claude Code is installed with `curl | bash`.
- **What `check-apk.sh` can't catch:** a compromised library can leak data without any permission, for
  example by opening a browser with the data in a URL.
- **Fix:**
  - Add dependency verification and the wrapper checksum.
  - Pin the image digest and verify the tools zip.
  - Publish each release APK's SHA-256 next to the certificate digest.

### 11. Skimming, destruction, and lockout of a "read-only" tag — *raised from Low; open*

`app/src/main/java/rs/zivke/seedtag/TagIo.kt` (`lock()`)

Originally rated Low as plain skimming and erasing. A deeper look found that **Make tag read-only**
doesn't stop someone locking the owner out of the tag.

- **Skimming:**
  - **Range:** a phone reads at 1–4 cm. A home-built skimmer has read this type of tag at about
    25 cm, and a phone talking to the tag can be overheard from about 1 m.
  - **What leaks:** the ciphertext, the chip's fixed UID, and the fact that it's a SeedTag (one
    104-character Base64 string starting with `AQ`). The password is never transmitted. With a strong
    password this is low risk, but it can mark the owner as a target.
- **Erasing:** any phone with an NFC app can erase or overwrite an unlocked tag, or write junk to it and
  lock it.
- **Lockout of a locked tag:** `Ndef.makeReadOnly()` freezes the data pages but not the NXP-specific
  settings: the password or key, and the read-protection switch.
  - **NTAG21x:** CFGLCK is never set, so the settings stay writable. Anyone can set PWD, PROT=1 and
    AUTH0, and from then on no phone can read the tag.
  - **Ultralight C:** it depends on formatting.
    - Without a Lock Control TLV, Android writes `FF 0F` to page 0x28. That freezes the AUTH0, AUTH1
      and key lock bits *open* for good, so anyone can set a 3DES key with read protection.
    - With NXP's TLV, Android writes `FF FF`, and the tag is safe.
  - Nothing leaks, but the backup is lost. The attack takes seconds with a phone and no special
    hardware, and the app says the tag is protected when it's only half protected. That's an
    availability risk, so the rating is now **Medium**.
- **Physical destruction:** heat, cracking the chip or antenna, or an RF pulse (an "RFID zapper").
  Separate copies are the only answer.
- **Fix options** (pros and cons in [the tag write-protection report](2026-11-07-tag-write-protection.md)):
  - After locking, read the lock and settings bytes back and warn if the settings are still open.
    This is harmless and a good first step.
  - **Proper lock:** before `makeReadOnly()`, freeze the settings too.
    - Ultralight C: set lock byte 3 bits 4–7 while AUTH0 = 30h.
    - NTAG: set CFGLCK while AUTH0 = FFh.
  - **Write password instead of read-only:** a reusable tag with reads left open.
    - Ultralight C: a 3DES key derived from the backup password, with AUTH1 = 1 and AUTH0 = 03h.
    - Not recommended on NTAG, whose 32-bit password is sent in plain text.
  - **Rescue for Ultralight C tags already locked with `FF 0F`:** set a write key yourself, before an
    attacker does.
  - **Docs:** correct the README's claim that locking protects the tag, and recommend a metal box or
    shielding sleeve, not carrying the tag around.
  - Every one of these writes to the tag irreversibly, so it needs testing with spare tags on a device.

## Low

8. **Hijacking the app's task (StrandHogg)** — *fixed: `taskAffinity=""` in `b976e94`; overlays hidden (Android 12+) and obscured taps ignored (all versions) in `776588e`*. Devices running Android 8–9, which `minSdk` 26 allows, let
   another app slip a fake screen into SeedTag's task to phish the seed. The fix is a single attribute,
   `android:taskAffinity=""`. Blocking screen overlays (`HIDE_OVERLAY_WINDOWS`) would need that permission
   added to `check-apk.sh`'s allow list.
9. **Autofill** — *resolved by `51afe6a`*. Compose 1.7 doesn't offer text fields to autofill services, but
   1.8+ does. Setting `importantForAutofill = NO_EXCLUDE_DESCENDANTS` on the window would have prevented a
   password-manager "save password?" prompt after a future update.
10. **Some secret copies aren't zeroed** — *fixed for passwords; AES key copies documented and kept on purpose*.
    - `SecretKeySpec` copies the AES key (`SeedBox.kt:49`, `:73`), and that copy is never cleared.
    - The passphrase generator and password normalisation both create `String`s.
    - The README's "keys are zeroed" is therefore only partly true.
    - **Fix:** note it in the README, or use Bouncy Castle's lower-level cipher API, whose key array can be
      wiped.
11. **Skimming and destruction** — *raised to Medium; see #11 under Medium*.
12. **The passphrase looks like a seed** — *fixed: the app and README say to label it*. It uses the same word list, so a written-down passphrase can be
    mistaken for a seed. Tell users to label it "SeedTag password".
13. **Whitespace and case must match exactly** — *fixed: retried with spaces collapsed, then lower-cased*. An extra space or a capitalised first word gives "wrong
    password", which users may read as a lost backup. On a failed decrypt, the app could retry with
    collapsed spaces and lower case. That only tries a different password, so the v1 format doesn't
    change.
14. **Fingerprint (informational)** — *documented in the README*. It's an unsalted 32-bit hash of the seed. That's fine as a check
    value. If someone knew most of the words, it would help them test guesses, but public wallet addresses
    already do that better.

## Suggested order

1. ~~Move release signing out of the container (#1).~~ Done.
2. ~~Small fixes on `main`: #2, #6, #8.~~ Done.
3. #5 needs device testing (the risk is already documented in the README).
4. #11: first the read-back check and the README correction, then the proper lock or write key on a
   separate branch, tested with spare tags.
5. #7 is build work. It adds no new dependencies to the app, but it changes the devcontainer.
