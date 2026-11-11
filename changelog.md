# Changelog

All notable user-visible changes to SeedTag. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are the
`versionName` in `app/build.gradle.kts`.

## [Unreleased]

### Added
- Back up, Verify and Restore warn when USB or wireless debugging is on, with
  a link to Developer options: a computer the phone was ever allowed to debug
  can silently replace SeedTag or turn on an accessibility service. Android
  shows these settings without any permission.

- `seedtag.py decrypt` and `verify` work without any package: when neither
  `cryptography` nor `argon2-cffi` is installed, they use a built-in Argon2id
  and AES-GCM written in plain Python (about a minute per password tried).
  So a copy of `seedtag.py` and any Python 3.8 or later are enough to
  recover a tag. `encrypt` still needs `cryptography`.

- The screen no longer times out while seed words, a password or restored
  words are on the phone, which wiped them mid-entry. Instead, after
  3 minutes without a touch SeedTag asks *Still there?*, and clears
  everything a minute later unless you tap **Keep**. While it asks, the
  screen behind it is blurred (covered before Android 12), so words left
  on screen aren't readable.

### Changed
- SeedTag now says it's beta software, in the README, on the About page and
  in the disclaimer shown before you start: it's still in development and
  has only been tested with MIFARE Ultralight C tags, so test any other tag
  before relying on it.
- Back no longer leaves the app with your input. On the Words and Password
  steps it does what the step's **Back** button does, and going back from
  the Password step asks before clearing a password (the on-screen **Back**
  too). Anywhere else, it asks before leaving while words, a password or
  restored words would be lost.
- CI also tests `seedtag.py` with the `cryptography` package (hash-pinned),
  the Argon2id implementation the README recommends, and the test vectors
  now cover 18- and 21-word phrases too.
- `tools/check-apk.sh` also fails a release APK that is debuggable or
  test-only, allows backups, or has an app component besides the three
  known ones; `make debug` checks with `--debug`.
- Releasing: `tools/sign-release.sh` now requires `--expect` (or an explicit
  `--no-expect`). It refuses to sign from a clone with uncommitted changes
  or whose `HEAD` isn't a `vX.Y.Z` tag that `git verify-tag` accepts (that
  check can be skipped with `--no-verify-tag`), and refuses an APK built from
  a different commit. The
  README's signing steps now sign from a fresh clone of a signed tag, after
  reviewing the diff since the last release, and `.devcontainer/README.md`
  explains how to take GitHub push access away from the container.
- A generated passphrase is now typed back from the written copy: *Repeat
  password* starts empty, and after **I've written it down** the passphrase
  is hidden while you type it, so a mistake in the copy is caught before the
  tag is written. Generating again (**Generate a different one**) asks first,
  since the old one may already be written down.
- Back up and the README say that only English BIP39 phrases are supported,
  and that a wallet's BIP39 passphrase (a "25th word") isn't stored and must
  be backed up separately; it isn't SeedTag's own password.
- Only the generated passphrase is rated *Strong*. A password you type
  yourself is shown as "≈N bits at most" and always needs a confirmation
  before writing, since SeedTag can't recognise a well-known phrase. The
  estimate also stopped over-crediting typed passwords: capitals and
  punctuation no longer hide BIP39 words, and a repeated word, token or
  pattern counts once (`correct horse battery staple` went from 86 to 43
  bits, `password password password` from 84 to 20). A seed word is also
  caught when it's joined to digits or punctuation (`legal1`).
- Holding ⌫ keeps deleting, on the password keyboard and the word keypad.
  On the keypad it stops at the start of the word being typed: words already
  entered are still removed one tap at a time, so holding the key can't wipe
  the phrase.
- *Go offline first* says plainly that using your everyday phone, if you
  don't have a spare one, carries a risk that restarting it and turning on
  airplane mode reduce but can't completely remove.
- *Read every screen carefully* now says to read everything before you
  actually do anything, not just before you tap.
- Bouncy Castle, used only for Argon2id, updated from 1.78.1 to 1.86. The
  key derivation is unchanged: the test vectors still match byte for byte.
- Back up and `seedtag.py encrypt` refuse a new password that starts or ends
  with a space, or has two spaces in a row. A written copy rarely shows them,
  and Verify and Restore can only forgive spaces that were *added* when typing,
  not ones that were part of the password when it was set, so such a backup
  could be impossible to open from the written copy.
- The password strength estimate no longer rates BIP39 words you choose
  yourself like randomly generated ones: they count about 6 bits each instead
  of 11, and words that follow each other in the list (abandon ability able…)
  count almost nothing. A phrase like the list's first six words used to pass
  as strong; it now asks for confirmation. The generated passphrase still
  counts 11 bits per word (≈77 bits).
- **Make tag read-only** no longer claims more than it does. It stops the
  backup from being overwritten or erased, but on NTAG21x and some Ultralight C
  tags it leaves the chip's password settings open, so someone holding the tag
  could still lock it against reading. The app and README now say so, and
  advise keeping the tag out of strangers' reach (a metal box or shielding
  sleeve) and keeping other copies.
- The README's *Using it safely* says to turn off USB and wireless debugging
  and revoke debugging authorizations on the phone used for real seeds, and
  not to use a development phone for them.

### Fixed
- A Verify or Restore result from before a new backup is cleared once the
  backup is written, instead of staying on its tab and comparing with the
  wrong backup. *Backup from this session* now only changes once a tag has
  really been written, not already when a write was started and then
  canceled or failed.
- A tag that can't be read properly is no longer taken for an empty one. If
  every read of an NTAG or MIFARE Ultralight tag finds no message, the app
  checks the tag's raw data before writing to it without asking, and asks
  you to hold it still and try again if a message is there after all.
- Switching NFC off and back on from the quick-settings panel while SeedTag
  is open turns its tag reader on again.
- A write or lock that finishes after you've started another scan no longer
  closes that scan's prompt while leaving it waiting for a tag. Canceling
  while a blank tag is being written no longer brings the scan prompt back by
  itself: the app says the backup was written but not checked. A quick double
  tap on **Encrypt & write tag** can't start two encryptions at once.
- The banner naming apps with accessibility access now also updates while
  SeedTag is open, for example when a service is switched on over adb, not
  only when you come back to the app.
- **Make tag read-only** on a tag that was already read-only no longer says
  it's "now permanently read-only": SeedTag changed nothing and can't tell
  whether it's permanent, and now says so. On a tag Android can't lock, the
  message says it's usually only possible for Type 1 and 2 tags (NTAG,
  Ultralight); the README says the same.
- Older and low-RAM phones are less likely to run out of memory during
  Argon2id, which needs 64 MiB at once: the app now asks Android for a larger
  heap (no permission involved).
- The app and `seedtag.py` now agree on every password edge case: both use
  the same explicit list of whitespace (the app missed U+0085), and the app
  lower-cases letters outside the Basic Multilingual Plane too. Neither can
  be typed on the app's keyboard. A password with a broken character gives a
  clear error instead of an unexpected one.
- A backup written by a future SeedTag version (a newer format) is
  recognised as a backup: the tag check says so, and overwriting it warns
  that it destroys a backup, not just "other data". Verify and Restore say
  the tag was written by a newer version and to update the app, instead of
  "Unsupported tag format version"; `seedtag.py` says the same.
- `seedtag.py` says what to install when the `cryptography` package is
  missing, instead of stopping with a traceback, and checks this before the
  slow key derivation. It now runs on Python 3.8 and later (it needed 3.10).
- The Done screen and the README advise keeping a copy of `seedtag.py` with
  the written password, so a tag can be recovered even if SeedTag and its
  website are gone.
- A Type 5 tag (ICODE, ST25TV) that already holds data is no longer
  formatted, wiping it without asking, when a too-quick tap made Android miss
  its data: as with NTAG and Ultralight tags, the app asks you to hold it again.
- On Android 10 and 11, where other apps' overlays can't be hidden, taps are
  also ignored while another app's window covers any part of SeedTag's (with
  the red banner), not only when the tap itself lands under it. An overlay
  could otherwise hide the warning of *Overwrite* or *Lock forever* while
  leaving its button clear.
- The letters of the seed word being typed are no longer kept as text: they
  live in a char array that's zeroed like the passwords, since four letters
  identify any BIP39 word. While the words are hidden (the default), the
  letters being typed are shown as dots too; the suggestions still show the
  matching words.
- `seedtag.py` falls back to `argon2-cffi` also when `cryptography` is
  recent enough but was built without Argon2id (OpenSSL older than 3.2),
  instead of stopping with a traceback. If neither is available, it says what
  to install. It now prints which Argon2id implementation it used.
- An unexpected error from the phone's NFC stack no longer leaves the scan
  window open and ignoring every tap: it's shown like any other tag error, and
  tapping again retries. Running out of memory during Argon2id (on phones with
  little RAM) now shows a message instead of closing the app.
- **Overwrite** only applies to the tag you were asked about. Before, once
  you'd confirmed, the next tag held to the phone was overwritten without
  asking, even a different one (another backup, say) after a failed tap. Now
  any other tag, or the same tag with different content, is asked about again.
- If the tag moves away while it's being overwritten, the app now says that
  its old content may already be gone, instead of only "moved away too early".

## [0.3.0] - 2026-11-09

### Added
- The About page says SeedTag is free, open-source software, links to its
  source code on GitHub, and links to a page where you can donate if you've
  found it useful. Links open in the browser, only when tapped; SeedTag itself
  still has no network access.
- Back up, Verify and Restore advise going offline before you start: safest
  is a spare phone that's never online; otherwise restart the phone, turn on
  airplane mode with Wi-Fi and Bluetooth off, and restart it again before it
  goes back online. The card says whether airplane mode is on (Android shows
  that without any permission; Wi-Fi and Bluetooth can't be checked without
  one). The README has a new *Using it safely* section.
- Back up starts by checking the tag, before anything is typed: hold it to the
  phone and the app says whether it works, whether it's read-only, blank or
  already holds data (a SeedTag backup or something else), and how long a
  backup it has room for. Nothing is written. Word counts the tag can't hold
  are grayed out, and **Use another tag** goes back to the check.
- **Find NFC spot** says how long a backup a tag has room for (for example "137
  bytes: room for a backup of any length"), or that it's too small for any.
- Verify, Restore and `seedtag.py` forgive the most common typing mistakes:
  if the password is wrong, it's tried again without extra spaces (leading,
  trailing or doubled), then also in lower case. If that's what matched, the
  app says so, so you can correct your written copy. Each retry takes another
  few seconds, only when the password didn't match.
- A warning when choosing the password, and in the README: if the password is
  lost, the seed can't be restored from the tag by anyone, not by the app,
  `seedtag.py` or the developer.
- Back up, Verify and Restore start with a warning to read every instruction
  on screen carefully, since some steps can't be undone.
- Back up, Verify and Restore say up front to have the NFC tag ready, and how
  to hold it: flat against the back of the phone, over its NFC spot, still
  until the app is done. The scan window repeats it.
- Haptic feedback: every key on the app's keypad and keyboard gives a light
  tap, and the phone vibrates when a tag is handled: a confirmation when a
  backup is written and checked, a tag decrypts or gets locked, and a
  "rejected" buzz when a tag can't be read or a step fails. It follows the
  phone's touch-feedback setting and needs no extra permission.
- Verify explains itself on screen: what it checks, to type the password from
  the written copy rather than from memory, when to do it (after every backup,
  before locking, then about once a year), and afterwards what the result and
  the fingerprint mean.
- An **About** page, from the top bar: the version, privacy (offline, no
  network permission, nothing stored), the protections on the phone, the
  encryption and `seedtag.py`, and the license.
- **Find NFC spot** in the top bar opens a finder for the phone's NFC antenna:
  slide a tag or any contactless card over the back of the phone until it
  vibrates and says *Tag detected*. It also says whether SeedTag can use that
  tag (NDEF and its size, blank, or read-only), with tips on where the spot
  usually is and what blocks it. Nothing is written to the tag and its
  contents aren't read.
- A disclaimer before the seed is entered, on the About page and in the
  README: SeedTag comes without any warranty, its developer isn't responsible
  for any loss or damage, and users must make sure themselves that the phone
  has no malware, since the app can't cover every possible attack.
- Protection from accessibility services, which can read the screen and see
  every tap. On Android 14 and later, SeedTag hides its screen and dialogs
  from every accessibility service that isn't an accessibility tool; screen
  readers such as TalkBack keep working. On every version, a red banner names
  the installed apps with accessibility access, with a shortcut to Settings ›
  Accessibility.

### Changed
- A new, calmer look. One fixed palette in dark and light (ink surfaces, a
  single amber accent) instead of wallpaper colors, so warnings look the same
  on every phone: red only for what is risky or permanent, green only for a
  good result. Each step shows *Step N of M* with a progress bar, and its main
  button stays at the bottom of the screen. The safety advice before Back up,
  Verify and Restore is one *Before you start* checklist with a short line per
  item and **Learn more** for the details, instead of a stack of cards. Back
  up, Verify and Restore are a segmented switch, hidden while words or a
  password are being typed. Results start with one clear status mark and a
  short list of facts (fingerprint, match with the backup just written). The
  tag check lists the tag's room, state and contents, and word counts are
  tiles. Passwords have a strength bar. **Make tag read-only** is the only
  red-outlined button. The top bar shows the app icon, and About opens from
  its ⓘ. The NFC spot finder and the scan window pulse while they wait for a
  tag, and every screen scrolls back to the top when a prompt closes.
- Copy-edited every text in the app: clearer phrasing, fixed punctuation, US
  spelling, and dashes instead of chains of colons.
- *Before you start* says to hold the tag to the phone only when the app asks,
  not the whole time: the app only reads or writes it while it's held over
  the NFC spot. It also says that everything typed or decrypted is deleted
  when you close or leave the app. *Read every screen carefully* comes first
  and asks you to open every **Learn more**.
- The NFC spot finder is now **Find NFC spot** in the top bar (*Where to hold
  the tag*), and **Check your tag** in Back up links to it. Its first step
  checks that NFC is on; the tips explain that small tags and rings have an
  NFC symbol to put right against the spot. When it detects a tag, the phone
  gives two firm, longer pulses, easy to feel through the hand holding it.
  This adds the `VIBRATE` permission, granted at install without a prompt: it
  only runs the vibration motor and gives no access to anything.
- **Check your tag** explains what the scan is for: to inspect the tag before
  anything is typed, with nothing written.
- The generated passphrase comes with a reminder to label it "SeedTag
  password": it's made of the same words as a seed phrase, so an unlabeled
  copy is easy to mistake for one.
- README: what the fingerprint does and doesn't reveal, and not to keep it
  next to a partly readable copy of the seed.
- Warnings stand out from the rest of the screen: a red card with a red
  border, ⚠ and a bold title (the password warning, seed words in the
  password, private-only Restore, "not finished yet" after a backup, another
  app covering SeedTag). Dialogs that confirm something risky or permanent
  (overwrite, weak password, lock) and error messages have a red ⚠ icon, and
  the risky choice is in red.
- Verify and Restore are now two steps, like Back up: first the password and
  Scan tag, then the result on a screen of its own, without the password field
  and keyboard. **Verify another tag** and **Restore another tag** start again
  with an empty password, and each tab only shows its own result. The password
  field stays in view above the keyboard while typing.
- New app icon: a key-fob NFC tag with a ₿ on a dark background, plus a
  single-color version for themed icons on Android 13+. The app's window
  background matches the new icon's.
- Building: the release signing key no longer lives in the dev container.
  `make build` makes an unsigned `seedtag-unsigned.apk`, which `tools/sign-
  release.sh` signs on the machine that holds the key, refusing any
  certificate but the release one. `make build-test` signs the release build
  with the debug key for testing, and every build prints the APK's SHA-256.
  The README has the steps and the release certificate's SHA-256.
- Building: every download is now checked against a pinned checksum: the
  Gradle wrapper and distribution, every dependency (Gradle dependency
  verification, cross-checked against the repositories with `make deps-
  metadata`), and in the dev container the base image (by digest) and the
  Android command-line tools. `tools/sign-release.sh` also writes
  `seedtag.apk.sha256` to publish with a release.

### Fixed
- A blank tag's size is checked before it's formatted, from its chip's
  memory (MIFARE Ultralight, Ultralight C, EV1 and AES, NTAG203 and
  NTAG21x), so a backup that doesn't fit is refused with nothing written. If
  formatting fails anyway, the error says the tag may be too small.
- "Tag too small" now says how many bytes the backup needs and the tag has,
  how many words the tag can hold, and to use a bigger tag.
- Passwords are no longer kept in memory as text. They live in one buffer
  that the app's keyboard edits in place and that's zeroed after use, so
  typing no longer leaves a copy of the password behind for every key, and
  the strength check no longer copies it either. Only **Show** (and the
  generated passphrase on screen) puts it on screen as text.

## [0.2.0] - 2026-11-07

### Changed
- Passwords are typed on the app's own keyboard (letters, digits, all ASCII
  symbols, common accented Latin letters), with BIP39 word suggestions. The
  app has no text fields any more, so the system keyboard, or any other
  keyboard app, never opens and never sees a password. A password with other
  characters can still be decrypted with `seedtag.py`.
- The word-entry keypad stays fixed at the bottom of the screen. Only the
  word list above it scrolls, and it follows the word being typed.
- The app runs full screen: the status and navigation bars are hidden and
  can be brought back briefly with a swipe from the edge.
- **Make tag read-only** is now a red button, so it stands out as the one
  permanent action, and it's only offered on Verify after a successful
  decrypt, no longer right after writing.

### Added
- Back up starts by asking how many words the phrase has (12, 15, 18, 21
  or 24). The keypad stops at that count, and **Next** is enabled once
  exactly that many words are entered and the checksum is valid.
- **Hide entered words**, on by default: words already entered are shown as
  dots, only the word being typed is visible. Leaving the app turns it back on.
- A password estimated below 60 bits, or one that uses words of the seed
  phrase, needs a confirmation before the tag is written. The estimate now
  counts repeated words once, gives words from the seed no credit, and treats
  repeats and runs (`aaa`, `123`) as weak. Generated passphrases never repeat
  a word or use one from the seed.
- Verify and Restore compare the fingerprint with the backup just written
  and say whether it's the same seed, with a warning if it isn't.

### Fixed
- Before writing, the app decrypts the encrypted backup once more, with a
  second key derivation, and writes nothing if that fails. A fault on the
  phone can no longer produce a tag that can't be decrypted. Encrypting takes
  about twice as long.
- Other apps can no longer draw over SeedTag to pose as its keypad or hide
  what a button does. On Android 12+ their overlays are hidden while SeedTag
  is on screen. On every version, a tap that comes through another app's
  window is ignored and a red banner says so. This adds the
  `HIDE_OVERLAY_WINDOWS` permission, which is granted at install without a
  prompt and gives no access to anything.
- The README has a new **Warnings** section on what other apps on the phone
  can still do: overlays on Android 8–11, accessibility services, fake apps.
- Another app can no longer slip its own screen into SeedTag's task (task
  hijacking, possible on Android 8 and 9) to pose as SeedTag.
- A tag that moved away too soon could read as empty. Before writing, the app
  now reads the tag twice and checks what Android read at discovery, so a
  failed read can't skip the overwrite confirmation. It also never formats a
  tag that is already NDEF-formatted.
- A failed read-back after writing asks for another tap to check the tag
  instead of calling it faulty. Verify, Restore and Lock say the tag couldn't
  be read instead of "empty" or "not the tag you verified".

## [0.1.1] - 2026-11-05

### Fixed
- **Make tag read-only** now only locks the tag that was last written or
  verified; any other tag is left unchanged.
- Writing to a blank, unformatted tag no longer reports a failure and then
  asks to overwrite its own backup. It now asks for a second tap and reads the
  tag back.
- Canceling a scan, or leaving the app while a tag was being read, no longer
  brings the scan prompt or an error back afterwards.
- Leaving the app while Argon2 is still running no longer puts the decrypted
  words back on screen.
- `seedtag.py` reports a mistyped word by position instead of echoing it, and
  its self-check after `encrypt` also runs under `python -O`.
- `check-apk.sh` also rejects `uses-permission-sdk-23` entries, and only
  allows this app's own receiver permission.

## [0.1.0] - 2026-11-03

### Added
- Back up a 12–24 word BIP39 seed phrase to an NFC tag, encrypted with a
  password (Argon2id + AES-256-GCM, format v1). Words are entered with the
  app's own keypad and checked against the wordlist and checksum.
- Optional generated 7-word passphrase, with a strength hint for typed passwords.
- Every write is read back and compared; a tag that already holds data is only
  overwritten after confirmation.
- Verify (✓ and a fingerprint, words stay hidden) and Restore (shows the words).
- Make the tag permanently read-only, behind a confirmation.
- No network permission, screenshots blocked, no backups; everything typed or
  decrypted is wiped when the app leaves the foreground.
- `tools/seedtag.py`: encrypt, decrypt and verify on a computer, byte-compatible
  with the app.
