# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android app **SeedTag** (`rs.zivke.seedtag`). It stores a password-encrypted
BIP39 seed phrase on an NFC tag and reads it back:

- **Back up**: the words are entered with the app's own keypad, checked
  (wordlist + checksum), turned into raw entropy, encrypted and written to the
  tag as one NDEF Text record. The blob is decrypted once before writing, and
  every write is read back and compared.
- **Verify**: decrypt and show only ✓ plus a fingerprint, never the words.
- **Restore**: decrypt and show the words.
- **Lock**: make the tag permanently read-only, behind a confirmation.

`tools/seedtag.py` is the desktop counterpart: it must stay able to encrypt and
decrypt exactly what the app writes, so a backup survives the app.

`minSdk` 26, `targetSdk` / `compileSdk` 35. Tested hardware: MIFARE Ultralight C
(137 bytes of NDEF space), which a 24-word blob (104 Base64 chars) fits.

## Build & run

JDK, Android SDK, Gradle cache, adb and the signing key are already set up in
the devcontainer; `.env` (gitignored) names the key (`KEYSTORE`) and holds
`KEY_PASS`. Never install, regenerate or read the key. Everything goes through
the `Makefile` (`make help` lists targets).

- `make test`: `:core:test` (JUnit, plain JVM) and `tools/test_seedtag.py`
  against the same vector files. No device, no Android classes.
- `make debug`: `seedtag-debug.apk`, debug key, no `.env` needed.
- `make build`: signed release `seedtag.apk` (R8 on). Fails before R8 if the
  key in `.env` is missing or can't be opened.
- Both builds finish with `tools/check-apk.sh`, which verifies the signature
  and **fails if the APK requests any permission except NFC**.
- `make connect DEVICE_IP=… [ADB_PORT=…]`, `make run` / `make run-debug`,
  `make logcat`, `make stop`, `make uninstall`, `make clean`, `make check` (+ lint).

There is no emulator and no instrumented tests: NFC needs a real phone and a
real tag. Anything beyond `make test` and a successful build has to be checked
on the device by the user. Say so rather than claiming a change works.

## Architecture

Two Gradle modules plus the Python tool:

- **`core/`**: pure Kotlin/JVM, no Android imports, Bouncy Castle only.
  - `Bip39.kt`: wordlist (resource `bip39-english.txt`, the official list,
    SHA-256 `2f5eed53…dbda`), words ↔ entropy, checksum, `suggest(prefix)`.
  - `SeedBox.kt`: the blob format (below), `seal` / `open`, Base64, `fingerprint`.
  - `Passphrase.kt`: 7-word generator from the BIP39 list, rough strength estimate.
  - `NdefText.kt`: Text-record payload decode (encode is `NdefRecord.createTextRecord`).
  - `Errors.kt`: `SeedTagException` subclasses; messages are shown to the user
    and must never contain secrets.
- **`app/`**: Compose, single activity.
  - `MainActivity.kt`: `FLAG_SECURE`, NFC **reader mode** while in the
    foreground (no other app sees the tag), `vm.wipeAll()` in `onStop`.
  - `SeedTagViewModel.kt`: all UI state and flows. Argon2 runs on
    `Dispatchers.Default`. The pending tag operation is an `AtomicReference`
    because the reader callback runs on a binder thread. A failed tag operation
    stays armed so the user can just tap again.
  - `TagIo.kt`: blocking NFC I/O (write + read-back, read, lock), returns a
    `TagResult`, never throws.
  - `Ui.kt`: screens, word keypad, password keyboard, dialogs, theme.
- **`tools/seedtag.py`**: single file, wordlist embedded, `encrypt` /
  `decrypt` / `verify`. Needs `cryptography` ≥ 44, or `argon2-cffi` as fallback.

## Blob format v1 (do not change in place)

```
blob  = 0x01 | salt[16] | nonce[12] | AES-256-GCM(entropy)[len+16]
key   = Argon2id(UTF-8(NFKD(password)), salt, t=3, m=65536 KiB, p=1, v1.3, len=32)
AAD   = 0x01
tag   = one NDEF Text record, lang "en", standard Base64 of blob
```

Existing tags in the field depend on this. Any change to parameters or layout
is a **new version byte**, with `open` still accepting v1, matching changes in
`seedtag.py`, and new rows in the vector files. Generate vectors with
`seedtag.py` (fixed salt/nonce) and check them in both test suites.

## Security rules

- **No network.** The manifest removes `INTERNET` / `ACCESS_NETWORK_STATE`
  with `tools:node="remove"`, and `check-apk.sh` enforces it on every build.
  Don't add permissions, analytics, crash reporting or anything that phones home.
- Never log, toast, persist or put on the clipboard any seed word, password,
  entropy, key or decrypted output. No `Log.*` with user data at all.
- Keep `allowBackup="false"` and the data-extraction rules; store nothing on disk.
- Secrets live in `ByteArray` / `CharArray` and are zeroed in `finally`.
  Compose text fields and the restored word list are `String`s; keep those
  short-lived and cleared by `wipeAll()`.
- All input uses the app's own keyboards: the word keypad for seed words, the
  full keyboard for passwords. No text fields (`TextField`, `EditText`), so the
  system keyboard can never open; the window also sets `FLAG_ALT_FOCUSABLE_IM`.
- Use throwaway mnemonics in tests and examples (e.g. Trezor vectors), never
  anything that looks like a real seed.

## Conventions

- Keep `core` free of Android imports so `make test` stays a plain JVM test.
  Add a test when BIP39 or crypto behaviour changes, in Kotlin and Python.
- No new dependencies without asking. Bouncy Castle is the only crypto library.
- Four-space indent for Kotlin, XML, shell and Python (`.editorconfig`).
- For any user-visible change: bump `versionCode` and `versionName` in
  `app/build.gradle.kts`, add an entry to `changelog.md` (Keep a Changelog) and
  update `README.md` if behaviour or setup changed.
- Versions are semver: `versionName` is `MAJOR.MINOR.PATCH`, the changelog
  heading matches, and a release is an annotated tag `vX.Y.Z`.
- Branching: small fixes and changes go on the current branch; new features
  and bigger or more significant changes get a separate branch.
- Never commit `*.p12` / `*.jks` / `*.keystore`, `.env` or an APK (`.gitignore`
  covers them).
