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
| ✅ | **Presumed tested.** It shipped in a released version (0.1.0 to 0.3.0), which was installed and used on the phone with an NXP NTAG213 (long taken for a MIFARE Ultralight C: a memory dump on 2026-11-21 showed otherwise). Nobody recorded which steps were actually exercised, so confirm the ones you aren't sure of. |
| ❓ | **Shipped, but probably not tested.** It's in a release, but it depends on hardware or an Android version that may not have been at hand (another chip, Android 13 or 14+, a low-RAM phone). |
| ⬜ | **Not tested.** Under `[Unreleased]` in `changelog.md`, on `main` only. |
| 🔀 | **Not tested, on a branch.** `tag-write-password`, not merged. |
| 🔍 | **Suspected problem.** From the [expert review](2026-11-10-expert-review.md), needs a device to confirm. |
| ⚠ | The test changes a tag for good, or may destroy its content. Use a spare tag. |
| ✔️ | **Passed** on the device, with the date it was tested. |

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
- **Tags:** at least two spare NTAG213 tags (A and B), one of them blank. Ideally also an Ultralight C, an NTAG215/216, a
  Type 5 tag (ICODE SLIX or ST25TV), a tag that's too small (plain Ultralight, 48 bytes) and any contactless card.
- **Phones:** the main test phone, plus wherever possible an Android 14+ phone and an old low-RAM phone, and an older
  phone for [section 6](#6-android-8-to-11). Write down each phone's model and Android version with the results.
- **Overlay app** for the overlay tests: any app that draws over other apps (a screen dimmer, a floating bubble or
  a chat head).

## 1. Untested changes on `main` (do these first)

Everything under `[Unreleased]` in `changelog.md`.

| # | Test | Steps | Expected | Status |
|---|---|---|---|---|
| 1.1 | **Bouncy Castle 1.86** in the R8 build | Back up the 12-word and the 24-word vector to tag A, Verify, Restore. | All succeed. The fingerprints after Back up and Verify match. Restore shows the same words. | ✔️ Pass, 2026-11-11 |
| 1.2 | Backward compatibility after 1.86 | Verify and Restore a tag written by 0.3.0 (before the update). | It decrypts, same fingerprint as before. | ✔️ Pass, 2026-11-11 |
| 1.3 | Forward compatibility after 1.86 | Read a tag written by this build with NFC Tools and run `seedtag.py verify <Base64>`. | `OK` and the same fingerprint. | ⬜ |
| 1.4 | Stray spaces refused | In Back up, type a new password with a leading space, then one with a trailing space, then one with two spaces in a row. | Each is flagged under the field and **Encrypt & write** refuses it. Removing the space clears the warning. | ✔️ Pass, 2026-11-11 |
| 1.5 | Strength: typed BIP39 words | Type `abandon ability able about above absent` as the password. | Rated weak; writing asks for confirmation. | ✔️ Pass, 2026-11-11 |
| 1.6 | Strength: generated passphrase | Tap **Generate a 7-word passphrase**. | About 77 bits, Strong, no confirmation. | ✔️ Pass, 2026-11-11 |
| 1.7 | Lock wording | Verify a tag, look at the **Make tag read-only** text and its confirmation dialog. Optional, ⚠: lock a spare tag and read the result. | The text says the lock stops overwriting and erasing, but not (on most chips) a stranger locking the tag against reading. Advice about a metal box or shielding sleeve and other copies. | ⬜ |
| 1.8 | Keypad prefix shown as dots | Back up, words hidden (the default): type a few letters. Tap **Show words**, type again. | Hidden: the letters being typed are dots; the suggestions still show matching words. Shown: the letters are visible. Backspace works in both. | ✔️ Pass, 2026-11-11 |
| 1.9 | Keypad prefix wiped | Type a few letters of a word, press Home, return. | The partial word is gone, along with everything else. | ✔️ Pass, 2026-11-11 |
| 1.10 | Overwrite bound to the tag | Tag A and B both hold data. Back up to A, confirm **Overwrite**, then hold **B** when asked to hold the same tag again. Then hold A. | B is not written: the app asks about B as a new tag. A is overwritten only after its own confirmation. | ✅ |
| 1.11 | Overwrite after the content changed | Confirm overwrite for tag A, cancel, change A's content (another backup, or NFC Tools), then try again. | It asks again, since the content changed. | ✔️ Pass, 2026-11-11 |
| 1.12 ⚠ | Interrupted overwrite | Overwrite a spare tag that holds data and pull it away during the write (it takes a few tries to time). | The message says the old content may already be gone, not just "moved away too early". Retrying writes and reads back the new backup. | ✔️ Pass, 2026-11-11 |
| 1.13 | Type 5 tag with data (needs a Type 5 tag) | Back up to an ICODE or ST25TV that holds data, with a very quick tap so Android misses the data. | Asks to hold the tag again; never formats it. A normal tap shows the overwrite prompt. | ⬜ |
| 1.14 | Type 5 tag, blank | Back up to a blank Type 5 tag. | Formats, asks for a second tap, reads back, *Backup written*. | ⬜ |
| 1.17 | Android 12+: no change | Use the app normally on Android 12 or later. | Taps work as before; the new filter is off on these versions. | ✅ |
| 1.22 | Tag from a newer format | Make the text of a "format 2" tag from a test backup's Base64: `python3 -c "import base64,sys; b=bytearray(base64.b64decode(sys.argv[1])); b[0]=2; print(base64.b64encode(b).decode())" <Base64>`. Write it to a spare tag with NFC Tools as a Text record. Check it in Back up, then Verify it. | Back up's check says "A backup from a newer version of SeedTag"; overwriting asks with "destroys that backup". Verify says the tag was written by a newer version and to update the app. | ⬜ |
| 1.23 | Generated passphrase typed back | Generate a passphrase, write it down, tap **I've written it down**, type it into *Repeat password* from the paper, once with a deliberate typo. | *Repeat* starts empty. After the tap the passphrase is hidden (card and password field); **Show it again** brings it back. The typo shows "don't match" and blocks writing; the correct copy writes without a strength confirmation. | ✔️ Pass, 2026-11-11 |
| 1.24 | Generate a different one | After generating, tap **Generate a different one**, then **Keep it**, then again and **Replace**. Also: type your own password, then tap Generate. | The button is relabelled after the first passphrase. Each replacement asks first; **Keep it** changes nothing; **Replace** shows the new passphrase and empties *Repeat*. | ✔️ Pass, 2026-11-11 |
| 1.25 | Debugging warning | With USB debugging on (it is while testing over adb), open Back up, Verify and Restore. Tap **Open Developer options**. Turn debugging off and come back. | A red *USB or wireless debugging is on* item in *Before you start*; the link opens Developer options; the item is gone after turning debugging (and wireless debugging) off. | ✔️ Pass, 2026-11-11 |
| 1.27 | Screen stays on, idle timeout | Set the screen timeout to 30 s. Enter a few words and wait without touching. Then let it run: wait 3 minutes, tap **Keep**; wait again and let the minute pass. Also try **Clear now**, the power button, and the Restore result left on screen. On the Done step and on empty screens, wait 30 s. | With secrets on screen the screen stays on. After 3 minutes, a buzz and *Still there?*, with the screen behind it blurred so no word or password can be read; **Keep** brings it back sharp and carries on with everything kept; a minute without an answer (or **Clear now**) clears the entry, with *Cleared* explaining why. The power button still clears everything. With nothing entered (Done, empty steps) the 30 s timeout applies again. A scan prompt left open for 3 minutes is canceled by *Still there?*. | ✔️ Pass, 2026-11-11 |
| 1.28 | Back | Words step: back gesture (also from the left and right edges, over the keypad's edge keys). Password step: Back with both fields empty, then with a password (gesture and on-screen **Back**: **Stay**, then **Clear and go back**). Length step with words entered, Verify / Restore with a password typed, and a Restore result: Back, **Stay**, then **Leave**. With nothing entered: Back. | Words → the length step, the words kept. Password with nothing typed goes straight back; with a password it asks *Clear the password?*. Elsewhere with input it asks *Leave SeedTag?*; **Leave** closes the app (and clears everything). With nothing entered, Back leaves as before. A top-bar page still closes with Back. | ✔️ Pass, 2026-11-11 |
| 1.29 | Verify result after a new backup | Verify tag A (result shown), then type a password on Restore without scanning. Back up a different seed to tag B. Open Verify and Restore. Then verify B, start another backup and cancel it at the scan, and look at Verify again. | After the backup, Verify and Restore are empty (no old result, no typed password). Verifying B says *Same seed*. A backup canceled before writing doesn't change *Backup from this session*. | ✅ |
| 1.30 | Scroll hint | On the Password step (keyboard open) and on the Length step, on a small phone or with a large font size; also a screen that fits. Tap the ↓ button, scroll by hand to the end and back. | Where the content goes on below the visible part: a fade at the bottom with a round ↓; tapping it scrolls most of a screen down. At the end both go away; scrolled down, the top edge fades. A screen that fits shows neither. Nothing is hidden under the ↓ that can't be reached by scrolling. | ✅ |
| 1.31 | MIFARE Classic warning | Back up, Check your tag, with a MIFARE Classic 1K (blank, then formatted by a practice backup); then with an NTAG213. | Classic: amber icon, *Better use another tag* with the reason, a failure buzz; **Check another tag** is the main button, **Use this tag anyway** goes on to the Length step. Room is "Unknown" while blank. NTAG213: *This tag works*, as before. | ✅ |
| 1.32 | No self-starting libraries | Install the `dependency-trim` build on the oldest phone at hand (Android 8 if possible) and a current one. Open every screen: Back up through the Done step with a throwaway seed, Verify, Restore, the dialogs, the NFC-spot page. Close and reopen the app a few times. | It starts and every screen draws as before: text, ✓, ↗, icons, dialogs. No crash at start (no missing class). Cold start isn't noticeably slower. | ⬜ |
| 1.33 | Content capture and autofill | Android 11+: open Restore with a result shown, and a dialog, then `adb shell dumpsys content_capture` and `adb shell dumpsys autofill`. Compare with the 0.4.2 build if possible. | No content-capture session and no autofill session for `rs.zivke.seedtag`. | ⬜ |
| 1.34 | New toolchain: full pass | Install the `toolchain-update` build (`make uninstall`, `make run-test`) on the oldest phone at hand and a current one. Back up a throwaway seed (all four steps), Verify, Restore, Lock on a spare tag; the dialogs, the idle warning, both keyboards (held ⌫ too), the NFC-spot page, Back everywhere. Open a tag written by 0.4.2. | Everything works and looks as before, except the icons (Material Symbols now: back arrow, ↓ scroll button, ✓, info, warning), which are slightly rounder and lighter but drawn in the same colors and sizes. A 0.4.2 tag verifies and restores; a tag written by this build verifies with `python3 -I -S tools/seedtag.py verify`. Argon2 takes about as long as before. | ⬜ |
| 1.35 | Android 16 behavior (targetSdk 36) | On Android 16: the back gesture with predictive back (hold the swipe: the preview, then release; also cancel it) on every screen of 1.28. On a tablet or unfolded foldable, if there is one: rotate to landscape on each screen, with the keypad and keyboard open. | Back does exactly what 1.28 expects; a cancelled gesture does nothing. In landscape everything can be reached (scrolling where needed), nothing overlaps; rotating doesn't lose typed words or the password. | ⬜ |
| 1.36 | Erase: what's on the tag | The Erase tab. Scan, in turn: a tag with a throwaway backup, one with other data (a URL from NFC Tools), an empty one, a blank (never formatted) one (a read-only one: 1.36b). Also check the tab row on the narrowest phone at hand, and with a large font size. | Nothing is written by the scan. Backup: *A SeedTag backup*, with **Check the password first**, **Erase without checking**, **Scan another tag**. Other data / Empty: **Erase this tag**. Blank: *Nothing to erase*. All four tab labels (Back up, Verify, Restore, Erase) fit, none cut off. | ✅ |
| 1.36b | Erase: a read-only tag | Needs a tag that's already read-only: lock a spare one with SeedTag (2.2.8) or NFC Tools (*Other* → *Lock tag*), both irreversible. On the Erase tab, scan it; then read it with NFC Tools. | The scan writes nothing: *Can't be erased*, with the advice to destroy the tag, *State: Read-only*, no erase button. NFC Tools still reads what it held. | ⬜ |
| 1.37 | Erase with the password check | Backup tag: **Check the password first**, a wrong password, then the right one (also with an extra space); **Erase**, then hold the tag. Then read it with NFC Tools, and with TagInfo's memory view (or NFC Tools' *Read memory*) on an NTAG/Ultralight. | Wrong password: an error, nothing changes. Right one: *Password checked*, the fingerprint, *Same seed* if it was written in this session, the password note for the extra space. The dialog names the fingerprint. After the tap: *Tag erased*. NFC Tools: empty. Memory view: the data pages after the NDEF header are all 00, none of the old Base64. | ✅ |
| 1.38 | Erase: wrong tag, interrupted | Scan tag A, **Erase without checking**, confirm, hold tag B. Then hold A but pull it away very quickly (during the write), and hold it again. Switch to another tab while a scan is waiting, and with a password typed. | Tag B: *This isn't the tag you scanned…*, nothing written, still waiting. Pulled away: a message that part may be gone; holding A again finishes the erase (*Tag erased*). Switching tabs cancels the scan and clears the result; with a password typed, it first asks *Clear the password?* (**Stay** keeps it). | ✅ |
| 1.39 | Switching tabs with secrets | Restore a throwaway backup (words shown), tap Verify; then **Stay**, tap it again, **Clear and switch**, go back to Restore. On Verify type a password, tap Restore (both answers). Also tap a tab with nothing typed, and on Restore after **Hide words**. | Words shown: *Hide the restored words?*, blurred behind. **Stay** changes nothing; **Clear and switch** opens Verify, and Restore then says the words are hidden (fingerprint kept). A typed password: *Clear the password?*; after switching, both password fields are empty. Nothing typed or shown: the tab switches without asking. | ✅ |
| 1.40 | Clear on the Words step | Enter 3 words, tap **Clear**, then **Keep them**; tap **Clear** again, then **Clear words**. Enter 1 word and tap **Clear**. With no words entered (letters typed only), look at **Clear**. Press Home while the question is open, and come back. | *Clear all 3 words?*, blurred behind. **Keep them** leaves all 3; **Clear words** empties the grid and the letters typed. One word: *Clear the word?* / **Keep it**. No words: **Clear** is greyed out. After Home: the question is gone, along with the words (everything is wiped on leaving). | ✅ |
| 1.41 | *Tag found* and steps | With a throwaway seed: Back up (Check your tag; Encrypt & write onto an empty tag, then over a backup; a blank tag too), Verify, Restore, Lock (spare tag ⚠), Erase. Hold the tag only when asked, slowly sliding it towards the NFC spot. Try system Back while the steps show. | The moment the phone finds the tag: one short buzz, *Tag found*, a spinner and what's being done. Reads: no step count, Cancel stays. Write: *Step 1 of 3* to *3 of 3* (blank tag: *of 2*, then the second tap); Lock: *of 2*; Erase: *of 5*. While those show: no Cancel, Back does nothing, the red *Don't move the tag* line. At the end: the success buzz and the result, with no extra tick after a read. Note whether *Tag found* can be seen at all on a quick read, and whether the phone's own NFC sound or buzz doubles the app's. | ✅ |
| 1.42 | *Tag found* when it fails | Erase a backup, pulling the tag away at step 2 or 3; Back up over a backup the same way. Then hold it again. | Pulled away: the window goes back to *Ready to scan* with the error and Cancel, and the failure buzz; holding the tag again shows *Tag found* again with the error gone. | ✅ |
| 1.43 | Spare-phone advice | Open Back up, Verify and Restore; tap *Learn more* under *Safest on a spare phone*, and under *Go offline first* (airplane mode off). Open About. Also with a large font. | It's the second item in each checklist, right after *Read every screen carefully* (on Restore, before *Only do this in private*), with a red title. *Learn more* shows three paragraphs: why, how to prepare a spare phone, and a pointer to *Go offline first* for the everyday phone (no offline steps repeated). *Go offline first* has the offline steps, what they can't stop, and no spare-phone advice. About shows the same text as its first section. | ✅ |
| 1.26 | Accessibility banner while open | With SeedTag in front, enable an accessibility app over adb (`adb shell settings put secure enabled_accessibility_services <pkg>/<service>`), then disable it. | The banner appears and disappears without leaving the app. | ⬜ |
| 1.18 | Out of memory (needs a low-RAM phone) | Back up or Verify on an old phone. | Either it works, or an out-of-memory message appears and the app stays open. It never crashes. | ⬜ |
| 1.19 | Held ⌫ on the password keyboard | Type a long password, hold ⌫. Then tap ⌫ once. | After a short pause it keeps deleting, with a tick each time, and stops at an empty field. A tap deletes one character; releasing after a hold deletes nothing extra. | ✔️ Pass, 2026-11-11 |
| 1.20 | Held ⌫ on the word keypad | Enter a few words and type part of the next one, then hold ⌫. Hold it again with no letters typed. Then tap it. | The hold deletes the letters and stops at the start of the word; entered words stay. The second hold removes one word, when released. Each tap removes one letter, or one word when no letters are typed. | ✔️ Pass, 2026-11-11 |
| 1.21 | Typed password that scores high | Type `q7#Lm!x2Vr9@kPz4&Wt8`, then `correct horse battery staple`, and try to write each. | The first shows "≈78 bits at most" in amber, never *Strong*; writing asks "Use your own password?". The second is *Weak* (≈43 bits) and asks "Weak password". Only the generated passphrase shows *Strong* and writes without asking. | ✔️ Pass, 2026-11-11 |

The NFC-stack catch-all (`TagIo` never throwing) can't be provoked on purpose. Note any odd error that appears
during the other tests.

## 2. Released features (presumed tested)

### 2.1 Back up

| # | Test | Expected | Status |
|---|---|---|---|
| 2.1.1 | Check your tag, blank NTAG213 | Says blank, the room it has, nothing written. | ✅ |
| 2.1.2 | Check your tag with data: a SeedTag backup, and other data | Identifies a SeedTag backup vs. other data. | ✅ |
| 2.1.3 | Check your tag, read-only tag | Says read-only; Back up isn't possible. | ⬜ |
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
| 2.1.14 | Write to a blank, unformatted NTAG213 | Formats, asks for a second tap, reads back, *Backup written*. | ✅ |
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
| 2.3.3 | Leave while Argon2 runs | The result doesn't appear on return. | ⬜ |
| 2.3.4 | Full screen; bars return briefly on a swipe | As described. | ✅ |
| 2.3.5 | Airplane-mode line on the *Before you start* card | Reflects airplane mode on and off. | ✅ |
| 2.3.6 | Accessibility banner | An app with accessibility access is named, with a shortcut to Settings. | ✔️ Pass, 2026-11-11 |
| 2.3.7 | Android 14+: the screen hidden from accessibility services that aren't accessibility tools | Such a service can't read the screen; TalkBack still works. | ❓ (needs Android 14+ and such a service) |
| 2.3.8 | Android 12+: other apps' overlays hidden | An overlay disappears while SeedTag is on screen. | ❓ |
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
| 3.1 | Old low-RAM phone (finding 6) | `largeHeap` is now on: check 1.18 on the oldest phone available. | ⬜ |
| 3.2 | Screen timeout mid-entry (finding 7) | Fixed on `main`: see 1.27. | ✔️ Pass, 2026-11-11 (1.27) |
| 3.3 | Back gesture (finding 7) | Fixed on `main`: see 1.28. | ✔️ Pass, 2026-11-11 (1.28) |
| 3.4 | Generate tapped twice (finding 2) | Fixed on `main`: see 1.24. | ✔️ Pass, 2026-11-11 (1.24) |
| 3.5 | "Already read-only" tag (finding 9) | Fixed on `main`: Lock on a tag that's already read-only (verify it first) says "Already read-only", not "Tag is now read-only". | ⬜ |
| 3.6 | Late results (finding 12) | Fixed on `main`. Double-tap **Encrypt & write**: one encryption. Write to a blank tag and Cancel during the write: "Written, not checked", no prompt reappearing. Cancel right as a write finishes, then start a Verify scan: its prompt stays. | ⬜ |
| 3.7 | Unreadable tag treated as empty (finding 14) | Hardened on `main`: on a Type 2 tag that every read calls empty, the raw TLVs are checked before writing without asking. Check that nothing regressed: Back up to an NDEF-formatted empty NTAG / Ultralight C (written without a prompt, *Backup written*), and to one holding a backup (the overwrite prompt). The failure itself is hard to provoke: a tag with data at a marginal position. | ⬜ |
| 3.8 | NFC toggled from quick settings (finding 14) | Reader mode is now turned on again when NFC comes back on with SeedTag in front. Turn NFC off and on from the shade while SeedTag is open, on Back up's tag check and on Verify; tags must still reach the app (and no other app's tag popup appears). | ✔️ Pass, 2026-11-11 |

## 4. Branch `tag-write-password` (before merging)

Superseded by board item B17 (branch `b17-tag-write-password`, NTAG213/215/216 only for now): rows 7.42 to 7.51
and 5.17 to 5.18. The rows below are kept for the MIFARE Ultralight C work that's still to come.

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
| 5.3 | `seedtag.py` with neither library (a fresh `python3 -m venv`, or a Python without packages): `verify` and `decrypt` a tag, then `encrypt` | `verify` / `decrypt` print the slow built-in note, take about a minute per password tried, then give the same fingerprint and words; `(Argon2id from built-in)`. `encrypt` fails with "needs the cryptography package", no traceback. Automated: CI decrypts a vector this way (`SEEDTAG_SLOW_TESTS=1`); still worth one run on your own recovery machine. | ⬜ |
| 5.4 | First CI run (Actions tab, "Run workflow" on `main`) | Green; the summary shows the SHA-256, the same as a local `make build` of the same commit. | ✅ |
| 5.5 | CI on a `v*` tag | Starts by itself. | ✅ |
| 5.6 | `sign-release.sh --expect <wrong hash>` on the signing machine | Refuses, nothing signed. | ✅ |
| 5.7 | `sign-release.sh --expect <CI hash>` | Signs; certificate check passes; writes `seedtag.apk.sha256`. | ✅ |
| 5.8 | Install the newly signed release over the previous release | Updates in place (same certificate), no uninstall needed. | ✅ (0.1.0 to 0.3.0 were updated this way, presumably) |
| 5.9 | `seedtag.py` with the tag's text as copied by hand or from an NFC app (board B8): spaces and line breaks, no `=`, `-` and `_`, `en` in front; also a file argument | All accepted, same fingerprint. One character missing or a wrong first character gets its own message, not "wrong password". | ⬜ |
| 5.10 | `seedtag.py verify --builtin` and `decrypt --builtin` with `cryptography` installed (board B10); `encrypt --builtin` | `(Argon2id from built-in)`, same result. `encrypt --builtin` is refused (exit 2). | ⬜ |
| 5.11 | `seedtag.py` on Windows with `py` (board B10) | `py seedtag.py verify <FILE>` works as on Linux. | ⬜ |
| 5.12 | Share a tag's text from NFC Tools (or the NFC app you use) straight into a file, then `python3 -I -S seedtag.py verify <FILE>` (H2) | *The text is exactly what SeedTag writes.* If it says it isn't, that app changes the text: note which app, so the README can name one that doesn't. | ⬜ |
| 5.13 | First CI run after `-W error` was added to the Python tests (board B10) | Green. | ✅ |
| 5.14 | `seedtag.py check` (H4): dump the NTAG213 with NFC Tools (OTHER, Read memory, ⋮ Export .nfc, as in the README) after a Back up with this version, then after writing a long text with NFC Tools and a SeedTag backup from before H3 (or just the text), and an empty tag after Erase | After this version's Back up and after Erase: *Clean*, exit 0. With old data after the backup: *NOT CLEAN*, the FAIL line names the pages, exit 1. | ⬜ |
| 5.15 | `python3 -I -S seedtag.py verify <DUMP>` with the dump from 5.14 (H4) | Asks for the password and prints the same fingerprint as the app; says the text is exactly what SeedTag writes. | ⬜ |
| 5.16 | `seedtag.py check` and `verify` on an NFC Tools text dump (*Read memory* saved as `.txt`) of the NXP NTAG213 holding a backup; then `check` on one of a non-NXP tag (serial number not starting with 04, e.g. tag 133 or tag 6) | NXP: the same lines as for its `.nfc` dump, plus a NOTE that the dump can't show whether it's a genuine NXP NTAG213; *Clean*. `verify` asks for the password and shows ✓. Non-NXP: a WARN that the serial number starts with 53, not 04; otherwise as for its backup (*Clean* if nothing else is wrong). | ⬜ |
| 5.17 | B17: `seedtag.py tag-key <DUMP or text>` with a protected tag's backup and its password; then `check` on dumps of it protected and after Remove write password | Prints the NTAG PWD and PACK (e.g. `f9a5e9d1` / `ca65` for the 12-word test tag with *sifra8*). Another NFC tool that sends raw commands: `1B` + PWD gets the PACK back. `check`: protected "A write password is on…", *Clean*; removed: "Settings at the factory defaults…", *Clean*. | `check` ✔️ 2026-11-25 (NTAG215 dumps); the raw PWD_AUTH ⬜ |
| 5.18 | B17: `seedtag.py check` on NFC Tools text dumps of the Fudan FM11NT021 (46 pages): with a backup, after Erase, and after Protect and Remove (7.51) | *NTAG213 lookalike: all 46 pages read*, a WARN that SeedTag doesn't officially support it (no maker or model named); otherwise as for an NTAG213 (*Clean* for a backup or an empty tag; "A write password is on…" when protected). Any lookalike with an NTAG21x's layout is checked the same way, whatever its serial number. | ✔️ Saved dumps, 2026-11-25: the 46-page ones (`clone_*`, `tag4_*`) *Clean*, except `clone_12words_over_empty_text.txt` (real leftover data; its `_fixed` dump *Clean*). Protected and removed (`fudan_protected.txt`, `fudan_protection_removed.txt`, and the Feiju's) *Clean* ✔️ 2026-11-25 |

## 6. Android 8 to 11

Several defences and details depend on the Android version, and the main test phone runs a newer one. Run this list
on an Android 8, 9, 10 or 11 phone, with the release-test APK. Rows marked *10–11* or *8–9* only apply there. Write
the phone's model and Android version next to each result.

What's different before Android 12: other apps' overlays can't be hidden, so they stay on screen, and only taps
that come through them are dropped. The idle warning covers the screen instead of blurring it. Before Android 14
(so on all of these), SeedTag can't hide its screen from accessibility services, it can only name them.

| # | Test | Steps | Expected | Status |
|---|---|---|---|---|
| 6.1 | Install and first start | `make uninstall`, `adb install seedtag-release-test.apk`, open it. Look at the launcher icon, the full-screen mode (swipe from an edge), dark and light theme. | Installs (the app needs Android 8.0 or later). The icon looks right (Android 8 introduced adaptive icons). Status and navigation bars are hidden and come back briefly on a swipe. Both themes are readable. | ⬜ |
| 6.2 | Main flow | Back up the 24-word vector with a generated passphrase to tag A, then Verify and Restore. Note how long each *Encrypting…* / *Decrypting…* takes. | All succeed, same fingerprint, same words. Argon2id may take longer on an old phone; note the seconds. No crash. | ⬜ |
| 6.3 | A tap through an overlay | Start an overlay app whose window covers a SeedTag button. Tap the button, also in the Overwrite, Weak password and Lock dialogs. | The overlay stays visible (it can't be hidden before Android 12). Taps through it are ignored and the red banner shows; nothing happens behind it. Without the overlay, the same buttons work. | ⬜ |
| 6.4 | *10–11:* partial overlay | An overlay that covers part of the screen but not the buttons. Tap a button, including in the Overwrite and Lock dialogs. | Taps are ignored and the red banner shows. Without the overlay, everything works normally. | ⬜ |
| 6.5 | *10–11:* no false positives | With no overlay app running, use every screen and dialog, including the system volume panel, a notification and a split screen. | No tap is blocked, unless something really covers the app. A tap blocked for no visible reason is a failure. | ⬜ |
| 6.6 | *8–9:* partial overlay | An overlay that covers part of the screen but not the buttons. Tap a button. | The tap works: Android 8 and 9 don't report a partly covered window, so only taps through the overlay itself are dropped (6.3). Expected, not a failure. | ⬜ |
| 6.7 | Accessibility banner | Enable an accessibility app (Settings › Accessibility), open SeedTag. With SeedTag open, turn accessibility off entirely, then on again (over adb: `adb shell settings put secure enabled_accessibility_services <pkg>/<service>`, and `adb shell settings delete secure enabled_accessibility_services`). | The banner names the app and says SeedTag can't hide its screen from it on this Android version. Turning accessibility as a whole on or off updates the banner at once. Adding or removing a second service while one is on only shows after leaving and returning (the live list update needs Android 13): expected. | ⬜ |
| 6.8 | Haptics | Keypad keys, a successful write, a failed scan (wrong tag), **Find NFC spot** with a tag, the *Still there?* buzz. | All are felt. On Android 8–10, success and failure feel like a long press, since the dedicated confirm and reject effects need Android 11. | ⬜ |
| 6.9 | Debugging warning (1.25) | With USB debugging on, open Back up; turn it off and come back. On Android 11, also with only wireless debugging on. | The red *USB or wireless debugging is on* item while it's on, gone when off. Wireless debugging only exists from Android 11. | ⬜ |
| 6.10 | Idle warning (1.27) | Enter a few words and wait 3 minutes without touching. | *Still there?* with a buzz, and the screen behind it **covered with the plain background** (not blurred, which needs Android 12): no word readable. **Keep** brings it back; a minute without an answer clears everything. The screen stays on meanwhile. | ⬜ |
| 6.11 | *8–9:* task hijacking | Hard without a test app: if you have one that declares `taskAffinity="rs.zivke.seedtag"`, start it, then SeedTag from the launcher and Recents. | Its screen never appears inside SeedTag's task, and Recents shows SeedTag's own blank thumbnail. Mark "not possible" without a test app. | ⬜ |
| 6.12 | Back (1.28) | *8–9* (three-button navigation): the Back button on the Words step, the Password step with a password, and the Length step with words entered. *10–11* with gesture navigation: a back swipe from the left and right edges over the keypad. | As in 1.28: Words → length step with the words kept; with a password it asks first; elsewhere with input it asks *Leave SeedTag?*. | ⬜ |
| 6.13 | Privacy basics | Screenshot and screen recording (also with a dialog open), the Recents thumbnail, Home and back, the screen off and on. With SeedTag in front, hold a tag; switch NFC off and on from the quick settings and hold it again (3.8). | Screenshots and recording blocked or black, the thumbnail blank. Everything typed is gone after Home or screen off. No system popup or other app reacts to the tag, also after NFC was switched off and on. | ⬜ |
| 6.14 | Small screen (1.30) | On the Password step with the keyboard open, and on Length and Done. | Where content goes on below, the bottom fade and ↓ show, and everything can be reached by scrolling. Nothing is cut off at the sides. | ⬜ |
| 6.15 | Low RAM (1.18, 3.1) | If the phone has little memory (2 GB or less): Back up and Verify, with a few other apps open. | Either it works, or an out-of-memory message appears and the app stays open. It never crashes. | ⬜ |

## 7. Expert board and outside remarks (from 2026-11-16)

Changes on `develop` from the [expert board review](2026-11-16-expert-board-review.md) (B…) and the outside remarks
on hidden data (H…, `TODO.md`). Each change adds its rows here in the same commit.

| # | Test | Expected | Status |
|---|---|---|---|
| 7.1 | Erase texts (B13): the Erase intro, the confirm question for a backup, and *Erased* | The intro says the whole data area is overwritten with zeros and read again, that it clears only this tag, and that Back up overwrites an old backup after asking. *Erased* says someone who copied the backup earlier keeps their copy, and when to move funds. | ✅ |
| 7.2 ⚠ | Erase: a very brief tap of a tag holding a backup on the Erase scan (B20a) | Never "blank, nothing to erase": either the backup is shown, or *Couldn't read the tag properly (it may have moved away too soon)…*. | ✅ |
| 7.3 ⚠ | Erase: pull the tag away right at the end, after the zeros were read back (B20c; hard to time) | The message says the old backup was already overwritten and to hold the same tag still and tap again; tapping again finishes. No crash. | ✅ |
| 7.4 | Erase's password step: type something, then tap *Don't check the password* (B20f), then go back to checking | The field is gone, and when it comes back it's empty. | ✅ |
| 7.5 | Back up's words step and the checksum error (B27, B8) | The notice says only English BIP39 phrases, not Electrum, SLIP-39 (Shamir) or Monero. A phrase of valid words with a bad checksum says that if every word is right, it isn't a BIP39 phrase (an Electrum phrase, for example). | ✅ |
| 7.6 | Restore, after the words are shown (B27) | An *If your wallet shows no funds* notice: wallet passphrase not stored, other accounts or address types. | ✅ |
| 7.7 | Fingerprint texts on *Backup written* and Verify (B27) | They say the fingerprint is SeedTag's own, not the one a wallet shows. | ✅ |
| 7.8 ⚠ | Read-only names (B41): the Back up, Verify and Done screens, the confirm dialog, the result | *Make it read-only*, *Make read-only*, *Tag is now read-only*. "Lock" appears only for an attacker locking a tag against reading. | ⬜ |
| 7.9 | Back up one tag, then Verify a second tag holding a different seed (B41) | Title *Not the backup you just wrote*: a different seed from the backup written in this session, and its fingerprint. Verify the first tag: *Verified*, *Same seed*. | ✅ |
| 7.10 | Each Settings link (NFC off banner, accessibility banner, debugging warning) with words or a password entered, then again with nothing entered (B29) | With secrets: *Leave SeedTag?*, background blurred; *Stay* keeps everything; *Clear and open Settings* wipes and opens the page. For NFC it also suggests the quick-settings button. With nothing entered: Settings opens straight away. | ✅ |
| 7.11 | A Settings link whose page the phone can't open (B29; it may not happen on your phone) | The main Settings page opens; no crash. | ✅ |
| 7.12 | Argon2 steps after the `runKdf` change (B42): Back up's encryption, Verify, Restore and Erase's password check, each once with the right password and once with a wrong one | Same results and messages as before. Tapping again while one runs: nothing starts twice (or *SeedTag is still busy…*). Home during the run, then back: wiped, no result shown late. | ✅ |
| 7.13 | Back up a throwaway seed, then Verify and Restore it (H2) | *The tag's message: Exactly as SeedTag writes it*, no caution notice. *Backup written* as before (the read-back now compares the whole message). | ✅ |
| 7.14 | With NFC Tools: write the same Base64 text to a tag as a Text record with another language code (or add a second record, a URL say), then Verify it in SeedTag (H2) | *Not as SeedTag writes it: see below*, and the notice *This tag isn't exactly as SeedTag writes it* naming the difference (*a language code other than en*, or *other records besides the backup*). The seed still opens. | ⬜ |
| 7.15 | With NFC Tools: write the Base64 text with a space or line break in it, then Restore (H2) | The same notice, naming *text that isn't exactly what SeedTag writes…*; the words are shown. | ✅ |
| 7.16 | The disclaimer before you start, the About page, and Back up's check of a MIFARE Classic tag (tested tag corrected) | The beta notice says *only been tested with NXP NTAG213 tags*. The Classic warning suggests *an NTAG213, the chip SeedTag has been tested with*. | ✅ |
| 7.17 | H3, the main case. With NFC Tools, write a long plain-text record (any throwaway text that fills most of the tag) to the NTAG213. Back up a 12-word throwaway seed over it (SeedTag asks first). Then dump the memory with NFC Tools (Flipper `.nfc`) | The scan shows a 4th step, *Clearing old data after the backup*; *Backup written* with no notice. In the dump, every byte after the backup's `FE` page is `00` (pages after the terminator's page up to page 39). Verify still ✓. | ✅ |
| 7.18 | H3: Back up a 12-word seed over a 24-word backup, then dump | As 7.17: nothing of the older backup's last 18 characters left after the new one. | ✅ |
| 7.19 | H3: pull the tag away right after *Reading it back to check it* (hard to time; try a few times) | *Backup written* with the caution *Old data may still be on the tag* and *Clear old data*. Tapping it and holding the same tag: the notice goes away; a dump shows zeros after the backup. A different tag: *This isn't the tag you just wrote*. | ⬜ |
| 7.20 | H3: Back up to a tag that isn't NTAG or MIFARE Ultralight (Type 4 or 5), if you have one | *Backup written* with the notice that old data may still be there and to use a new tag if it ever held anything secret. | ⬜ |
| 7.21 | Verify with a wrong password: once with a 7-word passphrase where one word is misspelt (e.g. `abandn` for `abandon`), once with every word a real BIP39 word but one of them wrong, once with a password that isn't words (`Tr0ub4dor&3`) (B45) | Title *This password doesn't open the tag*, three numbered steps (Show and compare; the right tag; seedtag.py). The misspelt case adds *Word 2 isn't a BIP39 word…* (its position), the all-words case *Every word is a BIP39 word…*, the other case neither. The password stays in the field, so Show works. | ⬜ |
| 7.22 | Erase, *Check the password first*, with a wrong password (B45) | The same title; step 2 says you can erase without checking if you're sure it's the right backup, step 3 suggests seedtag.py before erasing. | ⬜ |
| 7.23 | The password keyboard on Back up's Password step, Verify and Erase (B45), on a narrow phone if you have one | Its keys reach almost to the screen's edges (about 4dp left), wider than before; nothing cut off, no key overlaps another. The word keypad is unchanged. | ⬜ |
| 7.24 | B16 (tag flow rewritten, behaviour meant to be unchanged): Back up to a blank tag (check, write, the second tap, *Backup written* with the fingerprint), then again to the same tag (asks before overwriting; Overwrite) | As before: one prompt at a time, the Detected buzz on each tap, Success at the end. Done shows the fingerprint. | ⬜ |
| 7.25 | B16: during any scan, lift the tag too early once (an error in the prompt, it stays open), then tap properly; and Cancel a scan, then tap the tag | The second tap works without starting over. After Cancel, nothing happens and no prompt comes back. | ⬜ |
| 7.26 | B16: Verify, then Lock with **another** tag held (refused), and Verify again: *same seed as the backup just written* | Lock refuses the other tag and changes nothing. Verify compares with the backup just written. | ⬜ |
| 7.27 | B16: Erase a tag that holds the backup just written (scan, Erase, hold it) | *Tag erased*; the Erase tab is back at its start. Done's *Make tag read-only* then says *Write or verify the tag first.* (Done still shows *Backup written*: B15d, not changed here.) | ⬜ |
| 7.28 | B12: on the NTAG213-compatible tag (UID not starting with 04, NFC Tools lists only NfcA and Ndef), write a long text record with NFC Tools, then Back up a 12-word throwaway seed over it; export its memory (*Read memory*) | *Backup written* with no notice about old data. The dump: the backup, `FE 00` in its last page, then `00 00 00 00` in every page up to 27h; pages 28h onwards as before (`00 00 00 BD`, `00 00 00 FF`, …), unchanged. | ✅ |
| 7.29 | B12: Erase that tag (scan, **Erase without checking**, hold it), then export its memory | *Tag erased* says the data area was read back page by page and nothing is left. The dump: `34 03 04 D8` / `00 00 00 FE` in pages 5-6, zeros up to page 27h; pages 28h onwards unchanged. Then Back up still works on it. | ✅ |
| 7.30 | B12: on that tag, Back up over a backup with a very quick tap (touch and pull away at once), a few times | Never written without the overwrite question: either the question, or *Couldn't read the tag properly*. | ✅ |
| 7.31 | B12 regression on the NXP NTAG213: Back up over a longer text record, then Erase; export memory after each | The same as 7.28 and 7.29 (zeros up to page 27h, pages 28h-2Ch unchanged: `00 00 00 BD`, `04 00 00 FF`, `00 05 00 00`). | ✅ |
| 7.32 | B15a: Back up to a tag; when the progress shows *Writing the backup*, press **Home** with the tag still on the phone, then come back. Repeat a few times (it's a matter of timing) | Either *Backup written* with the fingerprint (it used to be missing; Verify of that tag then says *Same seed*), or, if Android ended the tag connection when the app left, a message that what the tag held may already be gone. Never *Backup written* without a fingerprint, and never nothing at all. | ⬜ |
| 7.33 | B15b: Erase a backup tag; during *Overwriting it with zeros*, press **Home** and lift the tag at once; come back | A message that part of what the tag held may already be gone (it used to say nothing). Scanning on the Erase tab and erasing again finishes it. | ⬜ |
| 7.34 | B15c/e: Erase a tag, and press **Home** while it's being erased (tag stays on); come back. Separately: scan a tag on the Erase tab, tap **Erase without checking** so *Erase this tag?* is open, press **Home**, come back. Also with **Make tag read-only**'s question open | First: *Tag erased* and the Erase tab back at its start (no old scan, no Erase button), or the 7.33 message. Second: no question is open, and the Erase tab is at its start: it has to be scanned again. | ⬜ |
| 7.35 | B15d: Back up a tag, Verify it (*Same seed*), then Erase that tag | *Tag erased*. Back up is back at *Check your tag*, not *Backup written*; Verify shows no result; *Make tag read-only* isn't offered for it. | ⬜ |
| 7.36 | B15f: open a message (e.g. a wrong password's dialog on Verify), leave the phone untouched 3 minutes so *Still there?* comes up, tap **Keep**. Then again, without Keep, for the full minute | Keep: *Still there?* closes and the wrong-password message is back. Without Keep: *Cleared* after the minute. | ⬜ |
| 7.37 | Back up: type all the words and a password, tap **Back** twice (to the word count); tap Verify. Then **Stay**; then again, **Clear and switch**, and back to Back up. Also with only a few words typed (Back once from the Words step) | *Clear the words?* asks first (no password is mentioned: going back from the password step already cleared it). Stay keeps everything. After Clear and switch, Back up's word count step has no words: choosing a count starts from the first word. With no words typed, switching tabs doesn't ask. | ⬜ |
| 7.38 | Make read-only on a tag this phone can't make read-only (e.g. a MIFARE Classic, NTAG 424 or Type 4 tag holding a backup, or any tag Android doesn't offer it for), after Verify of it | A buzz and *Can't be made read-only* (nothing was changed). The scan prompt is gone: no *Hold the tag* waiting for another one. Make read-only can be tried again. | ⬜ |
| 7.39 | Erase: scan a backup tag, open *Erase this backup?* both after checking its password and with **Erase without checking**; also for a tag with other data and an empty one | Both backup texts say "Make sure you have the seed saved somewhere else." None of the four ends with "Then hold the same tag to the phone." After **Erase**, the scan prompt says to hold the same tag. | ⬜ |
| 7.40 | Back up, Verify or Restore: *Safest on a spare phone* → *Learn more*; also the About page if it shows the same text. Then *Go offline first* → *Learn more* (with airplane mode off) | The second paragraph starts "A spare phone that's never online removes that risk:", and says "To prepare it, while it can still go online:". The last paragraph reads: "If you don't have a spare phone, you can use your everyday phone after making sure it's offline, but be aware that it is a less safe option. For more details, read Go offline first, in the checklist on Back up, Verify and Restore." *Go offline first* says "On your everyday phone, the following reduces the risk but can't remove it completely:". *Use at your own risk* → *Learn more* (Back up's checklist) and on the About page says "its developer is not responsible". | ⬜ |
| 7.41 | B24: turn on USB debugging; open Back up's checklist, *USB or wireless debugging is on*. On Android 12 or later, and if available on a phone with Android 11 or older | Android 12+: unchanged ("…or turn on an accessibility service. Don't use this phone…"). Android 11 and older: also "…and on this Android version it can also mirror the screen, SeedTag's included." | ⬜ |
| 7.42 | B17: Back up's first step, *Check your tag*, on the NXP NTAG213/215, a UID 53 tag (Feiju), the UID 1D clone (Fudan) and a blank tag | Two new lines. NXP chip: *Read-only*: "Can be made read-only for good, on the Verify tab once the backup is verified"; *Write password*: "Possible (NTAG215): set on the Verify tab once the backup is verified". Feiju and Fudan: "Possible, at your own risk: it's an NTAG21x lookalike, which SeedTag doesn't officially support, and setting one may destroy the tag…" (no maker or model named). A tag SeedTag can't protect (e.g. MIFARE Ultralight C): "Not possible: SeedTag can give one only to an NTAG213, NTAG215 or NTAG216, or an NTAG21x lookalike that works like one". Blank: both "Not known yet: the tag is blank. Check it again once the backup is written." The check itself works on every tag, as before. | NXP NTAG215: ✔️ 2026-11-25. Fudan: the check failed (the chip ended the session after GET_VERSION); fixed, retest with the Feiju ✔️ 2026-11-25. |
| 7.43 | B17: Verify a backup on the NXP tag, the Feiju and the Fudan tag | NXP: *Chip* "NTAG215" (or 213/216), *Write password* "Off: any phone can rewrite or erase the tag", a **Protect from writes** button. Feiju and Fudan: *Chip* "NTAG21x lookalike, not made by NXP: not officially supported", *Write password* "Off…", **Protect from writes**. | NTAG215: ✔️ 2026-11-25. Feiju and Fudan (now recognised by their layout): ✔️ 2026-11-25. |
| 7.44 ⚠ | B17: **Protect from writes** on the NXP tag: *Give the tag a write password?* → **Protect**, then the three taps, taking the tag away in between | Prompts: "…store its write password (step 1 of 3)", "Stored. Take the tag away, then hold it to the phone again… (step 2 of 3)", "Switched on. Take the tag away, then hold it once more to check it (step 3 of 3)". Ends with *Write password on*. Verify then shows "On: writing needs it, reading doesn't" and **Remove write password**. A dump: CFG0 `04 00 00 03`, CFG1 `00 05 00 00`, the last spare page still zero (step 3 also tries to switch the password off without it: CFG0 must still read `04 00 00 03`); `seedtag.py check` says "A write password is on" and *Clean*. | ✔️ Pass, 2026-11-25 (NXP NTAG215); again with step 3 also testing the settings page ✔️ 2026-11-25 |
| 7.45 | B17: the protected tag in other apps | NFC Tools (or another phone) reads the backup without any password; writing to it fails. | ✔️ Pass, 2026-11-25 (NXP NTAG215) |
| 7.46 | B17: the protected tag in Back up's first step, Erase and Make read-only | Back up: *This tag has a write password*, *Write password* "On (see above)". Erase: *Has a write password*, no Erase button. Verify: no **Make tag read-only**, instead "The tag has a write password: remove it first, then make the tag read-only." Nothing changed on the tag. | ✔️ Pass, 2026-11-25 (NXP NTAG215) |
| 7.47 | B17: Verify the protected tag, **Remove write password** | *Write password removed*. Verify shows "Off…" again; Back up, Erase and Make read-only work again. A dump: only CFG0 changed, to `04 00 00 FF`; `seedtag.py check` *Clean*. | ✔️ Pass, 2026-11-25 (NXP NTAG215) |
| 7.48 ⚠ | B17: interrupt Protect: pull the tag away during each of the three taps; tap **Cancel** after step 1 and after step 2; leave the app between taps; pull it away during **Remove write password** | Never a tag with a write password SeedTag can't remove. A step that failed stays armed: holding the tag again finishes it (after a failed step 2 it may go back to step 1). Cancel after step 2: *Write password on, not checked*. After leaving the app, Verify again, then **Protect from writes** or **Remove write password** finishes it (a tag already protected with this backup's password skips to step 3). Check each end state with a dump and `seedtag.py check`. | ✔️ Pass, 2026-11-25 |
| 7.49 ⚠ | B17: **Protect from writes** on a chip that answers like an NTAG21x but isn't NXP's: the Feiju (UID 53, GET_VERSION `00 53 04 02 01 00 0F 03`), a spare one | *Not officially supported*: "This tag is an NTAG21x lookalike: it works like NXP's NTAG21x chips, but NXP didn't make it, and SeedTag doesn't officially support it. Setting a write password may destroy the tag…", **Protect anyway**. After the three taps either *Write password on* or *This chip doesn't enforce a write password* ("SeedTag left it switched off: the tag works as before."). Then as 7.45 to 7.47, with dumps after Protect and after Remove. | Protect ✔️ 2026-11-25 (`feiju_protected.txt`: AUTH0 `03`, ACCESS `00`, PWD and PACK read `00`, as on an NXP chip; `check` *Clean*). NFC Tools can't write or erase it; SeedTag shows the password on and refuses Erase and Make read-only ✔️ 2026-11-25. Remove ✔️ 2026-11-25 (`feiju_protection_removed.txt`: AUTH0 `FF`, the rest unchanged; `check` *Clean*). |
| 7.50 | B17: on the Fudan tag, after the fix in 7.42: Back up's first step, a backup written to it, Erase of it, Make read-only offered on Verify | All work as before B17; *Write password* says "Possible, at your own risk…". | ✔️ 2026-11-25 |
| 7.51 ⚠ | B17: **Protect from writes** on a spare Fudan FM11NT021 (UID 1D), with a throwaway backup | The same *Not officially supported* warning as in 7.49 (no maker or model named), **Protect anyway**. After the three taps *Write password on* or *This chip doesn't enforce a write password*. Then as 7.45 to 7.47. Dumps after Protect and after Remove: CFG0 (page 29h) `00 00 00 03`, then `00 00 00 FF`; PWD (2Bh) always `00`; PACK (2Ch) shows this backup's 2 bytes while it's on (this chip lets it be read), `00 00 00 00` again after Remove; `seedtag.py check` *Clean* both times (WARN: lookalike; NOTE about the PACK while on). | ❌ 2026-11-25: tap 2 said *This chip doesn't enforce a write password*: SeedTag took the readable PACK for a readable password (`fudan_pwd_pack_stored.txt`: PWD `00`, PACK `AA A8`, AUTH0 `FF`). Fixed: only a readable PWD stops it. Retest. Retest, Protect ✔️ 2026-11-25 (15-word backup, `fudan_protected.txt`: AUTH0 `03`, ACCESS `00`, PWD `00`, PACK `DA 3E`; `check` *Clean*). Remove ✔️ 2026-11-25 (`fudan_protection_removed.txt`: AUTH0 `FF`, PACK `00 00 00 00` again, the rest unchanged; `check` *Clean*). NFC Tools can't write or erase it; SeedTag shows the password on and refuses Erase and Make read-only ✔️ 2026-11-25. |
| 7.52 | B4: the ✕ in the top bar, on Back up, Verify, Restore, Erase, the NFC spot page and About | ✕ (*Close SeedTag*) at the far right on every screen, next to ⓘ (no *Find NFC spot* in the top bar any more). Tap ✕: *Close SeedTag?* with **Close SeedTag** and **Stay**. **Stay**: everything as it was, nothing cleared. | ✔️ Pass, 2026-11-25 |
| 7.53 ⚠ | B4: **Close SeedTag** at the end of a backup (*Backup written*, *When you're done*) | The question lists "the fingerprint of the backup you just wrote (…: note it first if you need it), and Verify won't be able to say whether a tag holds that backup". **Close SeedTag**: the app is gone and not in Recents; `adb shell pidof rs.zivke.seedtag` prints nothing (the process ended). Opening it again starts fresh, on Back up's first step. | ✔️ Pass, 2026-11-25 |
| 7.54 | B4: **Close SeedTag** on the Verify result and the Restore result (words shown, and after **Hide words**) | The question lists "the Verify result", or "the Restore result; the restored words" (only the first after Hide words). Closing works as in 7.53. | ✔️ Pass, 2026-11-25 |
| 7.55 | B4: Close SeedTag while a tag is being read or written: hold the tag for a write, an Erase or a Protect, and look at the ✕ while *Tag found* shows | ✕ and the *Close SeedTag* buttons can't be used while the tag is being worked on; the job finishes normally, then ✕ works again. | ✔️ Pass, 2026-11-25 |
| 7.56 | B4: the explanations: Back up's checklist, *Nothing is kept* → Learn more; About, *Closing SeedTag*; the checklist's offline item | "Leaving SeedTag clears what you typed or decrypted, but a few copies can't be cleared by an app: …", then "Close SeedTag (✕ at the top right, and at the end of Back up, Verify and Restore) ends it at once…". The offline item says "tap Close SeedTag (✕, top right) and restart the phone before it goes back online". About's footer: "Leaving SeedTag clears what you've typed in it." | ✔️ Pass, 2026-11-25 |
| 7.57 | The NFC-spot link on every tab: Back up's first step, Verify's and Restore's password step, Erase before the scan | "Not sure where to hold the tag? Find your NFC spot" under the heading on all four; it opens *Where to hold the tag*, and Back returns to the same tab with nothing lost. The top bar has only ⓘ and ✕. | ✔️ Pass, 2026-11-25 |
| 7.58 | The tested-tags texts: Back up's checklist, *Use at your own risk* → Learn more; About's disclaimer; a MIFARE Classic tag in Back up's first step | "It has only been tested with NXP NTAG21x tags (NTAG213 and NTAG215): other tag brands and types may not work as described." The Classic hint: "Use another tag (an NTAG213 or NTAG215, the chips SeedTag has been tested with, for example), or use this one only to practise." | ✔️ Pass, 2026-11-25 |

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
