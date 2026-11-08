# SeedTag

Offline Android app that stores a **password-encrypted BIP39 seed phrase on an NFC tag**,
plus a single-file Python tool to recover it on any computer.

- Encrypts the raw seed entropy (not the words) → 77 bytes for 24 words, fits a 137-byte MIFARE Ultralight C.
- Argon2id (64 MiB) + AES-256-GCM. Documented, versioned format.
- **No `INTERNET` permission.** Screenshots blocked, no backups, no clipboard.
- **Other apps' overlays are hidden** (Android 12+), and taps that come through another app's window are ignored.
- **No system keyboard.** Seed words and passwords are typed on the app's own keyboards, so no keyboard app ever sees them.
- The encrypted backup is decrypted once before it's written, and every write is read back and compared before it's
  reported as success.

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
requests any permission other than NFC and `HIDE_OVERLAY_WINDOWS`**, so a library can't quietly add network access.
(`HIDE_OVERLAY_WINDOWS` is granted at install without a prompt and gives no access to anything: it only lets
SeedTag hide other apps' overlays.)

## Using it

**Read every instruction on screen carefully before you tap.** Some steps, such as overwriting or locking a
tag, can't be undone.

**Have the NFC tag ready before you start.** When the app asks for it, hold the tag flat against the back of the
phone, over its NFC spot (usually near the middle or the top), and keep it still until the app says it's done.
A thick or metal case can block it. The phone vibrates when the tag has been handled: a short confirmation when it
worked, a "rejected" buzz when something went wrong (it follows the phone's touch-feedback setting).

1. **Back up**: choose how many words your phrase has (12, 15, 18, 21 or 24), enter them with the in-app keypad
   (tap a suggestion to accept each word), then set a password.
   Words already entered are hidden by default; turn off **Hide entered words** to check them.
   **Generate 7-word passphrase** is recommended (≈77 bits). Write it down and label it *SeedTag password*: it's made of
   the same BIP39 words as a seed phrase, so an unlabelled copy is easy to mistake for one, by you or by your heirs. Tap
   *Encrypt & write*, then hold the tag to the phone.
   The password is typed on the app's own keyboard, which suggests BIP39 words as you type.
   A password estimated below 60 bits, or one that uses words of the seed, needs a confirmation before writing.
   If the tag already has data, the app asks before overwriting it. A blank, unformatted tag is formatted
   first and then needs a second tap so the app can read it back.
2. **Verify**: type the password from your written copy (not from memory, so the copy is checked too) and scan.
   The app reads the tag, decrypts it and checks the seed's checksum. You get ✓ plus a fingerprint, and the words
   stay hidden. Right after a backup, it also says whether the tag holds the same seed you just wrote; later,
   compare the fingerprint with the one shown after the backup. Verify again every now and then (say once a year)
   to make sure the tag still reads.
   If the password is wrong, the app tries it again without extra spaces, then in lower case, and tells you if that
   worked, so you can correct your written copy.
3. Optionally recover once with `seedtag.py` on an offline computer.
4. Only then use **Make tag read-only** on the Verify tab. It's permanent, and it only works on the tag you last wrote or verified.
5. **Restore**: same as Verify, but the words are shown.

Verify and Restore are two steps, like Back up: type the password and scan, then the result. Once the tag has
been decrypted, the password field and keyboard are gone; **Verify another tag** or **Restore another tag** starts
again from an empty password.

Leaving the app (home button, Recents, screen off) wipes everything typed or decrypted.

**About** in the top bar shows the version, what the app does with your data (nothing leaves the phone), the
encryption used and the licence.

## Choosing a tag

Any tag Android can read and write as NDEF works. The app uses the generic NDEF API and listens for NFC-A, -B, -F
and -V, and every write checks the size first. Tested hardware: MIFARE Ultralight C.

**Space needed** (whole NDEF area, including the 3-byte TLV wrapper):

| Words | Base64 on tag | NDEF bytes |
|---|---|---|
| 12 | 84 chars | ~94 |
| 18 | 92 chars | ~102 |
| 24 | 104 chars | ~114 |

**Data retention matters most.** A seed backup has to last. Cheap Type 2 chips (NTAG21x, Ultralight C/EV1) are
rated for 10 years (at 22 °C), others for 25–60. Check the datasheet of the exact chip you buy: older revisions
can differ (MIFARE Ultralight C was rated for 5 years before the current datasheet). Treat a 10-year tag as one
copy among several, and verify it every now and then.

**Tag families** (manufacturer figures, checked November 2026; check the datasheet of your chip):

| Tag | Type / Android tech | User memory | 24 words? | Retention | Built-in write protection |
|---|---|---|---|---|---|
| MIFARE Ultralight C *(tested)* ¹ | Type 2, NfcA | 144 B | ✓ | 10 y | 3DES key, mutual auth; can restrict writes only (strong) |
| MIFARE Ultralight AES | Type 2, NfcA | 144 B | ✓ | not on NXP's product page | AES-128 mutual auth (strong) |
| MIFARE Ultralight EV1 (MF0UL21) | Type 2, NfcA | 128 B | ✓ (tight) | 10 y | 32-bit password (weak) |
| NTAG212 | Type 2, NfcA | 128 B | ✓ (tight) | 10 y | 32-bit password (weak) |
| NTAG213 / 215 / 216 | Type 2, NfcA | 144 / 504 / 888 B | ✓ | 10 y | 32-bit password, sent in clear (weak) |
| NTAG 424 DNA | Type 4, IsoDep | 416 B (256 B NDEF file) | ✓ | 50 y | AES-128 keys, per-file access rights (strong) |
| MIFARE DESFire EV3 ² | Type 4, IsoDep | 2–16 KB | ✓ | 25 y | AES keys, per-file access rights (strong) |
| ICODE SLIX2 | Type 5, NfcV | 316 B | ✓ | 50 y | 32-bit read/write passwords (weak) |
| ICODE SLIX | Type 5, NfcV | 112 B | ✗ (≈18 words max) | 50 y | none, only permanent block locks |
| ST25TV (02K … 64K) ³ | Type 5, NfcV | 64 B – 8 KB | depends on model | 40–60 y | 32- or 64-bit area passwords (medium) |
| FeliCa Lite-S | Type 3, NfcF | 224 B | ✓ | not published | per-block write-with-MAC using a card key |

¹ NXP marks Ultralight C *not recommended for new designs*; its replacement is Ultralight AES. It's still sold.
² DESFire EV2 (also 25 y) is *not recommended for new designs*; EV3 replaces it.
³ Retention and password scheme differ by model: ST25TV02K is rated for 60 y with 32- or 64-bit passwords,
ST25TV16K/64K for 40 y with 64-bit passwords (ST25TV16K is *not recommended for new designs*).

SeedTag doesn't use any of these write protections yet: the payload is encrypted either way, and **Make tag
read-only** is the only protection against overwriting. Only Ultralight C has been tested with SeedTag: try
any other tag with your phone (write, verify, restore) before relying on it. A tag mounted on metal needs an
on-metal version.

**Avoid:**

- Original MIFARE Ultralight, NTAG210 and Ultralight EV1 MF0UL11: 48 bytes, too small even for 12 words.
- MIFARE Classic: not an NFC Forum type. Android support for it is optional, so many phones can't read it at
  all, and its Crypto1 security is broken.
- Type 1 (Topaz): rarely sold any more. The original Topaz has only 96 bytes; Topaz 512 is big enough but
  untested with SeedTag.

**Buying one.** Online marketplaces (Temu, AliExpress, Amazon, …) sell these cheaply. Search for the exact
chip, plus the form factor you want:

```
NTAG216 NFC card PVC
NTAG215 NFC sticker
NTAG216 anti-metal NFC tag
MIFARE Ultralight C NFC card      (also listed as MF0ICU2)
NTAG424 DNA NFC tag
ICODE SLIX2 NFC label
NTAG216 NFC ring
```

- Only buy a listing that names the chip, e.g. "Chip: NTAG216". "13.56 MHz IC card", "NFC compatible" or
  "S50" without a chip name is usually MIFARE Classic 1K.
- Skip "UID changeable", "Gen1a", "CUID" or "magic card" (Classic clones), and "NTAG21x compatible" (unknown
  clones whose memory and retention aren't documented).
- For durability prefer PVC cards, epoxy tags or coin tags over paper stickers.
- A cheap listing can carry a fake chip. NXP's free **NFC TagInfo** app shows the real chip and whether it's
  genuine NXP silicon. Buy a few, test one with a throwaway phrase first, and prefer sellers who name the chip
  maker.

**Idea: an NFC ring.** An epoxy or ceramic ring with an NTAG215/216 inside (search `NTAG216 NFC ring`) keeps
the encrypted backup on you, and it looks like jewellery, not a seed backup. Things to know:

- The antenna is small, so the ring has to sit right on the phone's NFC spot, and it reads at a shorter range.
  Check that your phone reads it reliably before trusting it.
- Look for rings that say 13.56 MHz / NTAG. Many "dual-chip" rings also carry a 125 kHz chip (T5577, EM4305)
  that the phone can't see, and some only have that one. Fitness "smart rings" aren't NFC tags at all.
- Worn every day, it gets knocked about and is easier to lose. Treat it as an extra copy, never the only one.
  Losing it doesn't expose the seed as long as the password is strong.

## Format v1

```
blob  = 0x01 | salt[16] | nonce[12] | AES-256-GCM(entropy)[len+16]
key   = Argon2id(UTF-8(NFKD(password)), salt, t=3, m=65536 KiB, p=1, v1.3, len=32)
AAD   = 0x01
entropy = raw BIP39 entropy, 16/20/24/28/32 bytes (12–24 words; checksum recomputed on decode)
tag   = one NDEF Text record, lang "en", standard Base64 of blob (104 chars for 24 words)
```

The *fingerprint* is the first 4 bytes of `SHA-256("seedtag-fp" ‖ entropy)`. It isn't secret, and it lets you confirm two
backups or a recovery match without showing the words. The same seed always gives the same fingerprint, whatever the tag
or password.

It can't be turned back into the seed, and it doesn't help anyone guess the tag's password. It only helps someone who
already knows *most* of the seed: with two words of a 24-word phrase missing, the BIP39 checksum leaves about 16,000
candidates, and the fingerprint picks out the right one in under a second. For a wallet that has been used, its public
addresses already let them do that, more slowly. So writing the fingerprint on the tag's label is fine, but don't keep it
next to a partly readable copy of the seed.

## Recovering on a computer

`tools/seedtag.py` is self-contained (it embeds the official wordlist, SHA-256 `2f5eed53…dbda`). It only needs `cryptography` ≥ 44:

```bash
pip install cryptography          # do this before going offline
python3 seedtag.py verify  <BASE64>   # OK + fingerprint, no words
python3 seedtag.py decrypt <BASE64>   # prints the words
python3 seedtag.py encrypt            # create a tag string on the computer instead
```

Like the app, `decrypt` and `verify` retry a wrong password without extra spaces, then in lower case, and say so if
that's what matched.

To get the Base64 string off the tag without this app, read it with any NFC reader app (e.g. NFC Tools).
It's ciphertext, so that's safe.

## Warnings: other apps on the phone

SeedTag can only protect you from what happens inside its own window. Other apps on the same phone can still get at
your seed and password:

- **Fake-screen overlays.** An app allowed to *Display over other apps* can draw a copy of SeedTag's keypad on top
  of it and record what you type. On **Android 12 and later**, SeedTag hides every such overlay while it's on
  screen. On **Android 8–11 it can't.** There, open *Settings › Apps › Special app access › Display over other
  apps* (the path varies by phone) and turn it off for every app you don't fully trust, or use a phone with
  Android 12+.
- **Tapjacking.** An overlay can let your taps through while hiding what's underneath, to make you confirm
  *Overwrite*, *Write anyway* or *Lock forever* without seeing it. SeedTag ignores every tap that comes through
  another app's window, on every Android version, and shows a red banner saying so. Screen dimmers, blue-light
  filters and chat bubbles trigger it too: turn them off while using SeedTag. If you can't tell which app it is,
  don't enter your seed.
- **Accessibility services.** A service with accessibility access can read everything on screen, including the
  restored words and every key you tap. Neither overlay hiding nor screenshot blocking stops it. Open
  *Settings › Accessibility* and remove any service you didn't knowingly install.
- **Fake SeedTag apps.** Nothing inside SeedTag can protect you from a different app that looks like it. Install
  only an APK you built yourself or got from a source you trust, and check its signing certificate with
  `apksigner verify --print-certs seedtag.apk`.

The safest setup is a spare phone that was factory-reset, has nothing else installed and stays offline.

## Security notes

- **The password is the whole security.** A tag can be read by anyone who holds it, and the attack is offline password guessing.
  Argon2id at 64 MiB slows that down, but a weak password still falls. Use the generated passphrase.
- **A lost password can't be recovered.** Without it nothing can decrypt the tag: not the app, not `seedtag.py`,
  not the developer. There's no reset or recovery of any kind. Keep the written password safe, and keep a copy of
  the seed itself somewhere else.
- **This is not your only backup.** NFC chips can fail. Keep a metal or paper copy of the seed somewhere separate.
- The phone is still a general-purpose, normally online device. For maximum safety use it in airplane mode, or on a spare
  phone that is never online.
- **Password characters.** The app's keyboard has letters, digits, all ASCII symbols and common Latin accented letters
  (č, ć, đ, š, ž, é, ö, ß …). A password with any other character, such as Cyrillic or emoji, set with an earlier version
  or with `seedtag.py`, can't be typed in the app: decrypt that tag with `seedtag.py`.
- **What's wiped from memory.** Passwords are kept in one char array that's edited in place and zeroed after use, never
  as text. Entropy, password bytes and the derived key are zeroed too. Seed words, typed or restored, point at the app's
  built-in word list instead of being copied. On the JVM some things can't be wiped:
  - the password as text while **Show** is on, the generated passphrase while it's on screen, and a password with
    non-ASCII characters for a moment while it's normalised;
  - the few letters of the word being typed on the keypad;
  - copies of the AES key inside Android's crypto code. SeedTag keeps Android's AES on purpose: it's
    hardware-accelerated and resistant to timing attacks, unlike a pure-Java AES whose key could be wiped;
  - text Compose has already drawn on screen.

  Everything is dropped when the app is backgrounded. What's left in memory until it's overwritten can only be read
  with root access or a debugger.
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
