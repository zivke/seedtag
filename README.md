# SeedTag

Offline Android app that stores a **password-encrypted BIP39 seed phrase on an NFC tag**,
plus a single-file Python tool to recover it on any computer.

- Encrypts the raw seed entropy (not the words) → 77 bytes for 24 words, fits a 137-byte MIFARE Ultralight C.
- Argon2id (64 MiB) + AES-256-GCM. Documented, versioned format.
- **No `INTERNET` permission.** Screenshots blocked, no backups, no clipboard, own keypad for seed words.
- Every write is read back and compared before it's reported as success.

## Project layout

```
core/          pure Kotlin/JVM: BIP39, SeedBox (crypto), tests
app/           Android app (Jetpack Compose), NFC reader mode
tools/         seedtag.py (desktop encrypt / decrypt / verify), its tests, check-apk.sh
.devcontainer/ JDK, Android SDK, adb and Claude Code in a container
```

## Build & install

The easiest route is the devcontainer: open the folder in VS Code and *Reopen in Container*.
See [.devcontainer/README.md](.devcontainer/README.md) for the signing key and connecting the phone.
Outside it you need JDK 17+, the Android SDK (`ANDROID_HOME`), Python 3 with `cryptography`, and a phone
with NFC running Android 8+.

```bash
make test                         # crypto core (JUnit) + seedtag.py, same test vectors
make debug                        # seedtag-debug.apk, Android debug key
make build                        # seedtag.apk, release, signed with the key in .env
make connect DEVICE_IP=<phone ip> # adb over Wi-Fi
make run-debug                    # install the debug APK and open it
make help                         # all targets
```

Release signing reads `.env` (gitignored):

```sh
KEYSTORE=/home/vscode/.android-keys/<your-key>.jks
KEY_PASS=<password>
# KEY_ALIAS=<alias>   only if the keystore holds more than one key
```

Both builds end with `tools/check-apk.sh`. It verifies the signature and **fails the build if the APK
requests any permission other than NFC**, so a library can't quietly add network access.

## Using it

1. **Back up**: enter the words with the in-app keypad (tap a suggestion to accept each word), then set a password.
   Words already entered are hidden by default; turn off **Hide entered words** to check them.
   **Generate 7-word passphrase** is recommended (≈77 bits). Write it down. Tap *Encrypt & write*, then hold the tag to the phone.
   If the tag already has data, the app asks before overwriting it. A blank, unformatted tag is formatted
   first and then needs a second tap so the app can read it back.
2. **Verify**: type the password from your written copy and scan. You get ✓ plus a fingerprint, and the words stay hidden.
3. Optionally recover once with `seedtag.py` on an offline computer.
4. Only then use **Make tag read-only**. It's permanent, and it only works on the tag you last wrote or verified.
5. **Restore**: same as Verify, but the words are shown.

Leaving the app (home button, Recents, screen off) wipes everything typed or decrypted.

## Format v1

```
blob  = 0x01 | salt[16] | nonce[12] | AES-256-GCM(entropy)[len+16]
key   = Argon2id(UTF-8(NFKD(password)), salt, t=3, m=65536 KiB, p=1, v1.3, len=32)
AAD   = 0x01
entropy = raw BIP39 entropy, 16/20/24/28/32 bytes (12–24 words; checksum recomputed on decode)
tag   = one NDEF Text record, lang "en", standard Base64 of blob (104 chars for 24 words)
```

The *fingerprint* is the first 4 bytes of `SHA-256("seedtag-fp" ‖ entropy)`. It isn't secret, and it lets you confirm two
backups or a recovery match without showing the words.

## Recovering on a computer

`tools/seedtag.py` is self-contained (it embeds the official wordlist, SHA-256 `2f5eed53…dbda`). It only needs `cryptography` ≥ 44:

```bash
pip install cryptography          # do this before going offline
python3 seedtag.py verify  <BASE64>   # OK + fingerprint, no words
python3 seedtag.py decrypt <BASE64>   # prints the words
python3 seedtag.py encrypt            # create a tag string on the computer instead
```

To get the Base64 string off the tag without this app, read it with any NFC reader app (e.g. NFC Tools).
It's ciphertext, so that's safe.

## Security notes

- **The password is the whole security.** A tag can be read by anyone who holds it, and the attack is offline password guessing.
  Argon2id at 64 MiB slows that down, but a weak password still falls. Use the generated passphrase.
- **This is not your only backup.** NFC chips can fail. Keep a metal or paper copy of the seed somewhere separate.
- The phone is still a general-purpose, normally online device. For maximum safety use it in airplane mode, or on a spare
  phone that is never online.
- JVM/Compose limitations: text fields and the decrypted word list exist briefly as immutable `String`s, which can't be zeroed.
  Byte and char arrays (entropy, keys, password bytes) are zeroed after use. State is dropped when the app is backgrounded.
- The Ultralight C's own 3DES access protection isn't used, and isn't needed: the payload is encrypted.

## Tests

- `Bip39Test`: all 24 official Trezor English vectors, random round-trips, checksum/unknown-word errors, prefix uniqueness.
- `SeedBoxTest`: vectors generated by `seedtag.py` with fixed salt/nonce. Kotlin (Bouncy Castle Argon2) and Python
  (OpenSSL Argon2) must produce identical bytes. Also covers wrong password, tampering, malformed input, Unicode
  normalisation, and the 137-byte size budget.
- `tools/test_seedtag.py`: the same vector files run through `seedtag.py`, plus a check that its embedded wordlist
  matches the app's.

NFC itself has no automated tests: there's no emulator support. Test on a real phone with a real tag, using a
throwaway mnemonic.

## License

- The app and everything else in this repository: [GNU GPL v3 or later](LICENSE) (`GPL-3.0-or-later`). You may use,
  share and change it, but anything you distribute that's based on it must be released under the same licence, with
  its source.
- `tools/seedtag.py`: [BSD Zero Clause](tools/seedtag.py) (`0BSD`, the full text is in the file's header), so
  anyone can always use it to recover a tag, for any purpose.

Each source file names its licence in an `SPDX-License-Identifier` line. See [changelog.md](changelog.md) for changes.
