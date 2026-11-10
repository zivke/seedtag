# SeedTag expert review

- **Date:** 2026-11-10
- **Reviewed:** `main` at `a8bff16` (unreleased changes after 0.3.0)
- **Scope:** all source in `core/`, `app/` and `tools/`; the Gradle, Makefile, CI, signing and
  devcontainer setup; the docs; the three earlier reports and `TODO.md`. The branches
  `review-fixes-needs-device-test` and `tag-write-password` were read but not reviewed in depth.
- **What was run:** `make test` (Kotlin core tests passed; 16 Python tests ran OK, with the `cryptography` backend sub-test skipped),
  plus throwaway probes of the compiled `core` classes and of `seedtag.py`, using Trezor vector
  mnemonics only. **Nothing was tested on a device**; anything marked *needs device* is reasoning from
  the code and from Android's documented behaviour.
- **Not accessed:** `other/`, any keystore, `.env`, the network.

## Executive summary

**Verdict:** the cryptographic core is correct and conservative, and the fixes ticked in `TODO.md`
are really in the code. I would trust a real seed to this app today under these conditions:

1. The password is the **generated 7-word passphrase** (or an equally random one).
2. I **Verify from the written copy** before relying on the tag, and recover once with `seedtag.py`.
3. The tag is **one copy among several**, and a copy of `seedtag.py` is kept with the backup.
4. The APK is one I built or whose source diff I reviewed, on a phone without adb authorisations.

I would not yet recommend it to a non-expert without those conditions, for three reasons, none of
them in the cipher:

- **The strength meter still calls famous weak passwords "Strong".** `correct horse battery staple`
  scores 86 bits and `password password password` 84, with no confirmation. The password is the
  whole security against an offline attacker, so this is the most important open item.
- **A generated passphrase is never checked against what the user wrote down.** The Repeat field is
  filled in automatically, and *Backup written* appears without any proof that the written copy
  opens the tag.
- **The release process proves where the APK was built, not what it was built from.** The dev
  container holds working GitHub push credentials, so the "independent" CI build can be made to
  build whatever the container pushes.

Everything else is Low or informational. No finding is Critical or High.

## Status of earlier findings

Checked against the code, not the ticks.

| Earlier finding | Status on `main` | Adequate? |
|---|---|---|
| 11-07 #2, 11-10 #6: strength estimate | BIP39-word path fixed | **No**: see finding 1 |
| 11-07 #6: decrypt before writing | `SeedTagViewModel.kt:324-331` | Yes |
| 11-10 #2: independent build | `--expect`, CI workflow | **Partly**: see finding 3 |
| 11-10 #3: overwrite bound to the tag | `OverwriteTarget`, `TagIo.kt:141-147` | Yes |
| 11-10 #5: stray spaces | `Passphrase.hasStraySpaces`, both tools | Yes (edge case in finding 13) |
| 11-10 #7: `seedtag.py` KDF fallback | Fallback and tests added | **Partly**: see finding 5 |
| 11-10 #9: zeroable keypad prefix | `prefixBuf`, bullets while hidden | Yes |
| 11-10 #10: `TagIo.perform` never throws | Catch-all at `TagIo.kt:102`; `OutOfMemoryError` caught in the view model | Yes |
| 11-10 #1: Lock wording | Reworded in the app and README | Mostly: see finding 9 |
| 11-10 #4: adb and real seeds | README only | Docs yes; see finding 10 |

## Findings

| # | Severity | Category | Finding |
|---|---|---|---|
| 1 | **Medium** | Crypto UX | The strength meter rates well-known weak passwords "Strong" (previously reported, fix incomplete) |
| 2 | **Medium** | UX / data safety | A generated passphrase is never checked against the written copy |
| 3 | **Medium** | Supply chain | `--expect` proves the build, not the source; the container can push to GitHub (previously reported, fix partial) |
| 4 | Medium-Low | Recovery | No dependency-free recovery path, and no advice to keep `seedtag.py` with the backup |
| 5 | Low | Tests / recovery | The `cryptography` Argon2id backend is never tested anywhere (previously reported, fix partial) |
| 6 | Low | Android | Argon2id's 64 MiB sits on the Java heap with no `largeHeap` |
| 7 | Low | UX | Screen timeout or a back gesture wipes everything mid-entry |
| 8 | Low | Format | An old app meeting a newer format gives no useful guidance |
| 9 | Low | Tags | Lock reports "now permanently read-only" for a tag it didn't lock; Lock is probably unavailable on Type 4 and 5 tags |
| 10 | Low | Android | The app doesn't warn about adb, which the README ranks as a silent-replacement risk |
| 11 | Low | Docs | A BIP39 passphrase ("25th word") isn't stored, and nothing says so |
| 12 | Low | Design | Late tag results can leave a scan armed with no prompt on screen |
| 13 | Low | Parity | Kotlin and Python disagree on two password-variant edge cases |
| 14 | Info | Tags | A tag that can't be read is treated as empty |
| 15 | Info | Build | `check-apk.sh` and `check-workflows.py` could check a little more |

## Medium

### 1. The strength meter rates well-known weak passwords "Strong"

*Previously reported (11-07 #2, 11-10 #6). The fix covers only lower-case BIP39 words.*

`core/src/main/kotlin/rs/zivke/seedtag/core/Passphrase.kt:60-97`, `Ui.kt:1202-1208`,
`SeedTagViewModel.kt:298-309`

- **The flaw (verified by running the compiled class):** the word path at `Passphrase.kt:62-63` only
  triggers when *every* token is a lower-case BIP39 word. Anything else falls to the character path
  at `:89-96`, which is length × log2(pool) × 0.6. One capital, one punctuation mark or one
  non-list word switches paths.

  | Password | Estimate | Meter |
  |---|---|---|
  | `correct horse battery staple` | 86 bits | Strong, no confirmation |
  | `Correct Horse Battery Staple` | 99 | Strong |
  | `password password password` | 84 | Strong |
  | `the quick brown fox jumps` | 88 | Strong |
  | `Abandon Abandon Abandon Abandon` | 96 | Strong |
  | `abandon abandon abandon abandon.` | 82 | Strong |
  | `Summer2026!Summer2026!` | 80 | Strong |
  | `a1a1a1a1a1a1a1a1a1a1a1a1a1` | 80 | Strong |
  | `iloveyouiloveyouiloveyou` | 67 | Fair, no confirmation |
  | `abandon abandon abandon abandon` (lower case) | 6 | Weak, confirmation |

- **Scenario:** a user declines the generated passphrase, types `correct horse battery staple`, sees
  a green "Strong · ≈86 bits" and writes the tag. That phrase is in every cracking wordlist. Anyone
  who reads the tag (a few seconds of contact, or a skimmer) recovers the seed with a handful of
  Argon2 runs.
- **Why it matters more here:** Argon2id at 64 MiB adds roughly 20 bits of work. That's ample on top
  of 77 random bits and worthless on top of a dictionary phrase. The meter is the only thing between
  a user and that mistake.
- **Fix** (no new dependency needed):
  - Never show "Strong" for a typed password. Only `generatedPassphrase == true` earns it. For
    everything else, cap the label at "Can't be rated" and always show the confirmation dialog,
    which already says a generated passphrase is recommended.
  - If an estimate is kept for typed passwords, split tokens on any non-letter, fold case before the
    BIP39 lookup, count a repeated token or repeated substring once, and cap plain letters at about
    1.5 bits per character.
  - Add the rows above to `PassphraseTest` as cases that must need confirmation.

### 2. A generated passphrase is never checked against the written copy

`SeedTagViewModel.kt:281-287` (`generatePassphrase`), `Ui.kt:1154-1156`, `Ui.kt:1181-1190`,
`Ui.kt:1220-1253` (`DoneStep`)

- **The flaw (verified in code):**
  - `generatePassphrase()` writes the passphrase into both `password` and `passwordRepeat`. The
    Repeat field exists to catch a mistake; for the recommended path it's filled in by the app.
  - The **Generate a 7-word passphrase** button stays in place after use. A second tap replaces the
    passphrase in the same box, with no confirmation.
  - `DoneStep` then shows "Backup written" on a green hero. Verify is recommended, not required.
- **Scenario A:** the user writes down the seven words, then brushes the Generate button while
  scrolling to **Encrypt & write tag**. The tag is encrypted with a passphrase that was never
  written down. The app reports success.
- **Scenario B:** the user miscopies one word (`affair` for `afford`). The forgiving retry doesn't
  cover that. Nothing notices until a Verify that may never happen.
- **Why Medium:** in both cases the tag is cryptographically perfect and permanently unopenable, and
  the app has said "Backup written". The current mitigation is text asking the user to Verify.
- **Fix:**
  - For a generated passphrase, leave Repeat empty and ask the user to type it back **from the
    written copy**. The BIP39 suggestions make that about 30 taps.
  - Once a passphrase has been generated, relabel the button "Generate a different one" and confirm
    before replacing.
  - Consider making *Backup written* amber until a Verify with a matching fingerprint has been done
    in the same session.

### 3. `--expect` proves the build, not the source, and the container can push to GitHub

*Previously reported (11-10 #2). The fix is correct but covers only half the threat.*

`tools/sign-release.sh:25-29` and `:47-58`, `.github/workflows/build.yml:12-15`, `README.md:74-80`,
`.claude/settings.json:7`

- **What the fix does well:** `sign-release.sh --expect` compares the unsigned APK with the hash from
  a GitHub Actions build of the same commit. That defeats a poisoned Gradle cache, SDK or toolchain
  in the dev container.
- **What it doesn't cover:**
  - **The source.** CI builds whatever commit the tag points at. Both builds agree on a malicious
    commit just as well as on a good one.
  - **The container can create that commit and that tag.** Verified: VS Code's git credential helper
    is configured in the container (`/etc/gitconfig` and `~/.gitconfig`), and `origin` is the HTTPS
    GitHub URL. I did not attempt a push, so that it would succeed is an inference. The deny rule
    `Bash(git push:*)` is a prefix match on one spelling of the command; `git -C . push`, a Makefile
    target or a Gradle task aren't covered. The devcontainer README already says the container is
    the real boundary, which is the point: the boundary includes push access.
  - **`--expect` is optional.** Without it the script warns on stderr and signs
    (`sign-release.sh:55-58`).
  - **The check the README recommends misses uncommitted changes.** `git log -p -- tools/…`
    (`README.md:76-77`) shows commits only. If the signing machine runs the script from the same
    working tree the container mounts, an uncommitted edit to `sign-release.sh` or `check-apk.sh`
    runs on the host, next to the keystore and its password.
- **Scenario:** a compromised extension, installer or dependency in the container commits a
  two-line change to `SeedBox.seal` (fixed salt, or a weak key), pushes it with a `v*` tag, and CI
  publishes a matching hash. The owner runs `sign-release.sh --expect <ci hash>` and signs it.
- **Fix:**
  - Sign from a **fresh clone on the signing machine**, never from the container's working tree.
  - Create release tags as **signed tags** (`git tag -s`) with a key that isn't in the container,
    and have `sign-release.sh` run `git verify-tag` and check that `HEAD` is that tag.
  - Before signing, review `git diff <previous signed tag>..<new tag>` on the signing machine. Add
    that step to the README's signing instructions; it's the only defence for the source.
  - Make `--expect` mandatory, with an explicit `--no-expect` to override.
  - Remove push access from the container: turn off credential forwarding
    (`dev.containers.gitCredentialHelperConfigLocation: none` on the host) and push from the host.
    Add a GitHub tag-protection rule for `v*`.

## Medium-Low

### 4. No dependency-free recovery path, and no advice to keep `seedtag.py` with the backup

`tools/seedtag.py:320-322`, `:349`, `:325`; `README.md:317-334`

- **The question:** can someone recover in ten or more years with only `seedtag.py` and the password?
  Today, yes. Later, it depends on three things outside the project.
- **The gaps (verified):**
  - `seedtag.py` needs the third-party `cryptography` package for AES-GCM in every case. With it
    missing, `verify` and `decrypt` end in an uncaught `ModuleNotFoundError` traceback, not the
    clear message the Argon2 path gives. I reproduced this by blocking the import.
  - The README says "It only needs `cryptography` ≥ 44", and offers `argon2-cffi` as a fallback, but
    `argon2-cffi` alone isn't enough: AES-GCM still needs `cryptography`.
  - Recovery therefore needs a `pip install` that works on whatever Python exists then, which means
    a network connection or a wheel saved in advance.
  - Nothing in the README or the app tells the user to **keep a copy of `seedtag.py`** (and the
    format description) with the backup. If the GitHub repository is gone, an heir has a tag, a
    password and no tool.
  - The file needs Python 3.10 or later (`bytes | None` at `:325` is evaluated when the function is
    defined). That isn't stated.
- **What's fine:** the 0BSD licence, the embedded wordlist, the format block in the docstring and
  the README are enough for a competent programmer to reimplement it.
- **Fix:**
  - Add a pure-Python fallback for **decrypt only**: Argon2id on top of `hashlib.blake2b`, and
    AES-256-GCM decryption. It's roughly 200 lines, slow (minutes) and needs nothing but Python.
    Test it against the existing vectors. The fast backends stay the default.
  - Catch `ImportError` around the `cryptography` imports and print what to install.
  - In the README and on the Done screen: "Keep a copy of `seedtag.py` (print it or save it to a USB
    stick) with your written password."
  - State the minimum Python version in the docstring and README.

## Low

### 5. The `cryptography` Argon2id backend is never tested anywhere

*Previously reported (11-10 #7). The fallback fix is correct; the test gap remains.*

`tools/test_seedtag.py:100-113`, `.devcontainer/Dockerfile:11-16`, `.github/workflows/build.yml:36`

- **Verified:** `make test` reports `backend='cryptography' ... skipped`. The image ships Ubuntu's
  `cryptography` 41, and CI builds the same image, so the skip happens in every environment that
  runs the tests.
- **Why it matters:** `pip install cryptography` is what the README tells users to run, so that's the
  backend a recovery will actually use. Its agreement with the vectors has never been checked by this
  project. The vectors themselves are sound: Bouncy Castle and the reference libargon2 agree on them.
- **Fix:** add a CI step that creates a venv, installs a hash-pinned `cryptography` ≥ 44 and runs
  `tools/test_seedtag.py`, failing if any backend is skipped. Add vectors for 18 and 21 words while
  there; the file covers 12, 15 and 24 only.

### 6. Argon2id's 64 MiB sits on the Java heap with no `largeHeap`

`app/src/main/AndroidManifest.xml:19-27`, `SeedBox.kt:38`, `SeedTagViewModel.kt:83-85`

- **The flaw (verified: no `largeHeap` in the manifest; the rest is judgement, needs device):**
  Bouncy Castle allocates Argon2's memory as 65,536 `long[128]` arrays on the managed heap. An app's
  heap is capped by `dalvik.vm.heapgrowthlimit`, which is 96–128 MB on many low-RAM and older
  devices and 64 MB on some.
- **Scenario:** the README's safest option is "a spare phone that's never online … an old phone is
  fine". On such a phone every Back up, Verify and Restore may end in the out-of-memory message. The
  message is clear and nothing is lost, but the app is unusable exactly where it's recommended.
- **Fix:** add `android:largeHeap="true"`. It needs no permission. Test on the oldest phone
  available. If a v2 format is ever defined, a native Argon2 would lift the limit altogether.

### 7. Screen timeout or a back gesture wipes everything mid-entry

`MainActivity.kt:172-176`, `Ui.kt:277`

- **The flaw (verified in code; the feel needs a device):**
  - There's no `FLAG_KEEP_SCREEN_ON`. A typical 30-second screen timeout stops the activity, and
    `onStop` calls `wipeAll()`.
  - `BackHandler` is only enabled for the two top-bar pages. On the tabs, system Back finishes the
    activity. The keypad's `q`, `a`, `p`, `l` and backspace keys sit at the screen edges, where the
    back gesture starts.
- **Scenarios:**
  - The user is copying the generated passphrase onto paper. The screen goes dark at word four. On
    return, the seed words and the passphrase are gone, and a new passphrase is generated. The
    half-written note is now wrong; this feeds finding 2.
  - During Restore, the user is copying 24 words into a hardware wallet. The screen times out, and
    they must type the password and scan again, possibly several times.
- **Why Low:** it's safe by design, since nothing is written. But it makes the safety-critical steps
  frustrating, and frustrated users take shortcuts.
- **Fix:**
  - Keep the screen on while words, a password or restored words are on screen, with the app's own
    idle timeout (two or three minutes without a touch, then wipe).
  - On the Words and Password steps, map Back to the on-screen *Back* button; elsewhere with unsaved
    input, ask before leaving.

### 8. An old app meeting a newer format gives no useful guidance

`core/.../Errors.kt:17-18`, `TagIo.kt:189-190` (`looksLikeBackup`), `tools/seedtag.py:341-342`

- **The flaw (verified):**
  - A tag with version byte 0x02 gives "Unsupported tag format version 0x02." It doesn't say that a
    newer SeedTag wrote it or what to do.
  - `looksLikeBackup` accepts version 1 only. When a v1 app is asked to overwrite a v2 backup, the
    Check step says "Other data" and the dialog says "This tag already contains data. Overwriting
    replaces it", not the stronger "destroys that backup".
- **Scenario:** years from now, a user with an old APK on the spare phone checks a tag written by a
  newer version, reads "other data" and overwrites the only copy.
- **Fix:** decide this now, because it has to ship in the *old* app to help.
  - Message: "This tag was written by a newer version of SeedTag (format N). Update the app or use a
    current seedtag.py."
  - Treat any Base64 text that decodes to 61–93 bytes with a first byte of 1–15 as a SeedTag backup
    for the overwrite warning.
  - Do the same in `seedtag.py`.

### 9. Lock reports "now permanently read-only" for a tag it didn't lock

*Related to the deferred lock read-back (11-07 #11, 11-10 #1).*

`TagIo.kt:232-233`, `SeedTagViewModel.kt:469-477`, `README.md:228-248`

- **The flaw:**
  - `if (!it.isWritable) return TagResult.Locked` (verified). The app then says "The tag's data is
    now permanently read-only". Android reports a Type 2 tag as not writable when only the
    capability container says so, with the lock bits still open. Such a tag can be rewritten with
    raw page writes. The app made no change and can't know the lock is permanent.
  - As far as I recall from AOSP, `Ndef.canMakeReadOnly()` is true for Type 1 and 2 tags only
    (*needs device*). If so, **Make tag read-only** fails on NTAG 424 DNA, DESFire, ICODE and ST25TV,
    which the README's table lists for their 25–60 year retention. The README doesn't say so.
- **Scenario:** a user buys an ICODE SLIX2 for its 50-year rating, as the table suggests, and
  discovers after the backup that it can't be locked.
- **Fix:**
  - Return a separate result for an already read-only tag: "This tag was already read-only. SeedTag
    didn't lock it and can't tell whether that's permanent."
  - Test Lock on one Type 4 and one Type 5 tag, and add a "Lock works" column to the README table.
- **On the deferral:** waiting for `tag-write-password` before the read-back is reasonable, since that
  branch detects the chip. The first bullet above is independent of it and can go to `main` now.

### 10. The app doesn't warn about adb

`MainActivity.kt:149-157` (`refreshRadios`), `README.md:141-145`

- **The gap (verified):** the README says anything with adb access can silently replace SeedTag or
  switch on an accessibility service, and tells the user to turn debugging off. The app shows banners
  for airplane mode and accessibility services but never looks at debugging, although
  `Settings.Global.ADB_ENABLED` and `"adb_wifi_enabled"` are readable without a permission, like the
  airplane-mode setting it already reads.
- **Also:** the accessibility list is only refreshed in `onResume` (`MainActivity.kt:84`). A service
  enabled over adb while SeedTag is in the foreground isn't noticed.
- **Fix:** add a Preflight item, "USB or wireless debugging is on". Register an
  `AccessibilityManager` state-change listener between `onResume` and `onPause`.

### 11. A BIP39 passphrase ("25th word") isn't stored, and nothing says so

`README.md` (Using it, Format v1), `Ui.kt:1150` and elsewhere

- **The gap (verified by search):** SeedTag stores the BIP39 entropy only. A wallet that also uses a
  BIP39 passphrase can't be restored from the tag alone. Neither the README nor the app says this.
- **It's made worse by the vocabulary:** the app calls its own password a "passphrase" throughout.
  A user with a 25th word can reasonably think "the passphrase" is covered.
- **Fix:** one sentence on the Words step and in the README: "If your wallet also uses a BIP39
  passphrase (a '25th word'), SeedTag doesn't store it. Back it up separately." Mention too that
  only English BIP39 phrases are supported.

### 12. Late tag results can leave a scan armed with no prompt on screen

`SeedTagViewModel.kt:433-485` (`handle`)

- **The flaw (verified in code; the timing needs a device):**
  - `Written` and `Locked` are deliberately handled even when stale, which is right: the write
    happened. But both set `scanPrompt = null` without looking at `pending`. If a newer request was
    armed in the meantime, it stays armed with nothing on screen, and the next tag that touches the
    phone is acted on.
  - `WrittenUnchecked` re-arms after a Cancel, so the scan prompt comes back by itself.
  - `encrypt()` can run twice on a double tap, before the busy dialog composes. That means two
    64 MiB derivations at once (see finding 6).
- **Why Low:** the windows are tens of milliseconds for Lock and about a second for a write, and the
  armed requests are bound to their tag's content.
- **Fix:** in the stale branches, close the prompt only if `pending.get() == null`; return early from
  `encrypt()` when `busy != null`.
- **On the deferral:** these are exactly the cases a pure-Kotlin state machine with a fake `TagIo`
  would pin down in unit tests. I'd do that extraction before merging `tag-write-password`, not
  after: that branch adds more states to the code that's hardest to reason about.

### 13. Kotlin and Python disagree on two password-variant edge cases

`SeedBox.kt:126-154`, `tools/seedtag.py:359-378`

- **Verified by running both:**

  | Password | Kotlin | Python |
  |---|---|---|
  | `a` U+0085 `b` | no variant; not "stray" | `Spaces` variant; "stray" |
  | U+10400 `x` (a capital outside the BMP) | no variant | `Case` variant |

  Kotlin's `Char.isWhitespace()` doesn't include U+0085, and it lower-cases one UTF-16 unit at a
  time. The comments on both sides say "same rules".
- **Also:** a lone surrogate in a password makes `passwordBytes` (`SeedBox.kt:179-187`) throw
  `MalformedInputException`, which isn't a `SeedTagException`. The view model wouldn't catch it.
- **Why Low:** none of these characters can be typed on the app's keyboard. They can only come from
  `seedtag.py encrypt` on a terminal, and the blob format isn't affected.
- **Fix:** define whitespace in both tools as an explicit list, add the two rows to
  `password-variants.tsv`, and wrap the encoder failure in `SeedTagException.Malformed`.

## Informational

### 14. A tag that can't be read is treated as empty

`TagIo.kt:243-244` (`readMessage`), `:141-147`

- `getNdefMessage()` returns null both for an empty tag and for a failed read while the tag is still
  in range. The code reads twice and falls back to the message cached at discovery, which is sound.
- If all three are null on a tag that does hold data (a marginal position where reads fail and the
  following write succeeds), the write goes ahead without the overwrite prompt. *Needs device*; I
  think it's rare.
- **Possible hardening for Type 2 tags:** before writing without confirmation, read page 4 onwards
  and require the NDEF TLV length to be 0.
- Related, *needs device*: reader mode is enabled in `onResume` only. If NFC is switched off and on
  from the quick-settings shade, which doesn't pause the activity, check that tags still reach the
  app.

### 15. `check-apk.sh` and `check-workflows.py` could check a little more

`tools/check-apk.sh:44-58`, `tools/check-workflows.py:21`

- `check-apk.sh` checks permissions only, as the README says. A tampered APK passes it trivially;
  that's what `--expect` is for. As a guard against honest mistakes it could also fail a release APK
  that is `debuggable` or `testOnly`, or that has any component besides `MainActivity`
  (`aapt2 dump badging` and `dump xmltree`).
- `check-workflows.py` checks that each action reference has 40 hex digits. It can't tell whether
  that commit is the tag named in the comment. I couldn't verify the two SHAs offline; check them
  once by hand against the action repositories' release pages.
- The app opens two fixed GitHub URLs from the About page (`Ui.kt:1599-1600`, `:1675`). That's
  harmless as written. It also shows that an `ACTION_VIEW` intent is a way out of the app that needs
  no permission, so a compromised dependency could use the same route. The offline advice covers it.

## Side branches (brief)

- **`review-fixes-needs-device-test`:**
  - The partially-obscured filter is limited to API 29–30 and wraps the dialog's `Window.Callback`.
    It reads correctly. Keeping it off `main` until tried on an Android 10 or 11 phone is right: a
    false positive would block every tap.
  - The Type 5 guard sends an unaddressed READ SINGLE BLOCK. Some tags want addressed mode; on those
    it throws `IOException`, which fails safe.
  - Bouncy Castle 1.86: `make test` pins Argon2's output, so compatibility is provable off-device.
    Only R8 on a phone needs a look. I'd merge this one first.
- **`tag-write-password`:**
  - The write secret is HMAC-SHA256 keyed with the blob's AES key over a fixed label. The NTAG
    password (4 bytes of it) travels in clear, but that gives an attacker nothing the GCM tag doesn't
    already give: both need one Argon2 run per guess. Using the AES key directly as an HMAC key is
    acceptable; a KDF step that derives both would be cleaner in a v2.
  - The secret depends on the blob's salt, so it changes on every rewrite, and a tag whose blob has
    become unreadable can't have its password derived again. Document both.
  - `TagKey.kt` on the branch says `Copyright (C) 2026 zivke85`; `main` uses `zivke`.

## Checked and fine

**Cryptography**

- AES-256-GCM: 12-byte random nonce, 128-bit tag, version byte as AAD, a fresh `SecureRandom` salt
  and nonce on every `seal`. The key is unique per salt, so nonce reuse can't happen in practice.
- Salt and nonce aren't in the AAD and don't need to be: the salt is bound through the key and the
  nonce through GCM.
- Argon2id v1.3, t=3, m=64 MiB, p=1 is fixed in code and implied by the version byte. A crafted tag
  can't weaken it or exhaust memory. It matches RFC 9106's second recommended set apart from p. It's
  adequate **given** a password of about 65 random bits or more; see finding 1.
- The blob length is checked before the KDF runs. "Wrong password" and "corrupted" are deliberately
  the same error; there's no oracle beyond what an offline attacker has anyway.
- `openForgiving` runs at most three derivations and only changes the typed password. It doesn't
  lower an attacker's cost.
- NFKD then UTF-8, with an ASCII fast path. The vectors include a composed and a decomposed spelling
  of the same password giving the same blob.
- BIP39: all 24 Trezor vectors in both languages, random round-trips, checksum, word-count and
  unknown-word errors. The wordlist's SHA-256 is `2f5eed53…dbda` (I recomputed it), it's sorted, and
  a test compares the Python copy with the app's.
- Fingerprint: 32 bits of `SHA-256("seedtag-fp" ‖ entropy)`. It gives no shortcut for guessing the
  password and no practical information about a seed that's otherwise unknown. The README's "two
  missing words, about 16,000 candidates" is right.
- Passphrase generator: `SecureRandom.nextInt(2048)`, distinct words, seed words excluded. About
  76.9 bits; "≈77" is honest.
- Kotlin and Python parity: six shared vectors with fixed salt and nonce, sealed and opened by both,
  plus eleven shared password-variant rows. Bouncy Castle and libargon2 agree.

**Secrets in memory**

- Entropy, password bytes and the derived key are zeroed in `finally` on every path I traced.
- Passwords and the keypad prefix live in `SecretChars`; `toString` never prints the content.
- Seed words are references to the wordlist's own strings.
- `wipeAll()` covers words, prefix, all three password buffers, restored words, pending text and the
  overwrite target, and bumps `wipes` so Argon2 work that finishes later is thrown away.
- No `rememberSaveable`, no saved state, no logging, no toasts, no clipboard, no files. I searched
  for each.
- What the README lists as not wipeable matches what the code leaves behind.

**Android platform**

- Manifest: NFC, `HIDE_OVERLAY_WINDOWS` and `VIBRATE` only; `INTERNET` and `ACCESS_NETWORK_STATE`
  removed; `allowBackup="false"` with extraction rules; one exported launcher activity with no other
  intent filter; `taskAffinity=""`.
- `FLAG_SECURE` is set before content. Compose dialogs inherit it by default, and every dialog calls
  `GuardDialogWindow()`.
- Reader mode for the whole foreground time; tags with nothing pending are ignored.
- Obscured touches are dropped in the activity and in dialogs, and a gesture already in progress is
  cancelled.
- No `TextField` anywhere; `FLAG_ALT_FOCUSABLE_IM` is set.
- Error messages carry positions and exception class names only, never data.

**Tag I/O**

- "Backup written" is only reached through `TagResult.Written`, which needs a read-back equal to
  what was written, on the same tap or the next one.
- The blob is decrypted with a second key derivation before anything is written.
- Overwrite needs the same content, and the same UID when the UID is stable. A different tag is
  asked about again.
- Lock compares the tag's text with the last written or verified blob over the same connection.
- An interrupted overwrite says the old content may be gone.
- The blank-tag size probe never asks a known chip for a page it doesn't have; I walked through each
  chip in the table.
- The pending request is taken with `getAndSet(null)` on the binder thread; results are handled on
  the main thread and checked against `scanId`.
- The size arithmetic is right: 111 bytes for 24 words, 91 for 12, and 137 on an Ultralight C.

**Build and supply chain**

- The wrapper jar's hash matches the pinned file (recomputed). `distributionSha256Sum` is set.
- Dependency verification is on with `verify-metadata`; the only trusted exceptions are source and
  javadoc jars.
- The base image is pinned by digest and the command-line tools by SHA-256.
- CI: tag and manual triggers only, `contents: read`, no secrets, `persist-credentials: false`,
  actions pinned by SHA.
- `sign-release.sh` deletes its output unless it carries the pinned certificate.
- No signing configuration in Gradle; `dependenciesInfo` is off.

**Licensing**

- `seedtag.py` is 0BSD with the full text in its header. SPDX lines are present in the files I read.

## Prioritized action list

1. **Now, on `main`, no device needed:**
   - Finding 1: only the generated passphrase earns "Strong"; always confirm a typed password.
   - Finding 4: the `ImportError` message, the "keep a copy of seedtag.py" advice and the Python
     version.
   - Finding 8: the newer-format message and a wider `looksLikeBackup`.
   - Finding 11: the BIP39-passphrase sentence.
   - Finding 13: the two vector rows and the explicit whitespace list.
2. **Now, on `main`, then a short device check:**
   - Finding 2: type the generated passphrase back; confirm before regenerating.
   - Finding 6: `largeHeap`.
   - Finding 9, first bullet: the "already read-only" message.
   - Findings 10 and 12.
3. **Release process, before the next signed release:** finding 3. Fresh clone, signed tags, a diff
   review step, a mandatory `--expect`, and no push credentials in the container.
4. **Next:**
   - Finding 5: the CI step for the `cryptography` backend.
   - Finding 4: the pure-Python decrypt fallback.
   - Finding 7: keep the screen on, and handle Back.
5. **Before merging `tag-write-password`:** extract the tag-flow state machine with tests
   (finding 12), then the lock read-back.

Device confirmation is needed for findings 6, 7, 9 (second bullet), 12 and 14.
