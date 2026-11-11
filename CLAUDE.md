# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android app **SeedTag** (`rs.zivke.seedtag`). It stores a password-encrypted
BIP39 seed phrase on an NFC tag and reads it back:

- **Back up**: the tag is scanned first (size, read-only, blank, existing
  data; nothing written), then the words are entered with the app's own keypad, checked
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

JDK, Android SDK, Gradle cache and adb are already set up in the devcontainer.
The release signing key is deliberately **not** in it: release APKs are built
unsigned and the user signs them on another machine with `tools/sign-release.sh`.
Never install, regenerate or read a signing key, and don't bring one into the
container. Everything goes through the `Makefile` (`make help` lists targets).

- `make test`: `:core:test` (JUnit, plain JVM) and `tools/test_seedtag.py`
  against the same vector files. No device, no Android classes.
- `make debug`: `seedtag-debug.apk`, Android debug key.
- `make build`: unsigned release `seedtag-unsigned.apk` (R8 on), always a clean
  build, as in CI.
  `make build-test`: the same, signed with the debug key
  (`seedtag-release-test.apk`), to try the R8 output on the phone.
- Every build finishes with `tools/check-apk.sh`, which verifies the signature
  (skipped with `--unsigned`), prints the APK's SHA-256 and **fails if the APK
  requests any permission except NFC, `HIDE_OVERLAY_WINDOWS` and `VIBRATE`**
  (overlay hiding on Android 12+, and the NFC-spot finder's buzz; neither
  gives access to anything).
- `tools/sign-release.sh (--expect <sha256> | --no-expect) [--no-verify-tag] <keystore>`
  runs outside the container, in a fresh clone at the release tag: refuses an
  unsigned APK that doesn't match the independent build (`--expect`, the
  SHA-256 from the GitHub Actions run of the tag), a clone with uncommitted
  changes, a HEAD that isn't a `v*` tag accepted by `git verify-tag`, and an
  APK whose recorded commit isn't HEAD. It signs `seedtag-unsigned.apk` into
  `seedtag.apk`, refuses any certificate but the one in
  `tools/release-cert.sha256`, and writes `seedtag.apk.sha256`.
  The release build is reproducible; keep it that way (no timestamps, paths or
  other machine-specific data in the APK). `make install` / `make run` install that
  signed `seedtag.apk`; they never build or sign.
- `make connect DEVICE_IP=… [ADB_PORT=…]`, `make run-test` / `make run-debug`,
  `make logcat`, `make stop`, `make uninstall`, `make clean`, `make check` (+ lint, +
  `make check-workflows`: the workflows parse, declare `permissions` and pin
  every action by commit SHA; there's no way to run them in the container).

There is no emulator and no instrumented tests: NFC needs a real phone and a
real tag. Anything beyond `make test` and a successful build has to be checked
on the device by the user. Say so rather than claiming a change works.

## Architecture

Two Gradle modules plus the Python tool:

- **`core/`**: pure Kotlin/JVM, no Android imports, Bouncy Castle only.
  - `Bip39.kt`: wordlist (resource `bip39-english.txt`, the official list,
    SHA-256 `2f5eed53…dbda`), words ↔ entropy, checksum, `suggest(prefix)`.
  - `SeedBox.kt`: the blob format (below), `seal` / `open`, Base64, `fingerprint`.
    `openForgiving` retries a wrong password with spaces collapsed, then
    lower-cased (`passwordVariants`); `seedtag.py` has the same rules, checked
    against `password-variants.tsv`. Only the typed password changes, never
    the format.
  - `Passphrase.kt`: 7-word generator from the BIP39 list, rough strength estimate.
  - `SecretChars.kt`: zeroable, in-place password buffer.
  - `NdefText.kt`: Text-record payload decode (encode is `NdefRecord.createTextRecord`).
  - `Type2Tlv.kt`: NDEF TLV length in a Type 2 tag's raw data area, to double-check
    a tag every NDEF read called empty before writing to it without asking.
  - `Errors.kt`: `SeedTagException` subclasses; messages are shown to the user
    and must never contain secrets.
- **`app/`**: Compose, single activity.
  - `MainActivity.kt`: `FLAG_SECURE`, NFC **reader mode** while in the
    foreground (no other app sees the tag), `vm.wipeAll()` in `onStop`.
    Overlay defense: `setHideOverlayWindows` (Android 12+) and
    `dispatchTouchEvent` drops touches flagged `FLAG_WINDOW_IS_OBSCURED`
    (all versions, with a banner). Accessibility: Android 14+ marks the window
    `setAccessibilityDataSensitive(YES)` (only accessibility tools see it), and
    every version shows a banner naming non-system apps with accessibility
    access. Dialogs are separate windows and use `GuardDialogWindow()` in `Ui.kt`
    for all of this. Airplane mode is read from `Settings.Global` (no
    permission) for the offline advice; Wi-Fi / Bluetooth state would need
    permissions, so they're only mentioned.
  - `SeedTagViewModel.kt`: all UI state and flows. Argon2 runs on
    `Dispatchers.Default`. The pending tag operation is an `AtomicReference`
    because the reader callback runs on a binder thread. A failed tag operation
    stays armed so the user can just tap again.
  - `TagIo.kt`: blocking NFC I/O (check, write + read-back, read, lock), returns a
    `TagResult`, never throws.
  - `Ui.kt`: screens, word keypad, password keyboard, dialogs, theme.
- **`tools/seedtag.py`**: single file, wordlist embedded, `encrypt` /
  `decrypt` / `verify`. Python 3.8+; uses `cryptography` (AES-GCM, and
  Argon2id from ≥ 44), with `argon2-cffi` as the Argon2id fallback. Without
  them, decrypt falls back to its built-in plain-Python Argon2id and AES-GCM
  decryption (slow, standard library only); encrypt needs `cryptography`.

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
  The only permissions are NFC, `HIDE_OVERLAY_WINDOWS` and `VIBRATE`. Don't add permissions, analytics, crash reporting or anything that phones home.
- Never log, toast, persist or put on the clipboard any seed word, password,
  entropy, key or decrypted output. No `Log.*` with user data at all.
- Keep `allowBackup="false"` and the data-extraction rules; store nothing on disk.
- Secrets live in `ByteArray` / `CharArray` and are zeroed in `finally`.
  Passwords live in `SecretChars` buffers in the view model, edited in place
  by the keyboard: never turn one into a `String` (`reveal()` is only for
  showing it on screen). Seed words, typed or restored, are the wordlist's own
  `String` objects, not copies; the letters of the word being typed are a
  `SecretChars` buffer too (four letters identify any BIP39 word). `wipeAll()`
  clears all of it.
- The AES key stays in the platform cipher (hardware-accelerated,
  constant-time) even though `SecretKeySpec` copies it; don't swap in a Java
  AES just to be able to zero the key.
- All input uses the app's own keyboards: the word keypad for seed words, the
  full keyboard for passwords. No text fields (`TextField`, `EditText`), so the
  system keyboard can never open; the window also sets `FLAG_ALT_FOCUSABLE_IM`.
- Use throwaway mnemonics in tests and examples (e.g. Trezor vectors), never
  anything that looks like a real seed.

## Conventions

- Keep `core` free of Android imports so `make test` stays a plain JVM test.
  Add a test when BIP39 or crypto behavior changes, in Kotlin and Python.
- Licenses: the app and everything else are `GPL-3.0-or-later` (`LICENSE`); `tools/seedtag.py`
  alone is `0BSD`, so recovery is never in legal doubt: keep it that way. Every source file starts
  with an `SPDX-License-Identifier` line and `Copyright (C) 2026 zivke`; add them to new files.
- No new dependencies without asking. Bouncy Castle is the only crypto library.
- Every download is pinned: the wrapper jar (`gradle-wrapper.jar.sha256`, checked by `make`), the
  Gradle distribution (`distributionSha256Sum`), every dependency (`gradle/verification-metadata.xml`),
  and in the Dockerfile the base image digest and the command-line tools SHA-256. After changing a
  dependency, run `make deps-metadata` (records, from an empty Gradle home, and cross-checks the checksums;
  Gradle drops the XML's comment, so put it back) and commit the XML.
  Never hand-edit checksums or turn verification off to get a build through.
- Four-space indent for Kotlin, XML, shell and Python (`.editorconfig`).
- For any user-visible change: add an entry under `## [Unreleased]` in
  `changelog.md` (Keep a Changelog) and update `README.md` if behavior or
  setup changed. Don't touch `versionCode` / `versionName` and don't invent a
  version heading: unreleased work is never filed under a version number.
- Versions are semver (`MAJOR.MINOR.PATCH`), chosen only when releasing, from
  what's under `[Unreleased]`: incompatible changes (e.g. older versions can't
  read new tags, a feature removed) bump MAJOR (MINOR while still 0.x); any
  `Added` entry bumps MINOR; only fixes bump PATCH.
- Releasing (only when asked): rename `[Unreleased]` to `[X.Y.Z] - YYYY-MM-DD`,
  bump `versionCode` by 1 and set `versionName` to `X.Y.Z` in
  `app/build.gradle.kts`, commit, and run `make build`. The user creates the
  signed tag `vX.Y.Z` (`git tag -s`, with a key that isn't in the container),
  pushes it and signs `seedtag-unsigned.apk`, all outside the container. Never
  create or push a release tag from the container.
- Branching: small fixes and changes go on the current branch; new features
  and bigger or more significant changes get a separate branch.
- Never commit `*.p12` / `*.jks` / `*.keystore`, `.env` or an APK (`.gitignore`
  covers them).
