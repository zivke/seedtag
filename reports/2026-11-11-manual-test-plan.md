# SeedTag manual test plan

- **Date:** 2026-11-11
- **Covers:** `main` at the time of writing (unreleased changes after 0.3.0), the released versions 0.1.0 to 0.3.0, the
  unmerged branch `tag-write-password`, and the checks outside the app (CI, signing, `seedtag.py`).
- **Why it's needed:** there's no emulator and no instrumented tests. `make test` covers the crypto core and
  `seedtag.py`, and a build checks permissions. Everything else, all NFC I/O, every screen and every
  Android-version-specific defence, can only be checked on a phone with real tags.

## How the status was assigned

| Mark | Meaning |
|---|---|
| ✅ | **Presumed tested.** It shipped in a released version (0.1.0 to 0.3.0), which was installed and used on the phone with a MIFARE Ultralight C. Nobody recorded which steps were actually exercised, so confirm the ones you aren't sure of. |
| ❓ | **Shipped, but probably not tested.** It's in a release, but it depends on hardware or an Android version that may not have been at hand (another chip, Android 8 to 11, 13 or 14+, a low-RAM phone). |
| ⬜ | **Not tested.** Under `[Unreleased]` in `changelog.md`, on `main` only. |
| 🔀 | **Not tested, on a branch.** `tag-write-password`, not merged. |
| 🔍 | **Suspected problem.** From the [expert review](2026-11-10-expert-review.md), needs a device to confirm. |
| ⚠ | The test changes a tag for good, or may destroy its content. Use a spare tag. |

Record a result next to each row (pass, fail, or not possible, with the phone and Android version).

## Setup

- **APK:** `make build-test` makes `seedtag-release-test.apk`, the R8 release build signed with the debug key. It
  can't update a release install, since the certificate differs: run `make uninstall` first (nothing is lost; the
  app stores nothing), then `adb install seedtag-release-test.apk`. Put the signed release back the same way after
  testing.
- **Seeds:** only throwaway Trezor test vectors, never a real seed. The phone has adb authorised while testing, so it
  must not be used for a real seed until debugging is off and the authorisations are revoked.
  - 12 words: `abandon` ×11, then `about`
  - 24 words: `abandon` ×23, then `art`
  - 12 words, different seed: `legal winner thank year wave sausage worth useful legal winner thank yellow`
- **Tags:** at least two spare Ultralight C tags (A and B), one of them blank. Ideally also an NTAG213/215/216, a
  Type 5 tag (ICODE SLIX or ST25TV), a tag that's too small (plain Ultralight, 48 bytes) and any contactless card.
- **Phones:** the main test phone, plus wherever possible an Android 10 or 11 phone, an Android 8 or 9 phone, an
  Android 14+ phone and an old low-RAM phone. Write down each phone's model and Android version with the results.
- **Overlay app** for the overlay tests: any app that draws over other apps (a screen dimmer, a floating bubble or
  a chat head).

## 1. Untested changes on `main` (do these first)

Everything under `[Unreleased]` in `changelog.md`.

| # | Test | Steps | Expected | Status |
|---|---|---|---|---|
| 1.1 | **Bouncy Castle 1.86** in the R8 build | Back up the 12-word and the 24-word vector to tag A, Verify, Restore. | All succeed. The fingerprints after Back up and Verify match. Restore shows the same words. | ⬜ |
| 1.2 | Backward compatibility after 1.86 | Verify and Restore a tag written by 0.3.0 (before the update). | It decrypts, same fingerprint as before. | ⬜ |
| 1.3 | Forward compatibility after 1.86 | Read a tag written by this build with NFC Tools and run `seedtag.py verify <Base64>`. | `OK` and the same fingerprint. | ⬜ |
| 1.4 | Stray spaces refused | In Back up, type a new password with a leading space, then one with a trailing space, then one with two spaces in a row. | Each is flagged under the field and **Encrypt & write** refuses it. Removing the space clears the warning. | ⬜ |
| 1.5 | Strength: typed BIP39 words | Type `abandon ability able about above absent` as the password. | Rated weak; writing asks for confirmation. | ⬜ |
| 1.6 | Strength: generated passphrase | Tap **Generate a 7-word passphrase**. | About 77 bits, Strong, no confirmation. | ⬜ |
| 1.7 | Lock wording | Verify a tag, look at the **Make tag read-only** text and its confirmation dialog. Optional, ⚠: lock a spare tag and read the result. | The text says the lock stops overwriting and erasing, but not (on most chips) a stranger locking the tag against reading. Advice about a metal box or shielding sleeve and other copies. | ⬜ |
| 1.8 | Keypad prefix shown as dots | Back up, words hidden (the default): type a few letters. Tap **Show words**, type again. | Hidden: the letters being typed are dots; the suggestions still show matching words. Shown: the letters are visible. Backspace works in both. | ⬜ |
| 1.9 | Keypad prefix wiped | Type a few letters of a word, press Home, return. | The partial word is gone, along with everything else. | ⬜ |
| 1.10 | Overwrite bound to the tag | Tag A and B both hold data. Back up to A, confirm **Overwrite**, then hold **B** when asked to hold the same tag again. Then hold A. | B is not written: the app asks about B as a new tag. A is overwritten only after its own confirmation. | ⬜ |
| 1.11 | Overwrite after the content changed | Confirm overwrite for tag A, cancel, change A's content (another backup, or NFC Tools), then try again. | It asks again, since the content changed. | ⬜ |
| 1.12 ⚠ | Interrupted overwrite | Overwrite a spare tag that holds data and pull it away during the write (it takes a few tries to time). | The message says the old content may already be gone, not just "moved away too early". Retrying writes and reads back the new backup. | ⬜ |
| 1.13 | Type 5 tag with data (needs a Type 5 tag) | Back up to an ICODE or ST25TV that holds data, with a very quick tap so Android misses the data. | Asks to hold the tag again; never formats it. A normal tap shows the overwrite prompt. | ⬜ |
| 1.14 | Type 5 tag, blank | Back up to a blank Type 5 tag. | Formats, asks for a second tap, reads back, *Backup written*. | ⬜ |
| 1.15 | Android 10/11: partial overlay (needs Android 10 or 11) | Start an overlay app whose window covers part of the screen but not the buttons. Tap a button in SeedTag, including in the Overwrite and Lock dialogs. | Taps are ignored and the red banner shows. Without the overlay, everything works normally. | ⬜ |
| 1.16 | Android 10/11: no false positives (needs Android 10 or 11) | With no overlay app running, use every screen and dialog, including the system volume panel, a notification and a split screen. | No tap is blocked, unless something really covers the app. A tap blocked for no visible reason is a failure. | ⬜ |
| 1.17 | Android 12+: no change | Use the app normally on Android 12 or later. | Taps work as before; the new filter is off on these versions. | ⬜ |
| 1.18 | Out of memory (needs a low-RAM phone) | Back up or Verify on an old phone. | Either it works, or an out-of-memory message appears and the app stays open. It never crashes. | ⬜ |

The NFC-stack catch-all (`TagIo` never throwing) can't be provoked on purpose. Note any odd error that appears
during the other tests.

## 2. Released features (presumed tested)

### 2.1 Back up

| # | Test | Expected | Status |
|---|---|---|---|
| 2.1.1 | Check your tag, blank Ultralight C | Says blank, the room it has, nothing written. | ✅ |
| 2.1.2 | Check your tag with data: a SeedTag backup, and other data | Identifies a SeedTag backup vs. other data. | ✅ |
| 2.1.3 | Check your tag, read-only tag | Says read-only; Back up isn't possible. | ✅ |
| 2.1.4 | Check your tag, tag too small (plain Ultralight) | Word counts it can't hold are grayed out; "too small" gives the bytes needed and available. | ❓ |
| 2.1.5 | **Use another tag** | Goes back to the check. | ✅ |
| 2.1.6 | Word count 12, 15, 18, 21, 24 | The keypad stops at the count; **Next** only when the checksum is valid. | ✅ (12 and 24 at least) |
| 2.1.7 | Wrong checksum or unknown word | **Next** stays disabled; a clear message. | ✅ |
| 2.1.8 | Hide entered words on by default, **Show words** | Dots by default; shown on request; hidden again after leaving the app. | ✅ |
| 2.1.9 | Keypad haptics | Light tap on every key, following the touch-feedback setting. | ✅ |
| 2.1.10 | Password keyboard: letters, digits, symbols, accented letters, BIP39 suggestions | The system keyboard never opens. | ✅ |
| 2.1.11 | Weak password, or one using seed words | Asks for confirmation before writing. | ✅ |
| 2.1.12 | Repeat password mismatch | Refused. | ✅ |
| 2.1.13 | "Label it SeedTag password" reminder and the lost-password warning | Shown with the generated passphrase and the password step. | ✅ |
| 2.1.14 | Write to a blank, unformatted Ultralight C | Formats, asks for a second tap, reads back, *Backup written*. | ✅ |
| 2.1.15 | Write to a blank NTAG21x, Ultralight EV1 or AES, NTAG203 | The size probe is right, formats, writes. | ❓ |
| 2.1.16 | Write to a tag with data | Asks before overwriting; the risky choice is in red. | ✅ |
| 2.1.17 | Tag moved away during write, or failed read-back | Asks for another tap to check, doesn't call the tag faulty. | ✅ |
| 2.1.18 | Write success haptic; failure "rejected" buzz | Felt. | ✅ |
| 2.1.19 | Cancel a scan, or leave while a tag is being read | The prompt or error doesn't come back afterwards. | ✅ |

### 2.2 Verify, Restore and Lock

| # | Test | Expected | Status |
|---|---|---|---|
| 2.2.1 | Verify, right password | ✓, fingerprint, "same seed as the backup just written". | ✅ |
| 2.2.2 | Verify a different seed's tag right after a backup | Warns that it isn't the same seed. | ✅ |
| 2.2.3 | Wrong password | Wrong-password message, no detail. | ✅ |
| 2.2.4 | Forgiving retry: extra spaces, then capitals | Opens and says which correction matched. | ✅ |
| 2.2.5 | Two-step flow; **Verify another tag**, **Restore another tag** | The password field is gone after decrypting; "another" starts with an empty password; each tab shows only its own result. | ✅ |
| 2.2.6 | Restore shows the words | Same words as backed up. | ✅ |
| 2.2.7 | Tag that can't be read | "Couldn't be read", not "empty". | ✅ |
| 2.2.8 ⚠ | Lock after Verify | Only offered after a successful Verify; red button; confirmation; locks only that tag. | ❓ (irreversible: was a spare tag locked?) |
| 2.2.9 | Lock with a different tag held | Refused, the tag isn't changed. | ❓ |
| 2.2.10 | Lock on NTAG21x, Type 4, Type 5 | 🔍 Expert review #9: probably unavailable on Type 4 and 5. | ❓ |

### 2.3 Privacy and protection

| # | Test | Expected | Status |
|---|---|---|---|
| 2.3.1 | Screenshot or screen recording, and the Recents thumbnail | Blocked or blank, in dialogs too. | ✅ |
| 2.3.2 | Leave the app: Home, Recents, screen off | Everything typed or decrypted is gone on return. | ✅ |
| 2.3.3 | Leave while Argon2 runs | The result doesn't appear on return. | ✅ |
| 2.3.4 | Full screen; bars return briefly on a swipe | As described. | ✅ |
| 2.3.5 | Airplane-mode line on the *Before you start* card | Reflects airplane mode on and off. | ✅ |
| 2.3.6 | Accessibility banner | An app with accessibility access is named, with a shortcut to Settings. | ❓ (needs such an app installed) |
| 2.3.7 | Android 14+: the screen hidden from accessibility services that aren't accessibility tools | Such a service can't read the screen; TalkBack still works. | ❓ (needs Android 14+ and such a service) |
| 2.3.8 | Android 12+: other apps' overlays hidden | An overlay disappears while SeedTag is on screen. | ❓ |
| 2.3.9 | Android 8 to 11: a tap through an overlay | Ignored, red banner. | ❓ |
| 2.3.10 | Android 8 and 9: task hijacking | Another app can't put its screen into SeedTag's task. | ❓ (hard to test without a test app) |
| 2.3.11 | NFC reader mode | While SeedTag is in front, no other app or system popup reacts to a tag. | ✅ |
| 2.3.12 | NFC off | The app says NFC is off. | ✅ |

### 2.4 Other screens

| # | Test | Expected | Status |
|---|---|---|---|
| 2.4.1 | **Find NFC spot** with a tag and with a contactless card | Two firm pulses and *Tag detected*; NDEF size for a tag; nothing written, contents not read. | ✅ |
| 2.4.2 | About page | Version, privacy, licence; links open the browser only when tapped. | ✅ |
| 2.4.3 | Disclaimer before entering the seed | Shown. | ✅ |
| 2.4.4 | Look: dark and light theme, step progress, warning cards, red risky buttons | As in the 0.3.0 changelog. | ✅ |
| 2.4.5 | Themed icon (Android 13+) | The single-colour icon is used. | ❓ |

## 3. Suspected problems (expert review, need a device)

| # | Test | What to look for | Status |
|---|---|---|---|
| 3.1 | Old low-RAM phone (finding 6) | Back up, Verify and Restore all run out of memory. Same as 1.18. | 🔍 |
| 3.2 | Screen timeout mid-entry (finding 7) | With a 30 s screen timeout, the screen goes off while you copy the passphrase or the restored words, and everything is wiped. | 🔍 |
| 3.3 | Back gesture (finding 7) | A back swipe from the edge while typing on the keypad leaves the app and wipes the entry. | 🔍 |
| 3.4 | Generate tapped twice (finding 2) | A second tap on Generate replaces the passphrase without asking. | 🔍 |
| 3.5 | "Already read-only" tag (finding 9) | Lock on a tag that was already not writable says "now permanently read-only". | 🔍 |
| 3.6 | Late results (finding 12) | Double-tap **Encrypt & write**; Cancel right as a write finishes; look for a scan that's still armed with no prompt on screen. | 🔍 |
| 3.7 | Unreadable tag treated as empty (finding 14) | Hard to provoke: a tag with data at a marginal position, written without the overwrite prompt. | 🔍 |
| 3.8 | NFC toggled from quick settings (finding 14) | Turn NFC off and on from the shade while SeedTag is open; tags must still reach the app. | 🔍 |

## 4. Branch `tag-write-password` (before merging)

The branch also needs the expert review's advice in mind: extract the tag-flow state machine before merging, and
check the lock read-back after it.

| # | Test | Expected | Status |
|---|---|---|---|
| 4.1 ⚠ | Protect an Ultralight C (two taps) | The first tap stores the key, with protection still off. The second tap, in a new session, authenticates, and only then turns protection on. The result is *Protected*. | 🔀 |
| 4.2 ⚠ | Protect an NTAG213, 215 and 216 | Same, with PWD and PACK. | 🔀 |
| 4.3 | Reads stay open | Verify and Restore a protected tag without the write password. NFC Tools still reads it. | 🔀 |
| 4.4 | Back up and Lock refuse a protected tag | "This tag is write-protected…", nothing changed. | 🔀 |
| 4.4b | Protect an already protected tag | "Already write-protected", nothing changed. | 🔀 |
| 4.5 | NFC Tools can't write to a protected tag | Write fails. | 🔀 |
| 4.6 | Remove protection | After Verify with the right password, the tag is writable again. | 🔀 |
| 4.7 ⚠ | Protect interrupted between the two taps | Protection is off, the tag stays writable, and retrying works. Never a tag that's protected with an unknown key. | 🔀 |
| 4.8 ⚠ | Tag pulled away during the second tap, or during Remove protection | The app reports success only after reading the protection state back. Otherwise it says it couldn't confirm, and holding the tag again finishes or retries. Check the tag's real state with Verify and a write attempt from NFC Tools. | 🔀 |
| 4.9 | `seedtag.py tag-key` | The password it prints matches the app's: it can remove the protection with another tool. | 🔀 |
| 4.10 | Wrong backup password | Protect and Remove protection aren't offered, since Verify fails. | 🔀 |
| 4.11 | Chips the feature doesn't support (Type 4, Type 5, plain Ultralight) | Refused: "SeedTag can only write-protect MIFARE Ultralight C and NTAG213/215/216 tags", nothing changed. | 🔀 |
| 4.11b | A tag whose protection settings are locked (an Ultralight C locked with `FF 0F`) | Refused: "protection settings are locked", nothing changed. | 🔀 |
| 4.12 | Section 1 again, on the branch after merging `main` | No regressions in Back up, Verify, Restore and Lock. | 🔀 |

## 5. Outside the app

| # | Test | Expected | Status |
|---|---|---|---|
| 5.1 | Recover with `seedtag.py`: read a tag with NFC Tools, run `verify` and `decrypt` on an offline computer | Same fingerprint and words as the app. | ❓ (README step 3; was it done?) |
| 5.2 | `seedtag.py` with `cryptography` ≥ 44 only, and with `argon2-cffi` only | Both decrypt and say which Argon2id they used. 🔍 Expert review #5: the `cryptography` backend is never tested automatically. | ⬜ |
| 5.3 | `seedtag.py` with neither library | A clear message. 🔍 Expert review #4: AES-GCM still gives a traceback without `cryptography`. | 🔍 |
| 5.4 | First CI run (Actions tab, "Run workflow" on `main`) | Green; the summary shows the SHA-256, the same as a local `make build` of the same commit. | ⬜ |
| 5.5 | CI on a `v*` tag | Starts by itself. | ⬜ |
| 5.6 | `sign-release.sh --expect <wrong hash>` on the signing machine | Refuses, nothing signed. | ⬜ |
| 5.7 | `sign-release.sh --expect <CI hash>` | Signs; certificate check passes; writes `seedtag.apk.sha256`. | ⬜ |
| 5.8 | Install the newly signed release over the previous release | Updates in place (same certificate), no uninstall needed. | ✅ (0.1.0 to 0.3.0 were updated this way, presumably) |

## Short regression check for every release

Run this for every release candidate, on the signed APK, with a spare tag:

1. Find NFC spot detects the tag.
2. Back up the 24-word vector with a generated passphrase: the check, the words, the password, the write, *Backup
   written*.
3. Verify with the written passphrase: ✓, "same seed".
4. Restore: the same 24 words.
5. Overwrite the same tag with the 12-word vector: confirmation, then *Backup written*.
6. Read the tag with NFC Tools and `seedtag.py verify`: same fingerprint.
7. Home, then back: everything wiped.
8. Screenshot attempt: blocked.
