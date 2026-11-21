# SeedTag expert board review

- **Date:** 2026-11-16
- **Reviewed:** `develop` at `df24ec4` (Erase tab), plus branch `tag-write-password` where it touches the same code.
- **Board:** six seats, each run as a separate Claude Fable 5.2 agent and briefed to act as a long-time expert in its field:

  | Seat | Expertise |
  |---|---|
  | **CR** | Applied cryptography and format longevity |
  | **AN** | Android platform security |
  | **NF** | NFC / RFID hardware and firmware |
  | **SC** | Supply chain and release engineering |
  | **HF** | Human factors, self-custody and inheritance |
  | **AR** | Architecture, testing, licensing and governance |

  The coordinator (Claude Opus 5.5) wrote this report. It merged duplicate findings and recorded where seats disagree. It opened the cited lines of every finding rated Medium or higher, and of most Low ones, except where the claim is about a missing record (B14) or about chip or platform behaviour; the Basis column says which.
- **Scope:**
  - all of `core/`, `app/` and `tools/`; the build, CI, devcontainer, signing and release setup; the README, RELEASING, TODO and changelog;
  - the thirteen earlier reports, with their ticks checked against the code.
  - Five commits had not been reviewed before: the Erase tab (`df24ec4`), the icons (`f734964`), the toolchain bump (`6fc02a9`), the dependency trim (`3744f30`) and the `check-apk.sh` allowlist (`37d075e`).
- **What was run:**
  - `make test`: 44 Kotlin tests passed. 22 Python tests ran, all OK, with 2 skipped: the `cryptography` backend isn't installed here, and the slow all-built-in run needs `SEEDTAG_SLOW_TESTS=1`.
  - CR's throwaway probes of `seedtag.py`: the built-in path decrypted vector 1 at the real parameters in 51.7 s using 86 MiB, on Python 3.12.
  - HF ran `seedtag.py` with the Trezor `abandon … about` vector.
  - SC ran `sh -n` on the shell scripts and `tools/check-workflows.py`.
  - AR did a trial `git merge-tree` of `tag-write-password`.
  - AN read Compose UI 1.12.1 bytecode from the Gradle cache. AN and NF read AOSP sources (AccessibilityManagerService on Android 14 and 16; the NFC stack on `main`).
- **Nothing was tested on a device.** Every claim about chips, phones or Android versions is reasoning from code, AOSP sources or datasheets, and is marked *reasoned*.
- **Not accessed:** `other/`, any keystore, `.env`, the APK files, the project's git remotes. Only throwaway mnemonics were used.

## Verdict

No seat found anything Critical. The cipher, the blob format, the write path and the window hardening are all sound. The cryptography, Android and NFC seats would trust a real seed to SeedTag, and the human-factors seat would recommend it to a technical user, under conditions that mostly overlap. The supply-chain and architecture seats gave no such verdict; their findings are about the release path and maintenance, not the app's protection of a seed.

1. **Password:** the generated 7-word passphrase. It gives ≈77 bits. The KDF adds about 20, so the margin comes from the password.
2. **Before relying on a tag:**
   - Restore once and compare every word with the original.
   - Verify once with `python3 -I -S seedtag.py verify` on another computer.
3. **Redundancy:**
   - At least two tags plus a paper or metal copy.
   - The password written down twice, in different places, neither one kept with a tag.
4. **Phone:**
   - A spare phone, offline, on Android 14+ (12+ at least).
   - Debugging off and every adb authorisation revoked.
   - No accessibility service on, including the preinstalled screen reader.
   - Restarted or force-stopped after use, before it goes online.
5. **Tag:** a genuine NXP Ultralight C or NTAG213. **Make tag read-only** isn't relied on as tamper protection.
6. **Recovery kit:** a copy of `seedtag.py`, its SHA-256 and the format description, kept with the password.

HF's verdict for non-technical users: **yes for a technical user; for a non-technical relative, only if a technical person sets it up and writes an heir sheet, and never as the only backup.**

The new problems are outside the cipher:

- **Lock probably does less than the app says on NTAG215/216** (B2; NF read AOSP `main`, device test N1 decides), the very tags the app recommends when a tag is too small. An interrupted lock leaves a tag the app can't finish and misreports (B3). Both stay invisible until the lock bytes are read back. NF advises against deferring that read-back.
- **The GitHub account is both the only publication channel and the only trust anchor for a first-time installer**, and its credential is live inside the dev container (B1).
- **"Wiped" means the app dropped its references; the process stays alive** with the native text caches and Bouncy Castle's 64 MiB of Argon2 memory. The README says "Everything is dropped when the app is backgrounded" (B4).
- **Custody and recovery by someone else are under-designed.**
  - The app never has the user compare the words it encrypts (B6).
  - "Make two tags" has no path in the app. Retyping the generated passphrase for the second tag is scored "Weak" (B7).
  - An heir can't tell a Base64 typo from a wrong password (B8).
  - Nothing tells an heir what the tag is (B9).
- **Erase claims more than it proves**, and has some dead ends after a torn write (B13, B20).
- **Results don't carry their own context.** Cancel, the idle warning or `onStop` during tag I/O leave stale or wrong screens. The tag flow has no automated tests (B15, B16).

## Where the seats disagree

| Topic | Positions | Recommendation |
|---|---|---|
| Lock read-back timing | TODO and AR's roadmap: after `tag-write-password` and the state machine. NF: now, since it needs only READs and is the only way to see B2 and B3. | **NF.** A read-only check is small and independent of the branch. |
| Ending the process after the wipe (B4) | AN: kill it in `onStop`. The tag-flow findings (B3, B15a): a tag operation can be in flight then, and Done/Verify state is kept on purpose. | Fix the README now; end the process only when no tag I/O is pending, after deciding what Done should show. |
| Lock as a default step | README recommends it (procedure step 6). HF: make it optional, because it rules out erase, rewrite, refresh and the write password. NF: don't rely on it on NTAG215/216. | Make it optional, with the trade-offs listed, until the read-back exists. |
| TalkBack | HF's brief assumed TalkBack is blocked on Android 14+. HF found it isn't: only services that aren't accessibility tools are. AN: preinstalled screen readers are left out of the banner, so they speak the seed with no warning. | Both stand. Add a neutral notice whenever any accessibility service is on, and screen-reader labels (B23, B28). |
| Severity of the run-to-tag binding | Earlier reviews: Low, residual by design. SC: Medium, and fixable with provenance attestation or a host rebuild. | **Medium** (B11). |
| Deterministic salt and nonce (B5) vs. one ciphertext on several tags (B7) | CR's fix makes every copy of the same seed and password identical, so copies become linkable. HF wants identical copies anyway. | No conflict: identical copies are fine, and the owner already links them by fingerprint. |
| `from __future__ import annotations` | CR: removal planned after Python 3.13's end of life (PEP 749, from memory, not checked). | The fix is trivial, so do it regardless (B10). The timeline is unverified. |

## Earlier findings: where the tick is wrong or partial

| Earlier finding | Status on `develop` | Seat |
|---|---|---|
| 11-07 tag-protection report: "Android's lock freezes the data pages"; README:434-436 says the same | **Probably wrong for NTAG215/216**: AOSP `main` appears to write the dynamic lock bytes to the wrong page (B2; device test N1 decides) | NF |
| 11-07 #5 (accessibility), ticked | **Partial.** System services are filtered out of the banner. Dialog guards are set late and on the root view only. Test-plan 2.3.7 is still untested. | AN |
| 11-07 #10 / 11-10 #9 (unzeroed copies), documented | **The documentation is wrong.** The process survives the wipe (B4). | AN |
| 11-07 #6 (decrypt before writing) | Holds, but it is self-consistent only: it catches a transient fault, not a deterministic one in Bouncy Castle or ART on that phone (B21) | CR |
| README step 5, "verify on another computer … to rule that out" | **Overstated.** It rules out a weak key, not a hidden channel in salt and nonce (B5). | CR |
| Expert #2 (generated passphrase typed back) | Works. Two holes remain: **Show** during Repeat, and editing after "Show it again" (B30). | HF |
| Expert #4 (recovery without the app) | The tool side is done. The human side isn't: getting 104 Base64 characters onto a laptop, and the error messages (B8). Durability over 20 years is weaker than the tick suggests (B10). | CR, HF |
| Expert #8 (newer-format detection) | Correct, but it depends on an envelope no document commits to (B22) | CR |
| Expert #9 ("already read-only" message) | **Probably false after an interrupted lock** (B3; device test N3 decides) | NF |
| Expert #11 (BIP39 passphrase not stored) | **Partial.** Not said on the Restore result or in `seedtag.py decrypt`, which is where an heir meets it (B27). | HF |
| Expert #12 (late tag results), ticked | **Partial.** A late `Written` after `wipeAll()` loses the fingerprint, and a late `Failed` is dropped even when it reports destruction (B15). TODO says the state machine comes *before* the merge (expert #12) and *after* it (project #12). | AN, AR |
| Expert #14 / project #11 (raw empty check, format guard) | **Partial.** It skips non-NXP Type 2 tags (B12) and wasn't carried into Erase's `inspect()` (B20a). | NF, AR |
| Project #12 (CI) | Runs only on `v*` tags and by hand, so a Kotlin/Python parity break on `develop` goes unnoticed (B37) | AR |
| TODO security #7 (supply chain), ticked | **Partial.** apt and the JDK are unpinned, only two SDK files are hashed, and AGP can still download SDK packages (B33). | SC |
| Post-0.4.2 P7 ("runs one release late") | **Partial.** The signed v0.4.2 copy of `release-tag.sh` misses the distribution-checksum prompt, the metadata warning and the submodule, NUL and invisible-character checks. RELEASING.md:129-133 still says "0.4.3" and leaves out the Dockerfile (B34). | SC |
| Test plan 5.4-5.7 (CI hash equals the container's; signing with `--expect`) | Still ⬜ although `v0.4.2` was signed. Has the result just not been recorded? | SC |
| TODO "Make the on-screen warnings more prominent" | Overdone: static advice is as red as live danger (B31) | HF |

## Findings

**Basis.**
- *V*: the coordinator opened the cited lines and confirmed them.
- *v*: the seat confirmed it in code or with a probe.
- *r*: reasoned; it needs a device, a chip or a platform check.

Severity follows the seats, adjusted by the coordinator where seats overlapped.

### High

| # | Title | Where | Failure scenario | Fix | Seats | Basis |
|---|---|---|---|---|---|---|
| B1 | For a first-time installer, one GitHub account is both the publication channel and the trust anchor, and the container holds its credential. *Partly a re-raise:* expert #3 covered the container's push credential, and TODO records the owner's decision that pushing `main` from the container stays. What's new is that release assets, notes and `release-cert.sha256` hang on that same anchor for a new user. High only if the credential's scope reaches releases, which nobody checked (question 2). | `RELEASING.md:168-194`; `README.md:196-198`; `tools/release-cert.sha256`; `.devcontainer/README.md` | A compromised container (an auto-updating agent, an extension, any of about 330 build-time components) uploads an APK signed with another key, edits the SHA-256 in the release notes, and pushes a new `release-cert.sha256` and README. A new user sees everything agree. Existing installs are protected by Android's certificate check on update; new ones aren't. | Add a `v*` tag ruleset and immutable releases: these carry most of the fix, since a fine-grained token with Contents write can probably still edit releases (believed, not checked). Narrow or stop forwarding the container's credential as well. Publish the certificate digest somewhere the account can't edit (best: an F-Droid reproducible build; otherwise a personal site). Use `ssh-add -c`, or keep the agent away from the container. | SC | V: the VS Code credential helper and `SSH_AUTH_SOCK` are present in the container |

### Medium

| # | Title | Where | Failure scenario | Fix | Seats | Basis |
|---|---|---|---|---|---|---|
| B2 | Lock leaves most data pages of NTAG215/216 writable, although the app recommends those tags | `TagIo.kt:373` (`makeReadOnly`); `TagIo.kt:328` (too-small hint); `README.md:434-436` | Without a Lock Control TLV, AOSP writes the dynamic lock bytes at 80h (215) / DEh (216), not the real 82h / E2h. Pages 16 and up, 17 pages of a 24-word backup, stay writable with a plain WRITE, so anyone with a raw-command app can destroy the copy after the app called it "permanently read-only". Ultralight C, NTAG213 and EV1 aren't affected. | Read the lock bytes back after every Lock and report "data locked" or "only marked read-only". On 215/216, identified by GET_VERSION, write the real lock bytes first, on a branch tested with spare tags. Until then, say Lock is complete only on Ultralight C and NTAG213, and stop recommending 215/216 in the too-small hint. Device test N1. | NF | V for the code and the README claim; r for the AOSP lock position (AOSP `main` read, not run) |
| B3 | An interrupted Lock leaves the tag half-locked, and the app misreports it | `TagIo.kt:366-373`; `SeedTagViewModel.kt:658-666` | Pulled away after CC3 = 0Fh is written: `makeReadOnly` fails and the app says "Locking failed. The tag is unchanged" (false). The next tap gets `AlreadyReadOnly`: "SeedTag didn't change it" (false). AOSP refuses to lock a tag whose CC3 is already 0Fh, so nothing can finish the lock, and every data page stays raw-writable for good. | The read-back from B2. If CC3 = 0Fh and the lock bytes aren't set, finish with raw WRITEs (lock bits are OR-ed, so repeating is safe). Remember that a Lock was tried on this text, and reword both messages. Device test N3. | NF | V for the code; r for the chip and stack |
| B4 | The process outlives the wipe; the README says everything is dropped | `MainActivity.kt:250-255`; `README.md:592` | After `onStop` the app can sit as a cached process for days. It still holds the `String`s from `reveal()` (one per keystroke while Show is on), the generated passphrase, the native text-layout cache with every word drawn, Bouncy Castle 1.86's un-zeroed 64 MiB Argon2 memory, and copies of the AES key. A later root exploit, a forensic dump or an OEM's flash-backed "RAM Plus" can recover them. | Correct the README now and tell users to force-stop or restart. Ending the process (`finishAndRemoveTask()`, then `Process.killProcess(myPid())`) needs design first: a write, erase or lock may still be in flight at `onStop` (a torn write, see B3 and B15a), and it would also discard the Done fingerprint and `lockTarget`, which `wipeAll()` keeps on purpose. At most, end the process when no tag I/O is pending, and decide what Done and Verify should show afterwards. Doing so would make the Bouncy Castle 1.87 update less urgent. | AN, CR | V |
| B5 | Salt and nonce are a 28-byte covert channel that the independent `verify` can't detect | `SeedBox.kt:45-48`; `seedtag.py:359-360`; `README.md:263-267` | A poisoned library, RNG or build has no network, so the tag is its only way out. It sets `salt‖nonce` to the 16-byte entropy of a 12-word seed (or the password), encrypted under an attacker's key. The blob still decrypts everywhere and the fingerprint matches. Whoever later skims the tag with that key has the seed. | Derive both from the secrets, e.g. `SHA-256("seedtag-v1-sn" ‖ len ‖ NFKD(pw) ‖ entropy)[:28]`, and have `verify` re-derive and check them ("derived ✓" / "random: older tag"). `open` doesn't change, so no new version byte; needs vectors in both suites. **A proposal that needs design review, the owner's call under "do not change in place":** with salt and nonce a fast hash of password and entropy, anyone who knows the seed can test passwords at SHA-256 speed instead of Argon2 speed, which matters if the same password protects another seed's tag. CR had no second-opinion pass. Reword README step 5 now either way: it rules out a weak key, not a hidden channel. | CR | V for the code and README; r for the attack and the fix |
| B6 | The app never has the user compare the words it encrypts | `Ui.kt:1162-1166` (words hidden by default); `Ui.kt:1464-1473` (Done steps); `README.md:291` | Words are masked while typed and picked from neighbouring suggestion chips. A wrong but valid word passes the checksum about 1 time in 16 (12 words) or 1 in 256 (24 words). "Backup written" and Verify ✓ then both succeed on the wrong seed. Only Restore-and-compare catches it, and only the README asks for that. | Add a review step before Password: all words shown, plus "I've compared all N with my copy". At least put "Restore once and compare every word" in the Done steps and the Verify checklist. | HF | V for the code; r for the mis-tap rate |
| B7 | "Make at least two tags" has no path in the app, and retyping the generated passphrase scores "Weak" | `README.md:300`; `SeedTagViewModel.kt:561` (`startOver` wipes); `Passphrase.kt:41-42, 71-73` | For a second tag the user retypes the whole seed. Generating again gives each tag its own password. Typing the first tag's generated passphrase scores 7 × 6 = 42 bits, "Weak · can be cracked offline", with a confirmation dialog, for the passphrase the app itself recommended. | After Done and after Verify, offer "Write the same backup to another tag": it needs only the ciphertext already in `lockTarget`, no secrets, with the same read-back. That removes the need to retype the passphrase, so expert #1's rule (a typed phrase never earns "Strong") can stay. README: identical copies are fine, one password serves them all. | HF | V |
| B8 | Recovery from a laptop: one mistyped Base64 character reads as a wrong password | `seedtag.py:371-390, 657-659`; `README.md:534`; `SeedBox.kt:253-257` | One wrong but valid character gives "wrong password or corrupted blob". A wrong first character gives "written by a newer version (format 5)". A missing `=` gives Python's raw "Incorrect padding", although the app's Java decoder accepts it. Pasting with the record's `en` prefix gives "unknown format 7a". An heir can't tell a typo from a wrong password. | Show a short, non-secret check value of the Base64 text in the app (Done, Verify, the Erase scan) and have `seedtag.py` print it before asking for the password. Explain errors plainly (expected length, ends in `=`). Re-pad and accept `-_` in `_read_blob`. Accept a file. Mention `py seedtag.py` on Windows. Add a shared `blob-text.tsv` of accepted and rejected inputs. | HF, CR, AR | V for `validate=True`; v for the messages, from running them |
| B9 | The custody model and instructions for heirs aren't written down | `README.md:300-305, 569-574` ("heir" appears only at :331) | Tag plus password is 2-of-2. With one written password, losing that one place loses every tag at once. A tag is an unlabelled piece of plastic: nothing tells an heir what it is, which tool opens it, which wallet, or whether there is a 25th word. | Add a README section "What the tag is for", with a table of what happens if a tag, the password, the paper or a whole location is lost. Keep two copies of the password, never with a tag. Add a printable heir sheet: what this is, where the pieces are, `seedtag.py` and its SHA-256, the fingerprint, the wallet, the derivation path, the BIP39 passphrase (yes/no and where), the exact commands. Say it does nothing against coercion. | HF | V |
| B10 | Long-term risk in `seedtag.py`: a `__future__` import that may be removed, and only some backend errors are caught | `seedtag.py:49`; `seedtag.py:286-306, 341-352, 697` | If a future Python drops `from __future__ import annotations`, the recovery tool fails at import, before any fallback can run. If a future `cryptography` renames `lanes=` or `memory_cost=`, the user gets a traceback even though the built-in code would work. | Drop the import, and the annotations that need it. Catch `Exception` around each third-party backend and fall back to the built-in one with a note. Add `--builtin`. Put the `-I -S` hint in the failure message. Add a CI job on the newest Python with `-W error`. | CR | V for the import; v for the error handling (probe); r for the removal timeline |
| B11 | The binding between the signed APK and the tag is still one person reading a run header | `sign-release.sh:87-100, 165-172`; `README.md:86-91`; `build.yml` | Move the tag, let CI build it, move it back (or delete the real run). The commit recorded inside the APK is written by the build itself, so every scripted check passes unless the owner compares the header. | Either a build-provenance attestation in CI, checked by `sign-release.sh` with `gh attestation verify` (source = HEAD, tag ref, workflow, no self-hosted runner); or build the tag on the host in Docker from a throwaway clone and pass that hash to `--expect`, which also removes trust in GitHub's runners. | SC | V for the self-asserted commit check (`sign-release.sh:165-172`); r for the `gh` flags |
| B12 | The raw empty check and the format guard skip non-NXP Type 2 tags | `TagIo.kt:393, 411, 450` | AOSP adds `MifareUltralight` only when UID[0] = 04h. For "NTAG213-compatible" clones and ST or Infineon Type 2 tags, `type2HoldsData` and `looksNdefFormatted` return false, so a too-quick tap reported as empty is written or formatted without the overwrite prompt that expert #14 added. | Do the three raw reads with `NfcA.transceive(30h, page)`, gated on NfcA with SAK 00h. Device test N4. | NF | V (`MifareUltralight.get(tag) ?: return false`) |
| B13 | Erase over-promises: it doesn't undo copies already read, and the wording claims more than the code proves | `SeedTagViewModel.kt:680-684`; `Ui.kt:1525-1527`; `README.md:360-368` | "Nothing of what it held can be read from it any more" sounds like "this backup is gone". A ciphertext already read or skimmed still opens with the old password, so a user who erases because the password leaked feels safe. On Type 4 and 5, `maxSize` isn't traced; with a `maxSize` of 259-261 up to 3 bytes stay; wear-levelled memory may keep copies. A user changing the password may erase first and be left with no tag copy for a while. | Say "this tag's whole NDEF area was overwritten with zeros and read back". Add: "Erasing doesn't undo anything already read from the tag. If the password may be known too, move the funds to a new seed." And: "To replace a backup, write the new one over it; don't erase first." On Type 2, add a raw page check after the zero fill. | HF, CR, NF | V for the wording |
| B14 | Cross-machine reproducibility hasn't been shown since the toolchain bump | `README.md:221`; `6fc02a9`; test plan 5.4-5.7 | "Two clean builds identical" was on one machine. AGP 9.5.1, R8 and Kotlin 2.4.21 have no CI comparison on record. A mismatch would only show at release time, after the release commit; the gate fails closed. | Run the workflow on `develop` now, compare its hash with `make build` of the same commit, record both in the test plan, and repeat after every toolchain change. | SC | v (no record found) |
| B15 | Tag results read live view-model fields, so Cancel, idle or `onStop` mid-I/O give wrong or missing outcomes | `SeedTagViewModel.kt:609-610, 638-648, 674-685, 763-767, 788-804` | (a) `onStop` during a write (a call, the power button): `wipeAll()` clears `pendingFingerprint`, and the late `Written` sets `backupFingerprint = null`, so Done shows no fingerprint and Verify can't say "same seed". (b) A late `Failed` is dropped, even "the old content may already be gone". (c) A late `Erased` leaves the old check and the Erase button, so the next tap says "This isn't the tag you scanned". (d) After an erase, "Backup written" and "Verified · Same seed" still describe the now-empty tag. (e) `wipeAll()` leaves `eraseCheck` and `eraseVerified`, and an open ConfirmErase or ConfirmLock, so whoever picks up the phone hours later can answer it (still bound to the tag). (f) `idleWarning()` replaces any dialog, and a late result can replace "Still there?" while its timer runs, so the wipe comes with no visible warning. | Carry fingerprint, target and text in the request and the result, and have the handlers read only those. Give `Failed` a `changedTag` flag and always show those. In `wipeAll()`, call `resetErase()` and close ConfirmErase and ConfirmLock. Keep dialogs in a small priority queue, with StillThere on top, and restore the previous one after Keep. | AN, AR, HF | V for (a), (d), (e); v for the rest |
| B16 | The tag flow has no automated tests, and Erase was added to it as a parallel copy | `SeedTagViewModel.kt:596-693`; `TagIo.kt`; `app/src` has only `main` | 7 purposes × 11 results × 3 generation counters × about 14 coupled fields. A reducer test with a fake tag would have caught every case in B15 and B20. `inspect()` has already drifted from `check()` (B20a). | Move what needs no Android into `core` with tests now: `OverwriteTarget.matches`, `fillerFor`/`isFiller`, `wrapPage`, `contentOf`, `back()`/`backStaysInApp`, and the idle timer with an injected clock. Then a pure-Kotlin `TagFlow` reducer (roadmap below). | AR | V (`app/src` has only `main`) |
| B17 | `tag-write-password` has drifted, and has design gaps before it can merge | branch base `aecb0fa` (2026-11-08) | It is 109 commits behind `develop` and 2 ahead. A trial merge gives 27 conflict hunks (view model 7, TagIo 5, Ui 5, `seedtag.py` 3, docs 7). Tip `7e99a92` only changes headers and is already redundant. Erase knows nothing of protection, so erasing a protected tag would report "moved away … may already be gone". Design gaps: (a) the Ultralight C key goes over the air in the clear at setup; (b) "Protected" is reported from bytes read back in the authenticated session, while the design report asked for a read without the key on a fresh tap plus a refused write; (c) a torn CFG0 write on NTAG can switch on the UID or counter mirror, which corrupts every later read; (d) a wrong key shows as "moved away"; (e) the README must say the NTAG password goes over the air in the clear. | Drop `7e99a92` and re-apply `c235d3c` onto `develop`. Land the pure additions first (`TagKey.kt`, `UltralightCAuth.kt`, their tests and vectors, the `seedtag.py` part). Add the fresh-tap check, compare all of CFG0 and CFG1, map a NAK after AUTH to "wrong key". Make Erase protection-aware. No other view model or TagIo features until it is merged. | AR, NF | V (109 behind, 2 ahead); v for the conflicts and the branch code |
| B18 | No plan for losing, rotating or having the signing key stolen; no succession plan | `sign-release.sh:179-186`; `RELEASING.md:33-34`; no SECURITY.md, CONTRIBUTING.md or continuity section | **Key lost:** no update can install, and announcing a new certificate looks the same as an attack (B1). **Key stolen:** nothing is written down. v3 rotation lineage only helps on Android 9+, while minSdk is 26. **Maintainer gone:** a fork needs a new id and key, old APKs eventually stop installing, and `seedtag.py` becomes the only route. | Write the plan: two offline keystore backups, a spare certificate digest announced in a signed tag, `apksigner rotate` steps and their minSdk consequence. Pass explicit signature-scheme flags and check one signer with `--min-sdk-version 26`. Add a README section "If SeedTag is abandoned", archive each release with a third party, add SECURITY.md and CONTRIBUTING.md. | SC, AR | V for the missing files and the implicit scheme flags (`sign-release.sh:179-181`); r for the rest |
| B19 | Third-party licence texts and notices aren't shipped | `Ui.kt:1991-1996` (About); `README.md:632-643`; the repo has only `LICENSE` | The APK ships AndroidX, Compose, Kotlin and coroutines (Apache-2.0) and Bouncy Castle (MIT-style). Both licences require their text and notices with every copy. Neither About nor the README has them, and AGP normally strips `META-INF/LICENSE*` from the APK. The five vendored Material icons and `gradlew` are Apache-2.0 with no licence text in the repo. About can't show the GPL text (GPL §0, "Appropriate Legal Notices"). | Add `LICENSES/` (REUSE layout: Apache-2.0, the Bouncy Castle licence, GPL-3.0-or-later). Add an "Open-source licences" section to About from static text (no plugin needed), and a `THIRD-PARTY.md`. | AR | V for About; r for the APK contents (not opened) |

### Medium-Low and Low

| # | Title | Where | Failure scenario → fix | Seats | Basis |
|---|---|---|---|---|---|
| B20 | Erase edge cases | `TagIo.kt:238-299`; `SeedTagViewModel.kt:245, 731-736` | (a) `inspect()` lacks `check()`'s `looksNdefFormatted` guard, so a too-quick tap on a backup tag shows "blank, nothing to erase" and the user throws away a tag that still holds ciphertext. (b) A tear during the zero fill leaves TLV length 0, and the retry says "This isn't the tag you scanned" on the very tag. (c) The final `writeNdefMessage(EMPTY)` (`:293`) is outside the try. (d) Erasing an Empty-scanned tag takes two rounds on current AOSP. (e) CC E1h with no NDEF TLV gets stuck as "blank". (f) "Don't check the password" hides the field but keeps `erasePassword` in memory. → One shared probe for `check()` and `inspect()`. Treat "no message + raw length 0 + same UID" as an interrupted erase and continue. Wrap `:293`. Offer a confirmed re-format. Clear the password on the toggle. Device tests N5, N6. | AR, NF | V for (a), (c); v for the rest |
| B21 | Neither app nor tool checks Argon2 and AES against known answers | `SeedTagViewModel.kt:425-431, 456-461` | A deterministic fault in Bouncy Castle or ART on one phone passes the decrypt-before-write and every Verify on that phone. The tag then opens nowhere else. → Run a one-off Argon2id known-answer test at the v1 parameters, plus one for AES-GCM, before the first seal of a session, and refuse to write on a mismatch. Mandatory if the app ever gets its own Argon2id. | CR | v (absence) |
| B22 | The format envelope a v2 depends on isn't written down | `SeedBox.kt:234-250`; `seedtag.py:61, 372-376` | Old apps recognise "newer backup" only when the tag holds one Text record of Base64, first byte 0x02-0x0F, 45-200 bytes. Any other v2 shows as "other data", with a weaker overwrite warning. → Commit to that envelope in the README format section and CLAUDE.md. For v2: the whole header in the AAD, a KDF context string, entropy padded to 32 bytes. Put the format description and the vectors under 0BSD or CC0 (`FORMAT.md`), so independent decoders aren't in doubt about the GPL. | CR, AR | v |
| B23 | Preinstalled screen readers and accessibility overlays fall outside every warning | `MainActivity.kt:162-170` | With TalkBack or Select-to-Speak on, restored words, a shown password and every picked suggestion are spoken, and handed to a TTS engine that may be a third-party app. The banner filters system services out. `TYPE_ACCESSIBILITY_OVERLAY` isn't hidden and doesn't mark touches obscured. → Show a neutral notice whenever any service or touch exploration is on, at least on Words and Restore. Say in the README that overlay hiding doesn't cover accessibility overlays. | AN | v for the filter; r for the platform |
| B24 | On Android 11 and below, adb can probably mirror `FLAG_SECURE` windows | `Ui.kt:874-886`; README "Using it safely" | A computer once authorised could watch word entry live. → Add this to the debugging notice and the README; consider making it blocking below API 31. | AN | r |
| B25 | targetSdk 36: Android 16 ignores the portrait lock on large screens | `AndroidManifest.xml:45-51`; `Ui.kt:1316, 1351, 1720` | On a tablet or foldable, rotating resets `remember` state. `field` returns to Password, so a generated passphrase hidden for the type-back check shows again. Under a translucent activity the app is paused but not stopped, so secrets stay in memory and a tapped tag goes to the system. → Move `field` into the view model, veil the screen on `onPause` without focus, and test row 1.35 on a foldable. | AN | v for the code; r for the platform |
| B26 | Seed words may be clipped in the word grid at larger font sizes | `Ui.kt:1204-1227` | Three columns at 14sp monospace, `maxLines = 1`, fixed 38dp height. On a 360dp phone at font scale 1.3, 6-8-letter words lose their endings on Restore. → Switch to two columns when a word doesn't fit, never clip a seed word, and add a test-plan row. | HF | r |
| B27 | Restore and `decrypt` end at the words | `Ui.kt:1660-1671`; `seedtag.py:694`; `Errors.kt:15-16` | An heir enters the 24 words and sees a zero balance: a BIP39 passphrase or a non-default account was used. Electrum, SLIP-39 and Monero phrases get "words wrong or in the wrong order". → Add one line on the Restore result and in `decrypt`'s output: "BIP39 words only; a wallet passphrase or account isn't stored on the tag". Extend the checksum message. Add "not your wallet's fingerprint" next to the fingerprint. | HF, CR | v |
| B28 | Not ready for screen readers, and the README doesn't say so | `Ui.kt` (no `semantics` or `Role`); keys `⌫ ⇧ ⇪ ?123` | Function keys have glyph-only labels. The idle timer restarts only on `ACTION_DOWN`, which TalkBack activations may not send. → Add content descriptions and roles, announce results, restart the idle timer on accessibility actions, add "use headphones" advice and a README "Accessibility" paragraph, and do one TalkBack pass. | HF | v |
| B29 | Settings links wipe secrets without warning | `Ui.kt:473, 502, 883`; `MainActivity.kt:92, 94` | With 24 words typed, "Turn on NFC" or "Open settings" leaves the app and `onStop` wipes it all. `ACTION_NFC_SETTINGS` and `ACTION_ACCESSIBILITY_SETTINGS` are unguarded and crash on a trimmed ROM. → Ask with the existing Leave dialog while `holdsSecrets`, point to the quick-settings shade, and use `runCatching` with `ACTION_SETTINGS` as the fallback. | HF, AN | v |
| B30 | The typed-back passphrase check can be got around | `Ui.kt:1364, 1387, 1741`; `SeedTagViewModel.kt:223` | **Show** on Password during Repeat lets the user copy from the screen. After "Show it again" the Password field is active, so the next key edits the generated passphrase and silently drops its generated status. → Make the field read-only while it holds a generated passphrase, with an explicit Edit, and hide Show during Repeat. | HF | v |
| B31 | Warning fatigue; idle timeout short for Restore | `Ui.kt:853-927`; `SeedTagViewModel.kt:109-111` | Static advice is as red as live danger, so by the third visit the red gets skipped. Three minutes is short for stamping a steel plate. → Use red only for live conditions, shorten the checklist on Verify and Restore, and give Restore a longer window or a "Keep for 10 minutes" option. | HF | v |
| B32 | Wrong messages after a refused write or a bad read-back | `TagIo.kt:127-130, 187-193, 199, 281-286` | A tag that NAKs writes (lock bits, or a password set with another tool) gets "moved away … may already be gone" every time, although nothing changed. One bad read gets "may be faulty, don't use it", and the next tap then asks to overwrite the user's own new backup. → After a failed write, read raw and compare; if unchanged twice, say the tag refuses writes. Re-read once before calling a tag faulty. Device test N10. | NF | v |
| B33 | SDK, JDK and apt aren't pinned; AGP may download SDK packages | `Dockerfile:15-17, 43, 57` | A future AGP default build-tools gets installed silently, in CI too. aapt2 and apksigner, which `check-apk.sh` relies on, aren't hashed. → Make the SDK read-only and/or `android.builder.sdkDownload=false` (check that 9.5 still has it), hash the whole SDK tree, and use apt snapshots or a JDK tarball by SHA-256. | SC | v for the config; r for AGP's behaviour |
| B34 | The next release is reviewed by the 0.4.2 script, and the manual note is stale | `RELEASING.md:129-133` | Changes to the wrapper, the 2,690-line metadata XML and the Dockerfile hashes get no prompt. → Say "the first release after 0.4.2" and list the manual checks: `distributionSha256Sum` against gradle.org, the Dockerfile's base digest and tools SHA, the XML `<configuration>` block. | SC | V |
| B35 | One Gradle cache volume is shared by every project's container | `.devcontainer/devcontainer.json:42` | Another project writes `~/.gradle/init.d/x.gradle` or patches an unpacked distribution, and SeedTag's next build runs it with push credentials and adb at hand. → `gradle-cache-${devcontainerId}`, and correct the README: the problem is integrity, not confidentiality. | SC | V |
| B36 | Static checks have gaps | `tools/check-apk.sh:75-112`; `tools/check-workflows.py:37-58`; `gradle/verification-metadata.xml:3-12` | `check-apk.sh` doesn't catch `application android:name`, `debuggable`/`testOnly` given as a resource reference, new intent filters, `<queries>`, `sharedUserId`, `<permission>`, SDK levels, cleartext traffic, `android:process`, a missing `dataExtractionRules`, or new `lib/*.so`. `check-workflows.py` doesn't check `permissions`, `runs-on`, `container`/`services` or the triggers. Nothing enforces the metadata `<configuration>` block, so a `<trust group>` could hide in a diff. → Allowlist each of them. | SC, AN | v |
| B37 | No CI on branch pushes | `.github/workflows/build.yml:13-16` | A Kotlin/Python parity break is found at release time or never. → A tests-only workflow on push (`make test` plus the `cryptography` step, `contents: read`, no artifacts). | AR | v |
| B38 | Reproducible only inside this image; nothing is recorded after a release | `app/build.gradle.kts:43-45`; `build.yml:60-66` | An outside rebuilder with an NDK strips `libandroidx.graphics.path.so`. aapt2 is pinned for Linux only. There is no `.gitattributes eol=lf`. The unsigned build's hash expires with the CI artifact. Users have no "download and verify" instructions, and `seedtag.py` isn't a release asset. → `keepDebugSymbols += "**/*.so"`, `* text=auto eol=lf`, say "Linux/Docker only". Put the unsigned SHA-256 and the commit in the release notes, attach `seedtag.py` with its SHA-256, add a README verification section, and commit the tag-signing public key. | SC | v / r |
| B39 | Licence headers and provenance | 7 files Apache-2.0, 27 GPL, 1 0BSD; no header on `Makefile`, the build scripts, the manifest, resource XML, art or `.devcontainer/*` | README's "everything else is GPL" is wrong for `gradlew*`, the wrapper jar and the wordlist. The wordlist's upstream licence and source aren't recorded, although it is embedded in the 0BSD `seedtag.py`. → `REUSE.toml`, the missing headers, `reuse lint` in `make check`; record the wordlist's source and licence (not checked by the board), and say the built-in crypto was written from the RFCs. | AR | v |
| B40 | Long-term storage advice leaves out what physically kills tags | `README.md:300-302, 407-410, 472` | Retention falls steeply with heat. A wireless charger or an induction hob can destroy a tag. Paper stickers break when bent. A locked tag can never be rewritten to refresh its memory. → Store cool and dry, away from chargers; write and lock with the tag flat on the NFC spot; copy to a new tag every 5 years or so; prefer PVC or epoxy tags. Wear isn't a concern (an erase is about 6 write cycles), which is worth one line. | NF, HF | r |
| B41 | Inconsistent wording and numbers | `README.md:323-324` vs `:403-405`; "Using it" steps; `Ui.kt:1447, 1460, 1642-1645` | README says 111 / 91 bytes in one place and ~114 / ~94 in another (both are right, but the second includes the TLV). Lock is step 4 before Restore at step 5 in "Using it", the other way round from the Recommended procedure. Lock has three names. A different seed still gets the "Verified" heading. → Fix each one. | HF | v |
| B42 | Unexpected exceptions in the Argon2 paths crash the app | `SeedBox.kt:87`; `SeedTagViewModel.kt:417-454, 501-530, 695-724` | `open` maps only `AEADBadTagException`; each of the three launchers catches only `SeedTagException` and `OutOfMemoryError`, with different guards. → One `runKdf` helper with the busy guard, the wipe check on errors too, and a catch-all; map `GeneralSecurityException`. | CR, AR | v |
| B43 | Dialog guards are applied late, and to the root view only | `Ui.kt:939-958` | In a `DisposableEffect` after the window is shown, so there is a frame without overlay hiding. Sensitivity is set on `rootView` only. `as? DialogWindowProvider` fails silently. These dialogs carry the Overwrite, Erase and Lock confirmations. → Also set sensitivity and the touch filter on `view`, and make a failed cast loud. | AN | v |
| B44 | Branch hygiene | local branches; `main` | `toolchain-update` and `dependency-trim` are fully merged. `main` has `c950bc8`, a hand-edited cherry-pick of `6391600`, so the next `develop` → `main` merge conflicts in README and changelog. → Delete the two branches; merge `main` into `develop` now. | AR | v |
| B45 | "Wrong password, or the tag data is corrupted" is a dead end | `Errors.kt:32-33`; `Ui.kt:1241-1244, 1623, 1810-1817` | A stressed user types a masked 7-word passphrase on keys about 27dp wide and gets this, with no next step. → Hints: tap Show and compare; flag tokens that aren't BIP39 words (needs no secret); it may be another tag's password; try `seedtag.py`. Let the keyboards run edge to edge. | HF | v |
| B46 | Small screens, contrast, English only | `Ui.kt:190, 434-451, 1435, 1501-1508, 1621-1626`; `strings.xml` | The fixed bottom area on Erase's password step is about 470dp, so on a 640dp phone the explanation is nearly hidden. The preflight list scrolls in an 80-150dp slot. The light theme's "fair" colour `#B06A00` on `#F8F8F6` is about 4.0:1 at 12sp, under 4.5:1. All strings are hard-coded in Kotlin (`strings.xml` holds only `app_name`). → Drop the tab row while a keyboard is up, darken "fair", move strings to resources before any translation. | HF | r |
| B47 | Test gaps in `core` and `seedtag.py` | `core/src/test/`; `tools/test_seedtag.py` | No test pins the wordlist's SHA-256 (Python only compares its copy with the app's). `NdefText.decodePayload`'s UTF-16 and malformed paths are untested. No vector has a 128-character or non-BMP password. No live Kotlin↔Python differential test; the golden files are the only cross-check. → Assert the hash in both suites, add the NdefText cases and vectors, optionally a `make test-cross` with random blobs both ways. | AR | v |

### Info

| # | Note | Seats |
|---|---|---|
| I1 | Argon2id margin, rough and not measured: about 10³ guesses per second per high-end GPU. 77 bits holds for decades, and `MIN_BITS = 60` is the right floor. For a v2, consider ≥ 256 MiB off the Java heap. | CR |
| I2 | AES-GCM doesn't commit to its key. With ≤ 32 bytes of ciphertext, a crafted multi-password blob gains 2-3 guesses per victim tap, each costing an Argon2 run. For v2, add a key-check value. | CR |
| I3 | The blob length reveals the word count, and the `AQ` prefix marks a SeedTag tag. The NDEF wrapper isn't authenticated, which is harmless. Pad in v2. | CR |
| I4 | `seedtag.py encrypt` accepts passwords the app can't type (longer than 128 characters, or outside its keyboard); NFKD may differ across Unicode versions for very new characters. Have it warn. | CR |
| I5 | The 32-bit fingerprint leaks nothing useful about an unknown seed. It stays on screen after `wipeAll()`, by design. | CR, AN |
| I6 | `singleTop` with `taskAffinity=""` lets a second instance start inside a caller's task (Back-button phishing); consider `singleTask`. Dialogs don't get `FLAG_ALT_FOCUSABLE_IM` (harmless with no editor). Immersive mode hides the cast and recording chip. Key haptics and the pressed-key ripple are a keystroke side channel. Check once that the `Arrays.fill` calls survive R8. | AN |
| I7 | Formatting a blank tag writes the one-time-programmable CC page, so if AOSP picks the wrong size the tag keeps it. On NTAG213, MIRROR_CONF can corrupt reads after Lock (another reason for CFGLCK). From Android 15 the NFC stack is a Mainline module and can change with an update. NFC-B tags with a random PUPI loop on overwrite and erase. | NF |
| I8 | Same-tap read-back: AOSP really re-reads the data pages but keeps the TLV length it cached. Add a raw TLV check after the NDEF read-back on Type 2. | NF |
| I9 | `gradlePluginPortal()` is probably unnecessary. `kotlinx.coroutines` is used directly but arrives only transitively, so declare it. UI strings live in TagIo and the view model; return typed reasons instead. | SC, AR |
| I10 | The test-plan step "switch tab while an Erase scan waits" can't be done: the scan prompt is modal. | AR |
| I11 | Copyright in AI-assisted code is unsettled in several jurisdictions, and copyleft rests on copyright; record human review in CONTRIBUTING.md. Is "SeedTag" clear of trademarks? AR has an unverified memory of an unrelated company using the name, and a rename is cheap at 0.4.x. | AR |
| I12 | Dead-store elimination and memory hygiene on the JVM: zeroing removes the long-lived copies, not stale GC copies, the `SecretKeySpec` copy, Compose's strings or Bouncy Castle's 64 MiB. The README says so honestly, apart from B4. Don't swap in a Java AES. | CR, AN |
| I13 | `seedtag.py` for a reader in 2040: `encrypt` reads 24 words blind, `decrypt` leaves the words in the scrollback (say "clear the terminal"), and a 707-line printout isn't realistically retypeable, so keep the file on two media with its SHA-256 on the heir sheet. A 2026 APK may not install on a 2040 Android. Practitioner additions: a screen lock on the spare phone; a password-free yearly "still reads, unchanged" check using B8's check value, with a full Verify less often; a pointer to multisig or SLIP-39 for larger holdings, and one sentence on when not to use SeedTag. | HF |
| I14 | Capacity: a 24-word message is 111 bytes (114 with TLV and terminator). Ultralight C and NTAG213 report 137 (26 spare). EV1 MF0UL21 and NTAG212 give 121 with a Lock Control TLV (10 spare). Too small: Ultralight, NTAG210, MF0UL11 (46), Topaz 96, ICODE SLIX (about 106, 18 words at most), ST25TV512. The app refuses cleanly before writing (`TagIo.kt:153, 171`). Reader-mode flags, the 250 ms presence check and the default timeouts are right. | NF |

## Done well (all seats)

- **Format:**
  - KDF parameters are fixed by the version byte, so a crafted tag can't downgrade them or exhaust memory; the length is checked before the KDF.
  - Salt and nonce are fresh for every seal, the version byte is in the AAD, and a wrong password and corruption give the same error.
  - `openForgiving` tries at most three derivations and gives an attacker nothing.
- **Passphrase:** `SecureRandom.nextInt(2048)` has no modulo bias, and "≈77 bits" is honest. Only an untouched generated phrase is called strong.
- **Recovery code:** the built-in Argon2id and AES-GCM in `seedtag.py` are correct against RFC 9106 and SP 800-38D (read line by line), and the tag is compared before any plaintext is produced. CI runs the slow test and requires the `cryptography` backend.
- **Window:**
  - `FLAG_SECURE` is set before `super.onCreate`.
  - Reader mode runs for the whole foreground time.
  - There are no text fields, and `FLAG_ALT_FOCUSABLE_IM` is set.
  - Autofill and content capture are excluded.
  - Obscured-touch filtering cancels a gesture already in progress.
  - Nothing goes into saved state.
- **Concurrency:**
  - The pending slot is taken with `getAndSet(null)`, and the `wipes`, `scanId` and `eraseSession` counters discard late results.
  - No thread race was found.
  - Overwrite, Erase and Lock are bound to the tag's UID and content, and no path writes to a tag the user didn't confirm.
- **Tag I/O:**
  - Decrypt, write, then read back.
  - A torn overwrite recovers by itself.
  - The max-size zero fill is the right way to erase through the NDEF API.
  - The blank-tag size probe can't hit an out-of-range page.
  - The capacity checks refuse cleanly before writing.
- **Supply chain:**
  - The signing key and the tag key are outside the container.
  - CI holds no secrets and no caches, and every download is pinned; the wrapper, distribution and action pins were checked and match upstream (checked from inside the container, the vantage point under review, so worth one re-check from the host).
  - The dependency trim and the one-component allowlist turn "a library added something" into a build failure.
  - The docs are honest about what `--expect` doesn't prove.
- **Use:**
  - Back up scans the tag first.
  - The generated passphrase must be typed back from paper.
  - A failed scan stays armed.
  - The guards on irreversible steps are proportionate.
  - Lock is reachable only from a Verify result.
  - The strength wording is honest, and the MIFARE Classic warning is there.
  - A disciplined changelog, TODO and report trail.

## Device tests to add to the manual test plan

From NF, plus a few from AN and HF. All use throwaway seeds; ⚠ uses up a tag.

| # | Tag / phone | Steps | Expected / what to record |
|---|---|---|---|
| N1 ⚠ | NTAG215, NTAG216 | Back up, Verify, Lock; dump the memory with NXP TagInfo before and after; then a raw `A2 20 00 00 00 00` | If B2 holds: static lock bytes FF FF, CC3 0F, the wrong pages FF, the real lock page still 00, and the raw write succeeds. Record the result in the README table. |
| N2 ⚠ | Ultralight C formatted by SeedTag, NTAG213 | Same as N1, raw writes to pages 10h and 20h | Both refused. Does page 28h read FF FF or FF 0F? That decides whether the 11-07 read-lockout applies to the tested hardware. |
| N3 ⚠ | Ultralight C or NTAG213 | Lock, pulling the tag away as fast as possible, until a first tap fails; tap again; dump | B3: the second tap says "Already read-only", and the dump shows CC3 0F with the lock bytes 00 |
| N4 | A non-NXP "NTAG213-compatible" tag | Check the tech list; back up over existing data with very quick taps, 20 times | No MifareUltralight in the list; count writes made without the overwrite prompt (B12) |
| N5 | Ultralight C, NTAG213, NTAG216 | Erase a backup; full dump | Data area all 00 apart from the TLVs |
| N6 | NTAG213 (the tested tag) | Erase, pulling away during the zero fill; tap again; Cancel, rescan, Erase; also erase an Empty-scanned tag and count the taps | The messages from B20b and B20d; the phone and Android version |
| N7 | NTAG213 (the tested tag) | Back up to an empty formatted tag and to one holding data; count taps to "Backup written" | Whether this phone reads back on the same tap (I8) |
| N8 | Blank EV1 MF0UL21, blank Ultralight C | Check (note the estimate), Back up, Check; dump pages 3-5 | The estimate equals `maxSize`; CC2 and Lock Control TLV as expected (I7) |
| N9 | NTAG 424 DNA or DESFire EV3; ICODE SLIX2 | Back up, Verify, Lock, Erase; dump after Erase | Lock refused; no old bytes left; closes expert #9's open box |
| N10 | NTAG213 with a password set by another tool | Back up over it; Erase it | Today "moved away … may already be gone", with the tag unchanged (B32) |
| N11 ⚠ | (branch) Ultralight C, NTAG213 | After Protect, read and raw-write on a fresh tap with another app; tear the second tap on NTAG and dump CFG0 | Reads open, writes refused, MIRROR bytes unchanged (B17) |
| N12 ⚠ | A spare backup tag | Leave it under a phone on a wireless charger for one charge, then Verify | Whether it survives (B40) |
| A1 | Any phone | Back up, then Home during the write; return | Done without a fingerprint (B15a) |
| A2 | Android 16 tablet or foldable | Generate, hide it for the type-back check, rotate or unfold | Is the passphrase shown again? (B25) |
| A3 | Android 14+ with TalkBack | Restore | What is spoken; does "Still there?" fire mid-task? (B23, B28) |
| H1 | Narrowest phone, largest font | Restore a 24-word backup with 8-letter words | No word clipped (B26) |

## Suggested order of work

1. **Docs and wording, no code risk:**
   - README step 5 (B5), the "Everything is dropped" sentence (B4), the Lock claims and the 215/216 recommendation (B2), Erase wording (B13).
   - BIP39-passphrase lines (B27), the custody section and heir sheet (B9), storage advice (B40), numbers and step order (B41).
   - RELEASING's 0.4.2 note (B34).
   - Decide on ending the process after the wipe, with the constraint in B4.
2. **Release safety before the next release:**
   - Record a CI vs. container hash on `develop` (B14).
   - Narrow the container's GitHub credential and add a `v*` ruleset (B1).
   - Write the key plan (B18).
   - Add `LICENSES/` and the About licences (B19).
3. **Small code fixes on `develop`:**
   - Lock read-back with corrected messages (B2, B3).
   - `seedtag.py` robustness: drop the `__future__` import, catch backend errors, lenient Base64, the check value (B10, B8).
   - Clear Erase state in `wipeAll()` and keep the context in the request (B15a, B15e).
   - The shared probe in `inspect()` (B20a); `NfcA` raw reads (B12).
   - Push-triggered test CI (B37).
4. **Custody features:** "Write the same backup to another tag" (B7), the word review step (B6), the check value in the app (B8).
5. **Format:** deterministic salt and nonce with vectors in both suites (B5), the envelope written down and `FORMAT.md` under 0BSD (B22), a known-answer self-test (B21).
6. **`tag-write-password`:** shrink it, close the design gaps, make Erase aware of protection, then merge (B17).
7. **Structure:** the `TagFlow` reducer and pure helpers in `core` with tests (B16), which also covers the rest of B15, B20 and B42. Then split `Ui.kt`.
8. **Standing policy:**
   - Toolchain updates on their own branch, gated by a reproducible-build comparison.
   - Decide once between an own Argon2id and Bouncy Castle 1.87.

## Questions for the owner

1. Was 0.4.2 signed from the CI APK, with a hash equal to the container's? Test plan 5.4-5.7 are unticked.
2. What can the forwarded GitHub credential do (a full OAuth token or one repo)? Is there 2FA with a hardware key, a `v*` ruleset, immutable releases?
3. Is F-Droid with reproducible-build verification wanted? It is the cheapest second anchor for B1.
4. Keystore: how many backups, and where? Is there a successor or key escrow?
5. Should Lock stay offered on chips where the app can't confirm the lock bytes, or only on chips it has identified and read back?
6. Should a "destroyed content" message (B15b) block until acknowledged?
7. Trademark check on the name "SeedTag" before the app is widely installed?
