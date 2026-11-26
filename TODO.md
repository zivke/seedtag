# TODO

## Security (open items from [the security review](reports/2026-11-07-security-review.md))

- [x] **#1 (High): move release signing out of the dev container.** The key and `KEY_PASS` can be reached
  by anything that runs commands in it, and the `android-keys` volume is shared by every Android project.
  Sign on a separate offline machine, or at least stop mounting the key by default and give each
  project its own volume.
- [x] **#5 (Medium): block accessibility services on Android 14+.** `setAccessibilityDataSensitive(YES)`
  on the window and dialogs, plus a banner naming apps with accessibility access on every version.
- [x] **#7 (Medium): verify the build supply chain.**
  - Add Gradle dependency verification and `distributionSha256Sum`.
  - Pin the Docker image digest and checksum the Android tools zip.
  - Publish each release APK's SHA-256.
- [ ] **#11 (Medium): a "read-only" tag can still be locked against reading by a stranger.** Options are
  in [the tag write-protection report](reports/2026-11-07-tag-write-protection.md). In order:
  - [ ] After locking, read the lock and settings bytes back, and warn if the settings are still open. On
    branch `lock-readback` (2026-11-26): on NTAG21x layouts SeedTag writes the lock itself and reads it back
    (`NtagLock`); device rows 7.59-7.63.
  - [x] Correct the README's claim that locking protects the tag. Recommend a metal box or shielding
    sleeve, and not carrying the tag around.
  - [ ] Proper lock (settings frozen too) or a write password, on a separate branch, tested with spare
    tags. The write password is B17's Protect from writes; the proper lock (CFGLCK with AUTH0 FF, the owner's
    choice on 2026-11-26) is on `lock-readback`.
  - [ ] Rescue for Ultralight C tags already locked with `FF 0F` (none known: the tested tag turned out to be an
    NTAG213).

## Project review (open items from [the 2026-11-10 project review](reports/2026-11-10-project-review.md))

- [ ] **#1 (Medium): the Lock text promises more than the lock does.** Same issue as #11 above.
  - [x] Corrected wording in the app and README.
  - [ ] Lock read-back (#11's first step): after the `tag-write-password` branch, which already detects the
    chip type.
- [x] **#2 (Medium): check that the signed APK was built from this source.** Build again in an
  independent environment (CI or the signing machine), compare, and make `sign-release.sh` refuse an
  unsigned APK whose SHA-256 doesn't match the expected one.
- [x] **#3 (Medium): bind a confirmed overwrite to the tag that was confirmed.** Carry its UID and
  existing text in the request, as `Lock` does, so a different tag gets a fresh "not empty" check.
- [x] **#4 (Medium): adb and the phone used for real seeds.** README: no real seed on a phone with USB or
  wireless debugging enabled or authorised; revoke authorisations first. The shared adb volume is kept;
  `.devcontainer/README.md` explains the risk and how to scope it per project.
- [x] **#5 (Medium-Low): stray spaces in a new password.** Refuse or confirm leading, trailing or repeated
  whitespace when the password is set, in the app and in `seedtag.py encrypt`.
- [x] **#6 (Medium-Low): the strength estimate over-credits typed BIP39 phrases.** Full 11 bits per word
  only for the generated passphrase; discount typed word phrases and flag runs of adjacent list indexes.
- [x] **#7 (Medium-Low): `seedtag.py` KDF fallback.** Also fall back on `UnsupportedAlgorithm`, test the
  vectors with every installed backend, and say which backend was used.
- [x] **#8 (Low): drop touches with `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`** (Android 10–11), at least on the
  confirm buttons, in the activity and in `GuardDialogWindow`. Still to try on an Android 10 or 11 phone.
- [x] **#9 (Low): zeroable keypad prefix.** Keep it in a `CharArray`, show bullets while words are
  hidden, and make the README say plainly what's left in memory.
- [x] **#10 (Low): `TagIo.perform` must never throw.** Final catch-all with a generic message; catch
  `OutOfMemoryError` around Argon2.
- [x] **#11 (Low): overwrite safety at the edges.**
  - [x] A format guard for Type 5 tags (MIFARE Classic isn't covered). Still to try on a Type 5 tag.
  - [x] A message that says an interrupted overwrite may have destroyed the old content.
- [ ] **#12 (Low): maintenance.**
  - [x] Bouncy Castle 1.78.1 → 1.86.
  - [x] Update AGP (8.7.3 → 9.5.1), Gradle (8.11.1 → 9.9.1), Kotlin (2.0.21 → 2.4.21), the Compose BOM
    (2024.12.01 → 2026.09.00), activity (→ 1.13.0) and lifecycle (→ 2.11.0); compileSdk 37, targetSdk 36
    (Play requires 36 for new apps and updates since 2026-08-31). Platform and build-tools in the
    Dockerfile updated, so a container rebuild. Still to test on the phone (test plan 1.34–1.35).
  - [ ] Bouncy Castle 1.86 → 1.87 (out 2026-11-11), once it has matured: not before mid-December 2026,
    and only if no problem has been reported against it. The Argon2 code zeroes its working memory
    after each run in 1.87; output is unchanged (checked by reading the 1.86 → 1.87 source diff of
    the classes the app uses). After updating: `make test` (the vectors decide), `make deps-metadata`,
    one back up and restore on the phone. Moot if the app gets its own Argon2id (dependency-reduction
    report, item 6).
  - [x] CI running `make test`, `make build` and `check-apk` (`.github/workflows/build.yml`).
  - [x] Split `Ui.kt` by screen, after `tag-write-password` is merged (it changes `Ui.kt` a lot). Done
    2026-11-26 (`6713c6d`): nine files by section, lines moved unchanged (`private` → `internal` where
    another file needs it); device row 7.64 passed.
  - [x] Move the tag-flow logic into a pure-Kotlin state machine with tests. Done before `tag-write-password`
    after all (board B16; decided 2026-11-22): that branch is re-applied onto it.
  - [x] Clarify the wrapper wording (8.14 jar, 8.11.1 distribution: both were right); remove the unused
    `Bip39.normalize(String)`.

## Expert review (open items from [the 2026-11-10 expert review](reports/2026-11-10-expert-review.md))

- [x] **#1 (Medium): the strength meter rates well-known weak passwords "Strong".** Only the generated
  passphrase earns "Strong"; always confirm a typed password. If an estimate stays, fold case before the
  BIP39 lookup, split on any non-letter and count repeats once. Add the report's examples to the tests.
- [x] **#2 (Medium): a generated passphrase is never checked against the written copy.** Leave Repeat
  empty and have the user type it back from paper; confirm before Generate replaces one.
- [ ] **#3 (Medium): `--expect` proves the build, not the source, and the container can push to GitHub.**
  - [x] Sign from a fresh clone on the signing machine, and review `git diff <last tag>..<new tag>` there
    (README).
  - [x] Signed release tags (`git tag -s`, key outside the container), checked by `sign-release.sh`.
  - [x] Make `--expect` mandatory, with an explicit `--no-expect`.
  - [x] Harden `release-tag.sh` and `sign-release.sh` after their review
    ([reports/2026-11-12-release-tag-review.md](reports/2026-11-12-release-tag-review.md)): all 17 findings,
    then the new ones from the two follow-up reviews
    ([reports/2026-11-12-release-tag-hardening-review.md](reports/2026-11-12-release-tag-hardening-review.md),
    [reports/2026-11-12-release-tag-followup-review.md](reports/2026-11-12-release-tag-followup-review.md)),
    and the review of the whole flow ([reports/2026-11-13-release-flow-review.md](reports/2026-11-13-release-flow-review.md)).
  - [x] Close the gaps a later security review found in `release-tag.sh`'s review step (branch
    `release-tag-review-gaps`, [the brief](reports/2026-11-13-release-tag-review-gaps-brief.md)): fixes 1–5 (wrapper jar checksum, submodules, invisible characters, a
    binary shown as text, raw tag names) and nits N1–N5 (script path, environment, config warnings, key
    files outside the clone, fsck on fetch).
  - [ ] The findings of the review of everything since 0.4.2
    ([reports/2026-11-13-post-0.4.2-review.md](reports/2026-11-13-post-0.4.2-review.md)): P1–P12 fixed
    (P7 as a note in RELEASING.md), P14 settled. P13 stays open by choice: marking no-break spaces would
    warn on ordinary text.
  - [ ] Set up the release clone on the host and a signing key outside the SSH agent, and tag and sign
    only there (README, "Signing a release"; `.devcontainer/README.md`, "What the container can reach on
    the host"). Pushing `main` from VS Code in the container stays. User action, on the host.
- [x] **#4 (Medium-Low): recovery needs third-party packages.**
  - [x] Catch `ImportError` in `seedtag.py` and say what to install; state the minimum Python version.
  - [x] README and Done screen: keep a copy of `seedtag.py` with the written password.
  - [x] Pure-Python decrypt-only fallback (Argon2id and AES-GCM), tested against the vectors.
- [x] **#5 (Low): the `cryptography` Argon2id backend is never tested.** CI step with a hash-pinned
  `cryptography` ≥ 44 that fails on a skipped backend; add 18- and 21-word vectors.
- [x] **#6 (Low): Argon2's 64 MiB on the Java heap.** `android:largeHeap="true"`, tried on an old phone.
- [x] **#7 (Low): screen timeout or Back wipes everything mid-entry.** Keep the screen on while secrets
  are shown, with the app's own idle timeout; map Back to the step's Back button.
- [x] **#8 (Low): an old app meeting a newer format.** "Written by a newer version" message, and treat any
  plausible SeedTag blob version as a backup in the overwrite warning, in the app and `seedtag.py`.
- [ ] **#9 (Low): Lock says "now permanently read-only" for a tag that was already not writable.**
  - [x] Separate result and message; the "can't be made read-only" message and the README say that Android
    usually only locks Type 1 and 2 tags.
  - [ ] Test Lock on a Type 4 and a Type 5 tag, and note the result in the README table.
- [x] **#10 (Low): no warning about adb.** Preflight item for USB / wireless debugging; listen for
  accessibility changes while in the foreground.
- [x] **#11 (Low): a BIP39 passphrase ("25th word") isn't stored.** Say so on the Words step and in the
  README; mention English phrases only.
- [x] **#12 (Low): late tag results can leave a scan armed with no prompt.** Close the prompt only when
  nothing is pending; ignore a second Encrypt while busy. Pin it down with the tag-flow state machine,
  extracted before `tag-write-password` is merged.
- [x] **#13 (Low): Kotlin and Python disagree on two password-variant edge cases.** Explicit whitespace
  list in both, two new `password-variants.tsv` rows, wrap the encoder failure in a `SeedTagException`.
- [x] **#14 (Info): an unreadable tag is treated as empty.** Optional Type 2 hardening: check the NDEF TLV
  length before writing without confirmation. Also turn reader mode on again after NFC is switched on from
  the quick-settings shade.
- [x] **#15 (Info): build checks.** `check-apk.sh` could reject `debuggable` / `testOnly` and extra
  components; check the two action SHAs by hand against their release tags.

## Expert board (open items from [the 2026-11-16 expert board review](reports/2026-11-16-expert-board-review.md))

Nothing was tested on a device; *(device)* marks items whose premise a device test must confirm first (the
report's tests N1–N12, A1–A3, H1).

### High

- [ ] **B1: one GitHub account is a first-time installer's only channel and only anchor.** Partly expert #3 again
  (the container's push credential stays, by decision); what's new is that release assets, notes and
  `release-cert.sha256` hang on it too. Check what the container's credential can do; add a `v*` tag ruleset and
  immutable releases; publish the certificate digest somewhere the account can't edit (F-Droid reproducible build,
  or a personal site).
- [x] **B4: the process outlives the wipe.** **High priority** (owner, 2026-11-24; moved up from Medium).
  - [x] Correct the README ("deletes everything", now around line 432 and in the About paragraph) and advise a
    force-stop or restart. *Security notes* already said so.
  - [x] Decide whether to end the process in `onStop`. Decided (2026-11-24): **no automatic end** (it would cut off
    a tag write, erase or lock in progress, or, once deferred until the tag is done, lose its result: *Backup
    written* and its fingerprint, the Verify result, Make read-only after a write; and every short trip out of the
    app would start over). Instead, option C below.
  - [x] **Option C: an explicit Close SeedTag**, ending the task and the process when the user chooses. Done
    2026-11-25 (`e272140`; device rows 7.52-7.56).
    - Where: on Done, on the Verify and Restore results, and in the top bar (always reachable).
    - A confirmation dialog first (**Close SeedTag** / **Stay**) that says exactly what happens: everything
      typed or decrypted is cleared, and SeedTag's process ends, so the copies the wipe can't zero (the
      password as text while Show is on, text already drawn, the Argon2id memory, copies of the AES key) go
      with it; what's on screen now is gone (say which: the fingerprint on Done, the Verify result), so note
      the fingerprint first; what it can't clear (copies outside SeedTag, such as the screen's graphics
      memory, and freed memory until it's reused), so restart the phone before it goes online.
    - Not while a tag is being read or written: the button is disabled, or the dialog says to wait.
    - Close the task first (`finishAndRemoveTask()`), then end the process, so Recents doesn't bring back an
      empty screen; nothing is saved for a restart.
    - Detailed explanations: an item in the checklist (*Learn more*), the About page, and the README (Using it
      safely, Recommended procedure: "tap Close SeedTag, then restart the phone"; Security notes, *What's wiped
      from memory*). Changelog entry, device rows (each place, the dialog's Stay and Close, Recents afterwards,
      disabled during tag I/O).

### Medium

- [ ] **B2: Lock probably leaves NTAG215/216 data pages writable** *(device: N1)*. Read the lock bytes back after
  every Lock and say "data locked" or "only marked read-only"; stop recommending 215/216 in the too-small hint and
  correct README:434-436 until then. Write the real dynamic lock bytes on 215/216 on a branch, with spare tags.
- [ ] **B3: an interrupted Lock leaves the tag half-locked and both messages are false** *(device: N3)*. Same
  read-back; finish the lock with raw WRITEs; reword "unchanged" and "SeedTag didn't change it". Do the read-back
  now, not after `tag-write-password` (it needs only READs).
- [ ] **B5: salt and nonce are a 28-byte covert channel `verify` can't detect.**
  - [x] Reword README step 5 (it rules out a weak key, not a hidden channel).
  - [ ] Derive salt and nonce from the secrets so `seedtag.py verify` can check them: options, trade-offs and what
    the fix needs in [the salt and nonce report](reports/2026-11-18-salt-nonce-channel.md). A format decision for
    the owner (the report recommends option C, after a review); own branch. The generated passphrase stays a way
    out for a malicious app even then (the report, "What it doesn't close").
  - The other places to hide data, around the blob and elsewhere on the tag, are under
    [Hidden data outside the blob](#hidden-data-outside-the-blob-outside-remarks-2026-11-20) below.
- [x] **B6: the app never has the user compare the words it encrypts.** A review step before Password, or at least
  "Restore once and compare every word" in the Done steps and the Verify checklist. Done 2026-11-26: Back up's
  step 4 *Check your words* (*They match* only with the words shown, *Fix a word*), and a Restore-and-compare item
  in Done's next steps; device rows 7.65-7.68 passed.
- [ ] **B7: no path to a second tag; retyping the generated passphrase scores "Weak".** "Write the same backup to
  another tag" after Done and Verify (ciphertext only); README: identical copies are fine. On branch
  `b7-copy-tag` (2026-11-26): *Write a copy to another tag* (`TagFlow.copyTo`, `Purpose.Copy`); device rows 7.80-7.83.
- [ ] **B8: one Base64 typo in `seedtag.py` looks like a wrong password.**
  - [x] Plain error messages; re-pad and accept `-_` (and an `en` prefix); accept a file; `py` on Windows; shared
    `blob-text.tsv`.
  - [ ] A non-secret check value of the text in the app (Done, Verify, Erase scan) and the tool, printed before
    the password is asked for.
- [ ] **B9: custody model and heir instructions not written down.** README "What the tag is for" with a loss
  table; two password copies, never with a tag; a printable heir sheet.
- [ ] **B10: `seedtag.py` long-term.**
  - [x] Drop `from __future__ import annotations`; catch any backend exception and fall back to the built-in code;
    `--builtin`; the `-I -S` hint in the failure message; tests run with `-W error` (`make test`, CI).
  - [ ] CI on the newest Python (needs `actions/setup-python`, pinned by SHA; with B37).
- [ ] **B11: the APK-to-tag binding is one person reading a run header.** Build-provenance attestation checked by
  `sign-release.sh`, or a host rebuild from a throwaway clone for `--expect`.
- [x] **B12: raw empty check and format guard skip non-NXP Type 2 tags** *(device: N4)*. Raw reads through `NfcA`.
  Confirmed on 2026-11-23 with an "NTAG213-compatible" tag (UID from 1Dh, Android offers only NfcA and Ndef): a
  12-word backup over a text record left 11 pages of the old text. All raw Type 2 access (empty check, format
  guard, size probe, clearing after the backup) now goes through `NfcA` (`Type2Raw`); Erase ends with the same
  zeroing and a raw check (`TagResult.Erased(rest)`); `Type2Memory.freePages` and the commands tested. Device rows
  7.28-7.31.
- [x] **B13: Erase over-promises.** "The tag's whole NDEF area was overwritten and read back"; erasing doesn't undo
  copies already read (move the funds if the password may be known); replace a backup by writing over it.
- [ ] **B14: reproducibility not shown since the toolchain bump.** Run CI on `develop`, compare with `make build`,
  record both hashes; repeat after every toolchain change.
- [x] **B15: tag results read live view-model fields.** Carry fingerprint, target and text in the request and
  result; `Failed` with `changedTag`, always shown; `resetErase()` and closing ConfirmErase / ConfirmLock in
  `wipeAll()`; a dialog priority queue; clear stale Done / Verified after Erase. On `b16-tag-flow-tests`:
  `Pending.fingerprint`, `TagRequest.Erase(target, text)`, `TagNotice.LateFailure`, `TagEffect.Emptied`, a late
  `Erased` resets the tab if its scan is the one erased, `wiped()` drops the Erase scan; *Still there?* sits on
  top of the dialog (`below` + `idle`) instead of a queue. A test per case in `TagFlowTest`; device rows 7.32-7.36.
- [x] **B16: no automated tests for the tag flow.** Pure helpers into `core` with tests, then the `TagFlow` reducer
  (the TODO lines on the state machine say both "before" and "after" the branch merge: pick one). Before, decided
  2026-11-22, on branch `b16-tag-flow-tests`.
  - [x] `TagModel.kt`: the request and result types, `OverwriteTarget.matches`, `EraseFiller`,
    `Type2Memory.wrapPage`, `TagContent.of`; `TagModelTest`.
  - [x] `BackAction.kt` for `back()` / `backStaysInApp`.
  - [x] `TagFlow.kt`: every purpose and result, stale results, staying armed, the claim from the NFC thread;
    `TagFlowTest`. Erase is no longer a parallel copy in the view model. Behaviour unchanged (B15 is next).
  - [ ] Not done: the idle timer with an injected clock. It's three `delay`s in `IdleWatch`, restarted by
    Compose on every touch; a clock-driven copy would be new code to get wrong, for little to test.
- [x] **B17: `tag-write-password` drift and design gaps.** Drop `7e99a92`, re-apply `c235d3c`, land the pure
  additions first; fresh-tap check before "Protected"; compare all of CFG0/CFG1; map a NAK after AUTH to "wrong
  key"; Erase aware of protection; README: the NTAG password goes over the air in the clear. Rebuilt on
  `b17-tag-write-password` from `develop` (the old branch stays as a reference until this merges):
  - [x] The pure additions: `TagKey`, `UltralightCAuth`, `openForgiving(withTagSecret)`, `TagKeyTest`,
    `tag-key-vectors.tsv` (+ `.license`), `seedtag.py tag-key` and its tests (Python 3.8 syntax), `FORMAT.md`
    section 6 (the derivation only).
  - [x] Chip identification and protection state from READs (and GET_VERSION once the size says NTAG), through
    `Type2Raw`; Protect / Remove protection as `TagFlow` requests with tests. **NTAG213/215/216 only for now**
    (owner, 2026-11-24), the Ultralight C later (below). Device-tested on an NXP NTAG215 (2026-11-25); the
    interruption rows (7.48) still open.
  - [x] **Chips that aren't genuine NXP** (owner, 2026-11-24): supported too, but a warning dialog first, before
    the password is set. A chip counts as genuine NXP only if its GET_VERSION reply is an NTAG21x's and its serial
    number starts with 04. The warning says what's different: another maker's chip, whose memory layout and
    password handling nobody documents; SeedTag sets the password only where it can check it works, but such a
    chip may ignore it, or handle it differently; use it for practice, not for the backup (as the README's buying
    advice says). Its settings pages come from the chip's own layout (CC size, page count), never assumed: e.g.
    the 46-page clones (UID 1D) have CFG0 at 29h like an NTAG213 but CFG1 `00 00 00 00` and one page more. Never
    write AUTH0 before a PWD_AUTH with the new password has worked in a fresh session, and say plainly when the
    chip doesn't enforce the password (the fresh-tap write check is refused or not). Done as layout recognition
    (`NtagIdentity.byLayout`, any maker, GET_VERSION last), one *NTAG21x lookalike* warning that never names the
    maker or model (owner, 2026-11-25), a readable PACK allowed and cleared on Remove; device-tested on the Feiju
    (UID 53) and Fudan FM11NT021 (UID 1D) tags, 2026-11-25.
  - [x] The design gaps: the fresh-tap check before "Protected" (a read without the key and a refused write), all
    of CFG0/CFG1 compared, a NAK after AUTH shown as "wrong password", a torn CFG0 write (mirror bits) caught and
    repaired, Erase and Make read-only aware of protection.
  - [x] Docs: README (what it protects and not; the NTAG password and the Ultralight C key go over the air in the
    clear when set; `tag-key`), `FORMAT.md` section 6's chip settings, changelog, device rows (N11).
  - Done 2026-11-25: every device row (7.42-7.51, 5.18) passed on an NXP NTAG215 and two lookalikes; 5.17's raw
    PWD_AUTH by hand is optional. The Ultralight C moved to its own item below.
- [ ] **B17 follow-up: MIFARE Ultralight C** (owner, 2026-11-24). The derivation (`TagKey.ultralightCKey`) and the 3DES
    handshake (`UltralightCAuth`) are in and tested; the app part waits for a real Ultralight C to test on (none
    yet: the tested tag was an NTAG213). Key pages 2Ch-2Fh, AUTH0 (2Ah) and AUTH1 (2Bh, 01h = writes only); the
    key goes over the air in the clear when it's set; old branch `c235d3c` has a first version.
- [ ] **B18: no key-loss, rotation or succession plan.** Write it (backups, spare certificate, `apksigner rotate`
  and minSdk); explicit signature-scheme flags; "If SeedTag is abandoned"; SECURITY.md, CONTRIBUTING.md.
- [ ] **B19: third-party licence texts not shipped.** `LICENSES/` (REUSE), an "Open-source licences" section in
  About, `THIRD-PARTY.md`, the GPL text reachable from About.

### Medium-Low and Low

- [ ] **B20: Erase edge cases** *(device: N5, N6)*.
  - [x] (a) Shared probe for `check()` and `inspect()`.
  - [ ] (b) Continue an interrupted erase (no message, raw length 0, same UID).
  - [x] (c) Wrap the final empty write.
  - [ ] (d) Erasing an Empty-scanned tag takes two rounds on current AOSP.
  - [ ] (e) Confirmed re-format for CC E1h without an NDEF TLV.
  - [x] (f) Clear the erase password on "Don't check the password".
  - [ ] On Type 2, a raw page check after the zero fill (from B13).
- [ ] **B21: no known-answer self-test** for Argon2id and AES-GCM before the first seal.
- [x] **B22: write down the format envelope** a v2 must keep; `FORMAT.md` and the vectors under 0BSD or CC0.
  `FORMAT.md` (0BSD, section 4 the envelope, v2 points listed but not decided), `.license` files for the three
  vector files, README and CLAUDE.md link to it; `kindOf`'s size limits tested. The board's "45-200 bytes" is
  wrong: 45 is the overhead, the smallest blob is 61.
- [ ] **B23: system screen readers and accessibility overlays are outside every warning.** Neutral notice when any
  service or touch exploration is on; README line on accessibility overlays.
- [x] **B24: adb can probably mirror secure windows on Android ≤ 11.** Say so in the debugging notice and README. Done: scrcpy's
  `ScreenCapture.createDisplay()` creates a *secure* display with shell permissions below API 31 ("Since Android 12
  (preview), secure displays could not be created with shell permissions anymore"); the notice adds it only there.
  Device row 7.41.
- [ ] **B25: Android 16 ignores the portrait lock on large screens** *(device: A2)*. `field` into the view model;
  veil on `onPause` without focus.
- [ ] **B26: seed words may be clipped at large font sizes** *(device: H1)*. Two columns when needed.
- [x] **B27: Restore and `decrypt` end at the words.** "BIP39 words only, no wallet passphrase or account"; a
  better checksum message for non-BIP39 phrases; "not your wallet's fingerprint".
- [ ] **B28: not ready for screen readers** *(device: A3)*. Content descriptions and roles, announcements, idle
  timer on accessibility actions, README "Accessibility".
- [x] **B29: settings links wipe secrets without warning, and two of them can crash.** Leave dialog while
  `holdsSecrets`; `runCatching` with `ACTION_SETTINGS` as fallback.
- [x] **B30: the typed-back passphrase check can be got around** (Show during Repeat; editing after "Show it
  again"). Done 2026-11-26: no Show and no editing on a generated passphrase (the view model ignores keys for
  it), *Show it again* clears Repeat, *Type my own password instead* behind a question; device rows 7.69-7.72
  passed.
- [ ] **B31: warning fatigue; idle timeout short for Restore.**
- [ ] **B32: a refused write reads as "moved away"; one bad read-back calls the tag faulty** *(device: N10)*. On `develop`
  (2026-11-26): after a failed write the tag is read again (`AfterWrite`): unchanged gives "didn't take", twice in a
  row "refuses writes"; a write that went through carries on. A bad read-back is read again before "faulty". Device
  rows 7.77-7.79.
- [ ] **B33: SDK, JDK and apt not pinned; AGP may download SDK packages.**
- [x] **B34: RELEASING.md:129-133 stale** ("0.4.3"; add the Dockerfile and the metadata `<configuration>` block).
- [ ] **B35: one Gradle cache volume shared by every project's container** (`gradle-cache-${devcontainerId}`).
- [ ] **B36: gaps in `check-apk.sh`, `check-workflows.py` and the metadata `<configuration>` check.**
- [ ] **B37: no CI on branch pushes** (tests-only workflow).
- [ ] **B38: reproducible only in this image; nothing durable recorded per release** (`keepDebugSymbols`,
  `.gitattributes`, unsigned hash and `seedtag.py` in the release, README verification section).
- [ ] **B39: licence headers and provenance** (`REUSE.toml`, missing headers, wordlist source and licence).
- [x] **B40: storage advice** (heat, wireless chargers, bending, refresh every ~5 years).
- [x] **B41: wording and numbers** (111/91 vs ~114/~94, step order in "Using it", one name for Lock, "Verified"
  for a different seed).
- [x] **B42: unexpected exceptions in the Argon2 paths crash the app** (one `runKdf` helper; map
  `GeneralSecurityException`).
- [ ] **B43: dialog guards applied late and to the root view only.**
- [x] **B44: branch hygiene.** Delete `toolchain-update` and `dependency-trim`; merge `main` into `develop`. Done:
  both deleted (fully merged); `main` merged with `develop`'s text kept (its only commit was already here as
  `6391600`).
- [x] **B45: "Wrong password, or the tag data is corrupted" is a dead end** (hints). `Dialog.WrongPassword` with
  the steps to check, non-BIP39 word positions (`Passphrase.nonWordPositions`, tested), Erase's own variant; the
  password keyboard runs into the gutters (`intoGutter`).
- [ ] **B46: small screens, contrast, English-only strings.**
- [ ] **B47: test gaps** (wordlist hash, `NdefText` paths, long and non-BMP password vectors, a differential test).
  - [x] The wordlist's SHA-256 pinned in both suites; `NdefText.decodePayload`'s UTF-16 (both byte orders) and
    malformed paths; vectors with a 128-character password and one outside the BMP that NFKD changes, checked
    by both suites (Bouncy Castle agrees with `seedtag.py`).
  - [ ] Optional: a live differential test (`make test-cross`: random entropy and passwords sealed by one side,
    opened by the other).
- [ ] **Info I1–I14:** see the report; none needs action before the items above.
- [ ] **Owner questions** (report, last section): 0.4.2 signed from a CI build with a matching hash? The
  container credential's scope; F-Droid; keystore backups and succession; Lock on unconfirmed chips; blocking
  "destroyed content" messages; a trademark check on the name.

## Hidden data outside the blob (outside remarks, 2026-11-20)

Same threat as B5: a poisoned build or library hides the seed on the tag, for whoever reads it later. The blob
itself needs nothing new: v1 has no optional fields, the Argon2 parameters are fixed by the version byte, and
`open` accepts only the five lengths the word count gives; the free bytes are the salt and nonce (B5). Padding
every blob to one length would hide the word count, which is privacy, not hiding: that's for a v2 (B22). What's
left is around the blob, where nothing checks it:

- [x] **H1: README step 5 says the check "rules out … extra data on the tag".** Another NFC app shows only the
  NDEF message, not the pages after it. Drop the claim, say what such an app shows and what it doesn't (linking
  to H4 is part of H5).
- [ ] **H2: nothing checks the record and text around the blob byte for byte.** Free today: spaces, `-_`, an
  `en` prefix and `=` in the text (accepted since B8); the 2 or 4 unused bits in the last Base64 character
  (12, 15, 21 and 24 words); the language code (up to 63 bytes), the UTF-16 flag, a record ID, and extra
  records (`firstText` takes the first Text record and ignores the rest).
  - [x] The app: the read-back after writing (`write`, `checkWritten`) compares the whole NDEF message with
    `NdefMessage(createTextRecord("en", toBase64(blob)))`, not only the text.
  - [x] The app: Verify and Restore say so when a tag's message isn't exactly what SeedTag writes
    (`NdefText.differences`, tested in `core`).
  - [ ] Both compare the message as Android parsed it and wrote it out again. A record header encoded
    differently (no short-record flag, a zero-length ID field, chunks) parses to the same message and only shows
    in the raw bytes: that's for H3 and H4.
  - [x] `seedtag.py verify` and `decrypt`: keep accepting lenient text, but say whether it's exactly what
    SeedTag writes (re-encode and compare), so a text copied straight off the tag can be checked.
  - [x] Tests in both suites (an `exact` column and two rows in `blob-text.tsv`; `SeedBox.isExactText`).
- [ ] **H3: Back up leaves the pages after the end marker as they were** *(device)*. Confirmed on 2026-11-21 with
  four Flipper `.nfc` dumps from NFC Tools (`other/tag-dumps/`, not committed): Android writes the TLV, the message
  and the terminator, pads that page with 00, and leaves the rest. A 12-word backup over a 24-word one keeps the
  old backup's last 18 characters after it (harmless: its salt and nonce are gone). Worse, a tag used with another
  app first keeps that data: one dump had the end of a plain-text BIP39 phrase from an earlier NFC Tools write
  after the backup. So even an honest tag fails an "everything else is zero" check, and Back up doesn't clear old
  plain text.
  - [x] Zero the pages after the terminator with raw page writes on every Back up and read them back, so a clean
    tag's dump is predictable (NTAG21x and Ultralight C, through `MifareUltralight`; `Type2Tlv.freeAfterTerminator`).
    *Clear old data* on Done finishes an interrupted clear; other tags get a notice there.
  - [x] For chips it can't do, say so: *Backup written* and the README. The Erase intro ("You don't have to erase
    an old backup to write a new one") stays true.
  - [x] Test plan rows (7.17-7.20).
  - [ ] Interrupted clearing, retried, on a tag whose capability container claims more memory than the chip has:
    the writes past the end fail every time. Rare (a corrupt CC); Cancel ends it, and the backup is fine.
- [ ] **H4: no check of the whole tag memory outside SeedTag.** A malicious build passes its own check, so this
  has to be `seedtag.py`, reading a full memory dump (no new dependency; a PC/SC reader would need `pyscard`).
  The format: the Flipper Zero `.nfc` file that NFC Tools exports; samples in `other/tag-dumps/`.
  - [x] `seedtag.py check <DUMP>` for NTAG213/215/216, page by page: every page read, the chip's version and
    serial check bytes, capability container, static and dynamic lock bytes (set: a warning), CFG0/CFG1 at the
    factory defaults (AUTH0 `FF`), PWD/PACK reading zero, the counters, the TLVs (only NXP's Lock Control TLV
    besides the message), the message exactly SeedTag's, zeros after the terminator. `decrypt` and `verify` take
    the dump too. Tests in `TagDump`.
  - [ ] Ultralight C: its 16-bit counter (only counts up), AUTH0/AUTH1, the unreadable key pages. Needs a dump of
    a real Ultralight C to see how NFC Tools writes it.
  - [ ] The NTAG21x PWD and PACK always read as zero (6 bytes nobody can check; PACK's 2 come back to whoever
    knows the PWD), and so does the Ultralight C's 3DES key. Find out whether NFC Tools' advanced commands can
    try the factory PWD (`1B FF FF FF FF`) or the Ultralight C factory key.
  - [ ] The NFC counter reads only when it's enabled; with ACCESS `00` it's off, and the dump's counters say 0
    whatever it holds. Check what NFC Tools writes for an enabled one.
  - [ ] Optional: verify NXP's originality signature from the dump, so a fake chip shows (NFC Tools already says
    "Valid (NXP Public Key)").
- [x] **H5: README: what a clean tag's dump looks like**, how to take one, and what stays unchecked (the key or
  PWD pages, other chips). Update the salt and nonce report's "What it doesn't close" point 2 to link here.
  - [x] How to take one (NFC Tools: OTHER, Read memory, Export .nfc) and what `check` can't see: README,
    *Recovering on a computer*.
  - [x] What a clean dump looks like, page by page, for someone checking without `seedtag.py` (NTAG213).
  - [x] The salt and nonce report's point 2.

## App and docs

- [x] Make the on-screen warnings more prominent.
- [x] Warn that if the encryption password is lost, there's no way to restore the seed phrase.
- [x] Warn users to read the on-screen instructions carefully.
- [x] Explain that users need to have their NFC tag ready beforehand, and to hold it against the back of
  the phone when the app asks.
- [x] Add haptic feedback.
- [x] Explain the verification procedure in more detail on screen.
- [x] Make Verify and Restore multi-step procedures like Back up:
  - remove the password field once it has done its job;
  - add "Verify another tag" and "Restore another tag" options;
  - etc.
- [x] Add an About page.
- [x] Add a separate option that helps users find their phone's NFC antenna beforehand.
- [x] Add a disclaimer, in the app and the README:
  - The app comes without any guarantees, and the developer is not responsible for any damage.
  - Users must make sure for themselves that no malware is already installed on their phone. The app
    can't cover every possible attack vector.
- [x] **Lock on a tag that can't be made read-only.** Show the message, but don't wait for another tag to be
  scanned: that doesn't make sense. (Today the scan stays armed with *This tag can't be made read-only from this
  phone…*, `TagIo.lock`.) Now `TagResult.CantMakeReadOnly`: a message (*Can't be made read-only*), the scan ends;
  tested in `TagFlowTest`, device row 7.38.
- [x] ***Erase this backup?* wording.** Say "Make sure you have the seed saved somewhere else", and drop the
  closing "Then hold the same tag to the phone." (`Ui.kt`, `Dialog.ConfirmErase`). Both backup variants; device row 7.39.
- [x] ***Safest on a spare phone* item.** Change its last paragraph to: "If you don't have a spare phone, you can
  use your everyday phone after making sure it's offline, but be aware that it is a less safe option. For more
  details, read the "Go offline first"" (`SPARE_PHONE_DETAILS` in `Ui.kt`), completed with "…, in the checklist on Back up,
  Verify and Restore."; device row 7.40.
- [x] **Words survive switching tabs.** In Back up, type all the words and the password, then cancel the write and
  go back twice: you can now switch between Back up, Verify, Restore and Erase without the words on the Back up
  tab ever being cleared. Leaving Back up with words typed now asks (*Clear the words?*, `Dialog.ConfirmTab`
  with `backup`) and clears them; device row 7.37.
