# Changelog

All notable user-visible changes to SeedTag. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are the
`versionName` in `app/build.gradle.kts`.

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
