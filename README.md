# SeedTag

Free, open-source, offline Android app that stores a **password-encrypted BIP39 seed phrase on an NFC tag**,
plus a single-file Python tool to recover it on any computer. Source: <https://github.com/zivke/seedtag>.

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
art/           app icon source (SVG) and the 512 px store icon
.devcontainer/ JDK, Android SDK, adb and Claude Code in a container
```

## Build & install

The easiest route is the devcontainer: open the folder in VS Code and *Reopen in Container*.
See [.devcontainer/README.md](.devcontainer/README.md) for connecting the phone.
Outside it you need JDK 17+, the Android SDK (`ANDROID_HOME`), Python 3 with `cryptography`, and a phone
with NFC running Android 8+.

```bash
make test                         # crypto core (JUnit) + seedtag.py, same test vectors
make debug                        # seedtag-debug.apk, Android debug key
make build                        # seedtag-unsigned.apk: release (R8), not signed
make build-test                   # the release build signed with the debug key, for testing
make connect DEVICE_IP=<phone ip> # adb over Wi-Fi
make run-debug                    # install the debug APK and open it
make help                         # all targets
```

Develop and test on a phone you'll never enter a real seed on: a phone with adb access can have its apps replaced
without notice (see [Using it safely](#using-it-safely)).

Every build ends with `tools/check-apk.sh`. It verifies the signature, prints the APK's SHA-256, and **fails the
build if the APK requests any permission other than NFC, `HIDE_OVERLAY_WINDOWS` and `VIBRATE`**, so a library
can't quietly add network access. It also fails a release APK that is debuggable or test-only, allows backups, or
declares any component besides SeedTag's activity and the two that AndroidX adds (the startup initializer, not
exported, and the profile installer's receiver, which only adb can call). A new one has to be looked at and added
to the script. `--debug` (for `make debug`) allows what debug builds have: debuggable, and Compose's preview activity.
(`HIDE_OVERLAY_WINDOWS` and `VIBRATE` are granted at install without a prompt and give no access to anything: one
only lets SeedTag hide other apps' overlays, the other only runs the vibration motor.)

### Signing a release

The release key is kept out of the dev container, so nothing that runs there (build scripts, dependencies, an AI
agent) can read it and sign a fake update. Sign on the machine that holds the key, ideally an offline one with
only the Android build-tools (`apksigner`, `aapt2`) installed:

```sh
make build                       # in the container: seedtag-unsigned.apk, prints its SHA-256
git tag -s vX.Y.Z && git push origin vX.Y.Z    # on the host, not in the container: a signed tag
# the tag's GitHub Actions run shows the SHA-256 of its own build (see Build integrity)

# on the signing machine, in a fresh clone (not the container's working tree):
git clone https://github.com/zivke/seedtag && cd seedtag && git checkout vX.Y.Z
git diff vPREVIOUS..vX.Y.Z       # review what changed: the source, tools/ and .github/
cp /path/to/seedtag-unsigned.apk .             # from the container, or the CI run's artifact
tools/sign-release.sh --expect <ci-sha256> /path/to/your-key.jks   # writes seedtag.apk
```

Then install `seedtag.apk` with `make run` back in the container (copy it there), or `adb install` from the
signing machine.

`sign-release.sh` checks everything before the key is used, and refuses to sign if:
- the unsigned APK doesn't match the independent build given with `--expect`. That's required: `--no-expect`
  skips the comparison, with a warning;
- the clone has uncommitted changes, so an edited script in a shared working tree can't run next to your key;
- `HEAD` isn't exactly a `vX.Y.Z` tag, or `git verify-tag` doesn't accept its signature (`--no-verify-tag` skips
  that check, with a warning);
- the APK says it was built from a different commit (`META-INF/version-control-info.textproto`);
- the APK asks for any other permission.

Then it signs, and deletes the result unless it carries the release certificate:

```
SHA-256 29370bf9dc6a272f18b3fbac01837c262bcec4035de162dad4fc5175291ae82c   (tools/release-cert.sha256)
```

That's also the digest to compare when you install an APK from elsewhere
(`apksigner verify --print-certs seedtag.apk`).

What each step protects against:

- **Signing outside protects the key, not the build.** If the container were compromised, the unsigned APK could
  already be tampered with, and the permission check wouldn't notice a weakened encryption. `--expect` catches
  that: sign only an APK that's byte-for-byte the same as a build made somewhere else.
- **`--expect` proves the build, not the source.** CI builds whatever commit the tag points at. The container can
  write to the repository, and with VS Code's forwarded git credentials it can push commits and tags too. So: take
  push access away from the container (see `.devcontainer/README.md`, "No push access from the container"),
  create release tags only on the host with `git tag -s`, and **review `git diff vPREVIOUS..vX.Y.Z` on the signing
  machine** before signing. That review is the only defence for the source itself.
- **A fresh clone** on the signing machine means the script that runs next to your key is the one in the
  reviewed tag, not one the container could have edited in its working tree.

Keep a backup of the key: without it no update can ever be installed over the app.

When you publish a release, publish `seedtag.apk.sha256` (written by `sign-release.sh`) and the certificate digest
above with it, so others can check what they downloaded.

### Build integrity

Everything the build downloads is checked against a pinned checksum, so a tampered download fails the build:

- **Gradle:** `make` checks the wrapper jar against `gradle/wrapper/gradle-wrapper.jar.sha256` (the official
  wrapper jar of Gradle 8.14) before every Gradle run; the wrapper downloads the Gradle version set in
  `gradle-wrapper.properties` (8.11.1) and checks it against `distributionSha256Sum`.
- **Dependencies:** Gradle checks every library and plugin file against `gradle/verification-metadata.xml`.
  After adding or updating a dependency, run `make deps-metadata`: it downloads everything again into an empty,
  temporary Gradle home (so it records every file a fresh machine like CI fetches, not just what's missing from
  the local cache), records the new checksums and then cross-checks all of them against what Maven Central, Google's Maven repository and the Gradle Plugin Portal
  publish (`tools/check-deps.py`, also `make check-deps`), so a bad download isn't simply trusted. Review the
  diff of the XML before committing it. Source and javadoc jars, which only the IDE downloads to show library
  code, aren't checked: they're never compiled into the app.
- **Dev container:** the base image is pinned by digest, and the Android command-line tools zip is checked against
  the SHA-256 Google publishes; `sdkmanager` checks the SDK packages itself.

- **Independent build:** the release build is reproducible: two clones of the same commit build byte-identical
  unsigned APKs, wherever they're checked out. The GitHub Actions workflow (`.github/workflows/build.yml`) runs
  `make test build` for every pushed `v*` tag, or when started by hand (the Actions tab's "Run workflow", or
  `gh workflow run build.yml --ref <branch-or-tag>`), in an image built from the same `.devcontainer/Dockerfile`,
  and shows the APK's SHA-256 in the run's summary (the APK is attached too). Pass that hash to
  `sign-release.sh --expect`. Its actions are pinned by commit SHA, which `make check-workflows` (part of
  `make check`) enforces. It can't tell offline whether a SHA is the release named in its comment, so check that
  when updating an action: `git ls-remote https://github.com/actions/checkout refs/tags/v7.0.1` must print the
  pinned SHA (for an annotated tag, the `^{}` line). Both current pins were checked this way on 2026-11-11. You can also run `make build` in a fresh `git clone` on another machine. Build from a
  real clone, not a `git worktree` or a source archive: the APK records the commit
  (`META-INF/version-control-info.textproto`), and without a `.git` directory that file differs. The image's
  Ubuntu packages (the JDK among them) aren't pinned, so a JDK update between two image builds could in principle
  change the output; if the hashes differ, unzip both APKs and `diff -r` them to see why before signing anything.

Not pinned: Claude Code's installer (`curl | bash` from claude.ai in `post-create.sh`) and Ubuntu's packages
(signed by Ubuntu's apt keys). Neither is part of the app, and the release key isn't in the container.

## Using it safely

SeedTag can't go online: it has no internet permission. Other apps on the phone can, and malware could send out
what you type or see in SeedTag. That matters for **Back up** (the seed is typed in), **Restore** (it's on screen)
and **Verify** too: it never shows the words, but you type the password, and the password plus the tag is the
seed. From safest down:

1. **A spare phone that's never online**, used only for this: factory-reset it and never connect it to Wi-Fi or
   mobile data. Copy the APK over by USB or SD card and check its SHA-256 first. An old phone is fine, though
   Android 12+ hides other apps' overlays and Android 14+ shuts out accessibility services (see
   [Warnings](#warnings-other-apps-on-the-phone)). Factory-reset it again before you give it away.
2. **Your everyday phone, offline**, if you don't have a spare one. Be aware that it carries a risk that the
   following reduces, but can't completely remove:
   - restart it before you start;
   - turn on airplane mode, and check that Wi-Fi and Bluetooth are off too: they can be switched back on in
     airplane mode. NFC normally stays on; if the app says NFC is off, switch NFC back on;
   - when you're done, close SeedTag and **restart the phone before it goes back online**. Some spyware doesn't
     survive a restart, so it loses whatever it caught before it could send it.

   This stops anything that sends data live, such as remote-control malware, and spyware that doesn't survive a
   restart. It doesn't stop malware that survives the restart and uploads later: only a phone that's never online
   does.
3. **Always:** a private room with no cameras, and a phone you have no reason to distrust.
4. **No debugging on that phone.** Turn off USB debugging and wireless debugging (*Settings › System › Developer
   options*, the path varies by phone), and tap *Revoke USB debugging authorizations*. Anything with adb access to
   the phone, such as a computer or dev container it was ever paired with, can silently swap SeedTag for a
   look-alike (SeedTag keeps no data, so you wouldn't notice) or turn on an accessibility service. Don't enter a
   real seed on a phone you develop or test apps with.

The Back up, Verify and Restore screens remind you of this, say whether airplane mode is on, and warn when USB or
wireless debugging is on. Android lets the app see those settings without any permission, but not Wi-Fi or Bluetooth,
so those you have to check yourself. The accessibility banner also updates while SeedTag is open, not only when you
come back to it.

## Using it

**Read every instruction on screen carefully before you actually do anything**, including the *Learn more*
details. Some steps, such as overwriting or locking a tag, can't be undone.

**Have the NFC tag ready before you start, but hold it to the phone only when the app asks.** You don't need to
hold it while you read or type: the app only reads or writes the tag while it's held there. When asked, hold it
flat against the back of the phone, over its NFC spot (usually near the middle or the top), and keep it still until
the app says it's done. If it moves away too early, just hold it there again. A thick or metal case can block it.
Small tags and rings have a small NFC symbol on them: put that side right against the spot. Not sure where the spot
is? **Find NFC spot** in the top bar opens a finder (*Where to hold the tag*): first it checks that NFC is on, then
you slide a tag or any contactless card over the back of the phone until it vibrates and says *Tag detected*, and
for an NDEF tag how long a backup it has room for. It doesn't write to the tag or read its contents. The phone vibrates when the tag has been handled: a short confirmation when it
worked, a "rejected" buzz when something went wrong (it follows the phone's touch-feedback setting).

1. **Back up**: first hold the tag you'll use to the phone. The app checks it (nothing is written): whether it
   works, whether it's read-only, blank or already holds data, and how long a backup it has room for. A 24-word
   backup needs 111 bytes of NDEF space, 12 words need 91. Then choose how many words your phrase has (12, 15, 18,
   21 or 24; lengths the tag can't hold are grayed out), enter them with the in-app keypad (tap a suggestion to
   accept each word), then set a password.
   Words already entered are hidden by default; tap **Show words** to check them. Only English BIP39 phrases are
   supported, and only the words are stored: if your wallet also uses a **BIP39 passphrase** (a "25th word", set in
   the wallet), SeedTag doesn't store it, so back it up separately. It has nothing to do with SeedTag's own password.
   **Generate a 7-word passphrase** is recommended (≈77 bits). Write it down and label it *SeedTag password*: it's made of
   the same BIP39 words as a seed phrase, so an unlabeled copy is easy to mistake for one, by you or by your heirs. Then
   tap **I've written it down**: the passphrase is hidden, and you type it into *Repeat password* from your written
   copy, so a mistake in the copy is caught before anything is written. **Generate a different one** asks before
   replacing a passphrase you may already have written down. Tap *Encrypt & write*, then hold the tag to the phone.
   The password is typed on the app's own keyboard, which suggests BIP39 words as you type.
   Only the generated passphrase is rated *Strong*. A password you type yourself always needs a confirmation
   before writing: SeedTag can't recognise a well-known phrase (`correct horse battery staple`, a quote, a name and
   a date), so its estimate is only an upper bound, shown as "≈N bits at most". The generated passphrase counts 11
   bits per word; BIP39 words you choose yourself count about 6 each, words that follow each other in the list
   almost nothing, and repeats (a word, a token or the whole pattern) count once, whatever the case or
   punctuation.
   A password that starts or ends with a space, or has two spaces in a row, is refused: a written copy rarely shows
   that, and only the exact spaces would decrypt the tag.
   If the tag already has data, the app asks before overwriting it (on NTAG and MIFARE Ultralight tags, a tag that
   reads as empty is double-checked in its raw data first, so a bad read isn't taken for an empty tag). A blank,
   unformatted tag is formatted
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
   It stops the backup from being overwritten or erased, but not, on most chips, from being locked against reading
   by someone who gets hold of the tag (see [Choosing a tag](#choosing-a-tag)).
5. **Restore**: same as Verify, but the words are shown.

Verify and Restore are two steps, like Back up: type the password and scan, then the result. Once the tag has
been decrypted, the password field and keyboard are gone; **Verify another tag** or **Restore another tag** starts
again from an empty password.

Leaving or closing the app (home button, Recents, screen off) deletes everything typed or decrypted; nothing is
ever saved on the phone. So that a screen timeout doesn't wipe a half-typed phrase, the screen stays on while
words, a password or restored words are on the phone. Instead, SeedTag has its own timeout: after 3 minutes without a
touch it asks *Still there?* (with a buzz), with the screen behind it blurred (covered before Android 12), and
clears everything a minute later unless you tap **Keep**. The power
button still turns the screen off, and that clears everything. Back on the Words and Password steps does what their
**Back** button does (going back from the password asks first, since it clears the password). Anywhere else, Back
asks before leaving while something would be lost.

**ⓘ** (About) in the top bar shows the version, what the app does with your data (nothing leaves the phone), the
encryption used and the license, with links to the source code and to donate. Links open in the browser, and only
when tapped; leaving SeedTag deletes everything typed in it.

## Choosing a tag

Any tag Android can read and write as NDEF works. The app uses the generic NDEF API and listens for NFC-A, -B, -F
and -V. Back up checks the tag's size before anything is typed, and every write checks it again. A blank (not yet
NDEF-formatted) Type 2 tag has no size on it, so the app works it out from the chip's memory for MIFARE Ultralight,
Ultralight C, EV1 and AES, NTAG203 and NTAG21x; for any other blank tag the size is only known once it's formatted. Tested hardware: MIFARE Ultralight C.

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
read-only** is the only protection against overwriting. It doesn't protect everything: Android's read-only lock
freezes the data but, on NTAG21x and on Ultralight C tags formatted without NXP's Lock Control TLV, leaves the
chip's own password settings open. Someone holding the tag for a few seconds can then set a read password of their
own, and no phone can read the backup again. Nothing leaks, but that copy is lost. So keep the tag where strangers
can't reach it (a metal box or a shielding sleeve also stops it being read from a distance), don't carry it
around, and keep other copies of the seed. Only Ultralight C has been tested with SeedTag: try
any other tag with your phone (write, verify, restore) before relying on it. **Make tag read-only** uses Android's
own lock, which phones usually only offer for Type 1 and 2 tags (NTAG, MIFARE Ultralight): on the Type 4 and 5 tags
above (NTAG 424 DNA, DESFire, ICODE, ST25TV) expect it to be refused (untested). If a tag is already read-only, the
app says so and doesn't call it locked, since it can't tell whether that's permanent. A tag mounted on metal needs
an on-metal version.

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

`tools/seedtag.py` is a single file (it embeds the official wordlist, SHA-256 `2f5eed53…dbda`) and needs Python 3.8 or
later and the `cryptography` package, for AES-GCM. Argon2id comes from `cryptography` ≥ 44; if yours is older, or
was built without Argon2id, it uses `argon2-cffi` for the key derivation instead (same bytes; `pip install
argon2-cffi`), and it says which one it used.

Without either package, `decrypt` and `verify` still work with a built-in Argon2id and AES-GCM in plain Python,
tested against the same vectors: about a minute per password tried (a password that needs the spaces or capitals
retry takes two or three), and around 90 MB of memory. `encrypt` needs `cryptography`.

**Keep a copy of `seedtag.py` with your written password**, printed or on a USB stick. With it and any Python 3.8
or later, recovering a tag needs neither this app, nor this website, nor any package. The packages' installers
(`pip download cryptography`) only make it faster.

```bash
pip install cryptography          # optional, much faster; before going offline
python3 seedtag.py verify  <BASE64>   # OK + fingerprint, no words
python3 seedtag.py decrypt <BASE64>   # prints the words
python3 seedtag.py encrypt            # create a tag string on the computer instead
```

Like the app, `decrypt` and `verify` retry a wrong password without extra spaces, then in lower case, and say so if
that's what matched. `encrypt`, like Back up, refuses a password with leading, trailing or doubled spaces.

To get the Base64 string off the tag without this app, read it with any NFC reader app (e.g. NFC Tools).
It's ciphertext, so that's safe.

## Warnings: other apps on the phone

SeedTag can only protect you from what happens inside its own window, and only from some attacks (see the
[disclaimer](#disclaimer)). Other apps on the same phone can still get at your seed and password:

- **Fake-screen overlays.** An app allowed to *Display over other apps* can draw a copy of SeedTag's keypad on top
  of it and record what you type. On **Android 12 and later**, SeedTag hides every such overlay while it's on
  screen. On **Android 8–11 it can't.** There, open *Settings › Apps › Special app access › Display over other
  apps* (the path varies by phone) and turn it off for every app you don't fully trust, or use a phone with
  Android 12+.
- **Tapjacking.** An overlay can let your taps through while hiding what's underneath, to make you confirm
  *Overwrite*, *Write anyway* or *Lock forever* without seeing it. SeedTag ignores every tap that comes through
  another app's window, on every Android version, and shows a red banner saying so. On **Android 10 and 11**, where
  overlays can't be hidden, it also ignores taps while another app's window covers any part of SeedTag's, so an
  overlay can't hide a warning while leaving its button clear. Screen dimmers, blue-light
  filters and chat bubbles trigger it too: turn them off while using SeedTag. If you can't tell which app it is,
  don't enter your seed.
- **Accessibility services.** A service with accessibility access can read everything on screen, including the
  restored words and every key you tap, and can tap for you. Neither overlay hiding nor screenshot blocking stops
  it. On **Android 14 and later**, SeedTag hides its screen from every accessibility service that isn't an
  *accessibility tool* (screen readers such as TalkBack keep working), but a malicious app installed from outside
  Google Play can claim to be one. On **Android 8–13 it can't hide anything.** On every version, SeedTag shows a red
  banner naming the installed apps that have accessibility access (preinstalled system services aren't listed).
  Open *Settings › Accessibility* and turn off any you don't recognize or don't need before entering your seed.
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
- The phone is still a general-purpose, normally online device. Use a spare phone that's never online, or at least
  airplane mode with a restart before and after: see [Using it safely](#using-it-safely).
- **Password characters.** The app's keyboard has letters, digits, all ASCII symbols and common Latin accented letters
  (č, ć, đ, š, ž, é, ö, ß …). A password with any other character, such as Cyrillic or emoji, set with an earlier version
  or with `seedtag.py`, can't be typed in the app: decrypt that tag with `seedtag.py`.
- **What's wiped from memory.** Passwords are kept in one char array that's edited in place and zeroed after use, never
  as text. Entropy, password bytes and the derived key are zeroed too. Seed words, typed or restored, point at the app's
  built-in word list instead of being copied, and the letters of the word being typed on the keypad are kept in a
  zeroed char array too (four letters are enough to identify any BIP39 word). On the JVM some things can't be wiped:
  - the password as text while **Show** is on, the generated passphrase while it's on screen, and a password with
    non-ASCII characters for a moment while it's normalised;
  - the letters of the word being typed, as text, while **Show words** is on (while the words are hidden, those
    letters are hidden too);
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
  matches the app's. The vectors are run through each Argon2id backend (`cryptography`, `argon2-cffi`) on its own;
  a backend that isn't installed is reported as skipped. The dev container only has `argon2-cffi`; CI also runs the
  tests with `cryptography` installed from the hash-pinned `tools/requirements-ci.txt`, and there a skipped
  `cryptography` backend fails the run (`SEEDTAG_REQUIRE_BACKENDS=cryptography`). The vectors cover all five
  phrase lengths (12 to 24 words). The built-in Argon2id is checked against known answers from both packages and
  the built-in AES-GCM against the GCM specification and every vector; `SEEDTAG_SLOW_TESTS=1` (set in CI) also
  decrypts a vector with no package at all, at the real parameters (about a minute).

NFC itself has no automated tests: there's no emulator support. Test on a real phone with a real tag, using a
throwaway mnemonic.

## Disclaimer

SeedTag comes **without any warranty**, and its developer isn't responsible for any loss or damage from using it,
including a lost seed or lost funds. You use it at your own risk (see also the [licenses](#license)).

**Make sure yourself that there's no malware on the phone before you enter a seed.** SeedTag defends against some
attacks by other apps (see the warnings above), but it can't cover every possible one: if the phone is compromised,
so may be your seed. The app shows this before a seed is entered, and on its About page.

## Support

SeedTag is free and has no ads. If you've found it useful, you can support its development with a
[donation](https://github.com/zivke/.github/blob/main/DONATE.md).

## License

- The app and everything else in this repository: [GNU GPL v3 or later](LICENSE) (`GPL-3.0-or-later`). You may use,
  share and change it, but anything you distribute that's based on it must be released under the same license, with
  its source.
- `tools/seedtag.py`: [BSD Zero Clause](tools/seedtag.py) (`0BSD`, the full text is in the file's header), so
  anyone can always use it to recover a tag, for any purpose.

Each source file names its license in an `SPDX-License-Identifier` line. See [changelog.md](changelog.md) for changes.
