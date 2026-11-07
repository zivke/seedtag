# Changelog

All notable user-visible changes to SeedTag. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are the
`versionName` in `app/build.gradle.kts`.

## [Unreleased]

### Added
- Verify, Restore and `seedtag.py` forgive the most common typing mistakes:
  if the password is wrong, it's tried again without extra spaces (leading,
  trailing or doubled), then also in lower case. If that's what matched, the
  app says so, so you can correct your written copy. Each retry takes another
  few seconds, only when the password didn't match.

### Changed
- The generated passphrase comes with a reminder to label it "SeedTag
  password": it's made of the same words as a seed phrase, so an unlabelled
  copy is easy to mistake for one.
- README: what the fingerprint does and doesn't reveal, and not to keep it
  next to a partly readable copy of the seed.

### Fixed
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
- Cancelling a scan, or leaving the app while a tag was being read, no longer
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
