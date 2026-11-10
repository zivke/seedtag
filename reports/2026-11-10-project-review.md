# SeedTag project review

- **Date:** 2026-11-10
- **Reviewed:** `main` at `38cb4db` (release 0.3.0)
- **Scope:** all source in `core/`, `app/` and `tools/`; the build, signing and devcontainer setup; the
  docs; and [the previous security review](2026-11-07-security-review.md). `make test` passed: 12 Python
  tests ran and passed, and the Kotlin core tests were cached as up to date. Nothing was tested on a
  device.

**Verdict:** a well-built project, better than most commercial wallet tooling. The crypto design is
right, the threat model is written down, and the defences match it. Nearly all of the previous review
has been fixed. What's left isn't in the cryptography. It's in three places:

1. Tag-handling cases where the app can still destroy or misdescribe a backup.
2. Trust in the build: nothing checks that the APK being signed was built from this source.
3. Usability traps that can make a correctly written backup impossible to recover.

## What's done well

- **Format:**
  - Argon2id parameters are fixed in code, so a crafted tag can't weaken them.
  - The length is checked before the slow Argon2 step, and the version byte is bound in as AAD.
  - Every write gets a fresh salt and nonce.
  - Base64 in a Text record, decodable by the 0BSD `seedtag.py`, means the backup doesn't depend on
    the app.
- **Integrity of a backup:** the blob is decrypted once more before writing, every write is read back
  and compared, and Lock only touches the tag holding the expected ciphertext.
- **Platform hardening:**
  - No network, enforced on every build.
  - `FLAG_SECURE`, reader mode, no text fields and `FLAG_ALT_FOCUSABLE_IM`.
  - Overlays hidden and obscured touches dropped; accessibility data marked sensitive, with a banner.
  - `taskAffinity=""`, backups off, everything wiped in `onStop`.
- **Engineering:**
  - A pure-JVM `core` module, with test vectors shared by the Kotlin and Python suites.
  - Every download pinned, and permissions checked on every APK.
  - The signing key kept out of the container.
  - Clear docs and a changelog.

## Findings

| # | Severity | Area | Finding |
|---|---|---|---|
| 1 | **Medium** | Tags / UX | The Lock text still promises more than the lock does (previous #11 is still open) |
| 2 | **Medium** | Supply chain | Nothing checks that the signed APK was built from this source |
| 3 | **Medium** | Tags | Confirming an overwrite lets the next tap overwrite *any* tag |
| 4 | **Medium** | Operations | A shared adb key volume plus a dev phone undermines everything else |
| 5 | Medium-Low | Recovery | A password set with stray spaces can never be "forgiven" |
| 6 | Medium-Low | Crypto UX | The strength estimate still over-credits human-chosen BIP39 phrases |
| 7 | Medium-Low | Recovery tool | `seedtag.py` fallback misses `UnsupportedAlgorithm`, and one KDF backend is never tested |
| 8 | Low | Tapjacking | `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` isn't handled (Android 10–11) |
| 9 | Low | Memory hygiene | The keypad prefix Strings amount to the whole seed |
| 10 | Low | Robustness | `TagIo.perform` can still throw, which leaves the scan prompt stuck |
| 11 | Low | Tags | The format guard only covers Type 2 tags; a dropped tap during an overwrite isn't explained |
| 12 | Low | Maintenance | Aging toolchain and targetSdk, no CI, `Ui.kt` too big, flow logic untested |

## Medium

### 1. The Lock text still promises more than the lock does (previous finding #11 is still open)

`app/src/main/java/rs/zivke/seedtag/Ui.kt:1334` and `:1816`

- **The flaw:** both strings say *"the tag can never be rewritten or erased"*. The
  [write-protection report](2026-11-07-tag-write-protection.md) shows that on NTAG21x, and on
  Ultralight C tags formatted without a Lock Control TLV, anyone can still set a password with read
  protection and lock the owner out.
- **Fix:** do the cheap part of TODO #11 now:
  - Reword both strings, e.g. "its data can't be changed, but someone with the tag can still make it
    unreadable: keep it shielded and keep other backups", and correct the README.
  - After `makeReadOnly()`, read back the lock and config bytes and warn if they're still open.
  - The full write-password feature can follow on its own branch.

### 2. Nothing checks that the signed APK was built from this source

`tools/sign-release.sh`, `tools/check-apk.sh`, `.devcontainer/`

- **The setup:** moving the key out of the container was the right fix. The signer still signs
  whatever `seedtag-unsigned.apk` it is handed, though. That file is built in a container with:
  - `Bash(*)` for an AI agent;
  - an unpinned `curl | bash` installer that auto-updates on every start;
  - a `latest` GitHub CLI feature.
- **The gap:** `check-apk.sh` only checks permissions. A tampered build that weakens the crypto, uses a
  fixed salt, or writes plaintext to the tag would pass it, with zero extra permissions.
- **Fix:**
  - Before signing, build again in an independent environment and compare. A CI runner or the signing
    machine would do. `dependenciesInfo` is already off, and with the same JDK and build-tools, AGP
    output is usually byte-identical.
  - Let `sign-release.sh` take the expected SHA-256 of the unsigned APK and refuse on a mismatch.
  - That makes the second build the root of trust instead of the container, and it is also the first
    step towards F-Droid-style reproducible builds.

### 3. Confirming an overwrite lets the next tap overwrite any tag

`app/src/main/java/rs/zivke/seedtag/SeedTagViewModel.kt:336` (`confirmOverwrite`)

- **The flaw:** it arms `Write(allowOverwrite = true)` with no tie to the tag that triggered the
  warning. The flag also survives a failed tap, because failed operations stay armed.
- **Scenario:** you confirm overwriting an old test tag and the tap fails. You then pick up the wrong
  tag from the table, say an existing backup of another wallet, and it is overwritten with no prompt.
- **Fix:** do what `Lock` already does. Carry the confirmed tag's UID and existing text in the request,
  and treat any other tag as a fresh "not empty" check.

### 4. A shared adb key volume plus a dev phone undermines everything else (operational)

`.devcontainer/devcontainer.json` (`android-adb` volume)

- **The setup:** the `android-adb` volume is mounted into every Android project's container. This is
  the same pattern as the old shared signing-key volume (previous finding #1). Any project's build
  scripts or dependencies, or the agent, can reach a phone that has authorised that key.
- **The impact:**
  - SeedTag stores nothing, so a silent uninstall and reinstall of a look-alike leaves no trace.
  - adb's shell can also enable accessibility services.
- **Fix:**
  - Add to "Using it safely" in the README: never enter a real seed on a phone with USB or wireless
    debugging enabled or authorised for a dev machine. Turn off Developer options and use "Revoke USB
    debugging authorizations" first.
  - Consider a per-project adb volume.

## Medium-Low

### 5. A password set with stray spaces can never be "forgiven"

`app/src/main/java/rs/zivke/seedtag/SeedTagViewModel.kt:293` (`encrypt`)

- **The flaw:** `openForgiving` only removes things: it collapses spaces and lower-cases. Suppose the
  password was *set* with an accidental double or trailing space, and the written copy shows `my pass`.
  Typing the written copy will never match. At encrypt time the password is used exactly as typed.
- **Fix:** refuse, or ask to confirm, a password with leading or trailing whitespace or repeated spaces
  when setting it, in the app and in `seedtag.py encrypt`. The format doesn't change.

### 6. The strength estimate still over-credits human-chosen BIP39 phrases

`core/src/main/kotlin/rs/zivke/seedtag/core/Passphrase.kt:51`

- **The flaw:** any 4 or more distinct BIP39 words get 11 bits each. 11 bits per word is only true for
  random picks.
  - `abandon ability able about above absent` (the first six words of the list) scores 66 bits, which
    passes `MIN_BITS` with no confirmation.
  - The same goes for natural sentences made of list words.
- **Fix:**
  - Give full credit only when `generatedPassphrase` is true.
  - For typed word phrases, discount heavily (about 5–6 bits per word).
  - Flag runs of adjacent list indexes.

### 7. `seedtag.py` fallback misses `UnsupportedAlgorithm`, and one KDF backend is never tested

`tools/seedtag.py:267` (`derive_key`)

- **The fallback:** it only falls back to argon2-cffi on `ImportError`. With `cryptography` ≥ 44 linked
  against an OpenSSL that lacks Argon2 (older than 3.2), constructing `Argon2id` is expected to raise
  `UnsupportedAlgorithm` instead. That case hasn't been run. The user would get a traceback rather than
  the fallback, in the one tool meant to work when everything else is gone.
- **The test gap:** the container ships Ubuntu's `cryptography` 41, so `make test` only ever runs the
  argon2-cffi path. The `cryptography` path, which most pip users will get, is never tested here.
- **Fix:**
  - Catch `UnsupportedAlgorithm` as well.
  - Add a test that runs the vectors through each backend that's installed.
  - Say clearly in the output which backend was used.

## Low

### 8. Partially obscured touches aren't handled (Android 10–11)

`app/src/main/java/rs/zivke/seedtag/MainActivity.kt:103`, `GuardDialogWindow` in `Ui.kt`

- **The flaw:** only `FLAG_WINDOW_IS_OBSCURED` is checked, which is set only when the overlay covers the
  touch point itself. On Android 10–11, which can't hide overlays, an overlay can cover the warning
  text of *Overwrite* or *Lock forever* while leaving the button clear.
  `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` (API 29+) catches that.
- **Fix:** also drop touches with that flag, at least on the confirm buttons, and do the same in
  `GuardDialogWindow`.

### 9. The keypad prefix Strings amount to the whole seed

`app/src/main/java/rs/zivke/seedtag/SeedTagViewModel.kt:209` (`typeLetter`), `WordGrid` in `Ui.kt`

- **The flaw:** each keystroke builds a new `String` (`prefix + c`, and `"$current▏"` in `WordGrid`).
  The first four letters identify any BIP39 word, so the leftover prefixes add up to the seed.
- **The docs:** the README calls them "the few letters of the word being typed", which understates
  this. Exploiting it still needs root or a debugger.
- **Fix:**
  - Keep the prefix in a zeroable `CharArray`, and show bullets for it while `hideWords` is on, so no
    String of it is needed.
  - Make the README wording say the risk plainly.

### 10. `TagIo.perform` can still throw

`app/src/main/java/rs/zivke/seedtag/TagIo.kt:71`

- **The flaw:** it is documented as "never throws", but it only catches 5 exception types.
  - Any other `RuntimeException`, such as `IllegalStateException` from tag technology connections,
    escapes on the binder thread. `pending` was already cleared by then, so the result is most likely
    a "Ready to scan" prompt that ignores every tap.
  - `OutOfMemoryError` from two 64 MiB Argon2 runs on a low-RAM phone is an `Error` and isn't caught.
- **Fix:**
  - Add a final `catch (e: Exception)` that returns a generic `Failed`, with no message text that could
    hold data.
  - Catch `OutOfMemoryError` around the Argon2 work and show a clear message.

### 11. Overwrite safety at the edges

`app/src/main/java/rs/zivke/seedtag/TagIo.kt:98` (`write`)

- **Format guard:** `looksNdefFormatted` only protects Type 2 tags. A formatted Type 5 or Type 4 tag
  whose NDEF check failed on a quick tap would be formatted, wiping its contents, without the "not
  empty" prompt.
- **Interrupted overwrite:** if the tag is pulled away halfway through an *overwrite*, the message is the
  generic "moved away too early". It doesn't say the old content may already be gone.

### 12. Maintenance and design

- **Toolchain age:** AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12 and Bouncy Castle 1.78.1 are close to
  two years old. No CVE is known to affect the Argon2 code path, but they should be updated on a
  schedule. If the app goes to Google Play, check the current target-API requirement, which is likely
  past 35 by now.
- **No CI:** a CI run of `make test`, `make build` and `check-apk` would also give the independent build
  that finding #2 needs.
- **`Ui.kt` is 1,824 lines:** split it by screen (backup, read, keyboards, dialogs, theme).
- **Flow logic is untested:** the most error-prone logic (stale scan ids, re-arming after a failure,
  overwrite and lock binding) sits in the view model, where nothing tests it. Pulling it into a
  pure-Kotlin state machine with `TagIo` behind an interface would make finding #3 and the earlier race
  fixes unit-testable without a phone.
- **Doc nits:**
  - ~~The README and Makefile call the wrapper "Gradle 8.14", but the distribution is 8.11.1.~~ *Correction:*
    both are right. The jar is the official wrapper jar of Gradle 8.14 (checked against
    services.gradle.org), and it runs the 8.11.1 distribution. The wording now names both.
  - `Bip39.normalize(String)` is unused and makes String copies of seed input; delete it or mark it
    test-only.

## Informational

- **Argon2id parameters:** 64 MiB, t=3, p=1 is reasonable for phones and is fine with a 77-bit
  passphrase. If a v2 format is ever defined, consider more memory where phones can afford it. Don't
  change v1.
- **Fingerprint:** 32 bits, already documented as previous finding #14. That's fine.

## Suggested order

1. **Now, on `main`:** #1 (wording plus the lock read-back), #3, #5, #10, the adb advice from #4, and
   the #7 exception fix.
2. **Next:** #2 (independent build and hash pinning in `sign-release.sh`) together with CI.
3. **On their own branches:** the write-password lock from previous finding #11; extracting the flow
   state machine with tests; #6, #8 and #9.

On-device behaviour (#1's read-back, #3, #8, #11) can only be confirmed with a phone and spare tags.
