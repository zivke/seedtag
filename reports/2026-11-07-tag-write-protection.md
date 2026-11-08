# Write-protecting a tag instead of making it read-only

- **Date:** 2026-11-07
- **Follows:** finding #11 of [the security review](2026-11-07-security-review.md) (skimming and destruction)
- **Status:** the write password is implemented on `main` (2026-11-08, see [Implementation](#implementation)), but
  not yet tested on a device. The proper lock and the `FF 0F` rescue are not implemented.

## Implementation

Added on 2026-11-08, following the recommendation below.

- **Chips:** MIFARE Ultralight C and NTAG213/215/216, with the NTAG caveats below.
- **Key:** derived from the backup password, so there's nothing new to remember:
  `secret = HMAC-SHA256(AES key, "seedtag tag key v1")`. Ultralight C key = `secret[0:16]`; NTAG PWD =
  `secret[16:20]`, PACK = `secret[20:22]`. Blob format v1 is unchanged.
- **Where:** **Protect tag** and **Remove protection** on the Verify result, which also shows the chip and
  whether it's protected. Only the tag that was just verified.
- **Two taps.** The first stores the key or PWD/PACK and sets writes-only (Ultralight C AUTH1 = 1; NTAG PROT = 0,
  AUTHLIM = 0) while AUTH0 is still off, and reads the settings back. The second, in a new session,
  authenticates (3DES, or PWD_AUTH with PACK checked) and only then writes AUTH0 = 03h and reads it back.
  A failed authentication goes back to the first step. Each tap is safe to repeat.
- **Refusals:** Back up and **Make tag read-only** refuse a protected tag; a read-only tag can't be protected;
  locked settings are reported and left alone.
- **Make tag read-only** is unchanged. The `FF 0F` rescue isn't included.
- **Chip identification** uses READ only, plus GET_VERSION once the size already says NTAG, because an
  unknown command ends the session. A 144-byte tag is told apart by where READ wraps back to page 0
  (Ultralight C after 2Bh, NTAG213 after 2Ch, NTAG203 after 29h).
- **Changing the password:** remove the protection (old password), back up with the new password, verify,
  protect again.
- `seedtag.py tag-key` prints the key, PWD and PACK, for other NFC tools.

## Background

**Make tag read-only** calls Android's `Ndef.makeReadOnly()`. That locks the data pages but not the
chip's NXP-specific settings: the password or key, and the switch that turns on read protection.

- **NTAG21x:** Android never sets CFGLCK, so the settings stay writable.
- **MIFARE Ultralight C:** it depends on how the tag was formatted.
  - Without a Lock Control TLV, Android writes `FF 0F` to page 0x28. That freezes the AUTH0, AUTH1 and
    key lock bits *open*, permanently.
  - With NXP's TLV, Android writes `FF FF` and the settings are locked too.

On an affected tag, a stranger with a few seconds of access can set their own key and turn on read
protection, and no phone can read the backup again. Nothing leaks, but the backup is lost.

The question here is whether a write password can replace the read-only lock.

## Answer

Yes. Both chip families have a **write-only password mode**: writing needs the key, but anyone can read
without one. Phones, `seedtag.py` and NFC apps keep working, so the backup still outlives the app, and
the tag can be reused. Against the lockout attack it does the same job as a proper lock. How good it is
depends on the chip.

## MIFARE Ultralight C (tested hardware): good fit

The datasheet supports exactly this mode:

- **AUTH1 bit 0 = 1** means "write access restricted, read access allowed without authentication".
- **AUTH0** is the page where protection starts (valid values 03h–30h).
  - **03h** protects everything from the format header (page 3) to the end, including AUTH0, AUTH1
    and the key pages.
  - Without the key, nobody can change the settings, set a read password or erase the tag.
- **The key never goes over the air.** Authentication is a 3DES challenge-response with a 112-bit key,
  so a skimmer or eavesdropper gets nothing usable.
- **What's left open:** page 2's static lock bits are below AUTH0 and can't be protected.
  - A stranger could set them and make pages 3–15 permanently read-only.
  - Reads still work, so this is a nuisance, not lost data.

## NTAG213/215/216: possible, but weaker

The write password works on NTAG too. It just protects less than on Ultralight C.

- PROT=0 plus AUTH0=04h gives write-only protection, and that covers the settings pages too.
- But the password is **32 bits, sent in plain text** (PWD_AUTH) with every write. Anyone listening
  (up to about 1 m) during a write gets it, and can then carry out the lockout attack.
- Guessing it over the air can be stopped with AUTHLIM (a limit on failed attempts). But an attacker
  who uses up the attempts makes the tag refuse every password from then on. Reads keep working.

The three choices for an NTAG tag, worst to best:

1. **Android's lock only** (what the app does now): anyone can lock the owner out at any time, without
   having to overhear anything.
2. **Write password:** safe unless someone overhears one of the few writes.
3. **Proper lock** (see below): there's no secret to steal, so nothing can ever be changed.

For a backup that's written once, losing reuse costs little, so the proper lock is the better choice.

## What "proper lock" means

It's still a read-only lock, the same as **Make tag read-only** from the user's point of view: anyone
can read the tag and nobody can ever change it. The difference is what gets frozen:

- **The app's current lock** (`Ndef.makeReadOnly()`) freezes the data: the blob and the NDEF header.
  The settings pages stay writable.
- **The proper lock** also freezes the settings in their current state, with protection off. Nobody can
  then switch on read protection.
  - **NTAG:** the app sets CFGLCK. PWD and PACK stay writable afterwards, but that doesn't matter:
    a password has no effect while protection is frozen off.
  - **Ultralight C:** the app sets lock byte 3 bits 4–7 (counter, AUTH0, AUTH1, key) before calling
    `makeReadOnly()`.

## Where the key comes from

The app stores nothing on disk, so the key must either be re-creatable or never needed again.

1. **Derive it from the backup password** (recommended for a reusable tag).
   - Argon2 already runs when sealing. A separate 3DES key would be derived from its output, under its
     own label.
   - To overwrite the tag later, the user enters the old password, the same one needed to decrypt
     the old backup.
   - An eavesdropper gets no better shortcut than the blob already gives them.
   - Forgetting the password only makes the tag non-reusable. A forgotten password already makes that
     backup useless.
   - **Blob format v1 is unchanged.** The key lives in the chip's settings pages, outside the NDEF
     record.
2. **A random key that the app throws away.**
   - The tag becomes permanently unwritable, which is in effect a lock.
   - No secret is involved, but the tag can't be reused.
3. **A separate tag PIN.** Not recommended: one more thing to remember, and nothing gained over option 1.

## Changing the write password

On both chip families the write password can be changed as often as needed. Each change needs the
current one, and each family has one condition that ends it permanently.

**Ultralight C:**
- **How:** authenticate with the current 3DES key, then write the new key to pages 2Ch–2Fh.
- **When it stops being possible:** once the key's lock bit (lock byte 3, bit 7) is set, the key is
  fixed forever.
  - A tag Android locked with `FF FF` is already in that state, with protection frozen off.
  - So it can never get a write password. It's fully read-only already and doesn't need one.
- **Interrupted change:** the key takes four page writes.
  - A tag pulled away part-way leaves a key that's part old, part new. Only writes are affected; reads
    still work.
  - The pages are written in order, so there are only five possible old/new mixes. The app knows both
    keys and can try each one to recover.

**NTAG21x:**
- **How:** PWD_AUTH with the current password, then write the new 4-byte password. It fits in one page,
  so a change can't be left half-written.
- **CFGLCK doesn't block it:** the datasheet says PWD and PACK stay writable after CFGLCK.
- **When it stops being possible:** if AUTHLIM is set and the attempts get used up, the tag refuses every
  password, so the password can't be changed any more. Reads still work.

**In the app:** the key would be derived from the backup password. Writing a new backup with a new
password would unlock the tag with the old key and set the new one, in the same tap.

## Rescuing tags that are already locked

- **Ultralight C showing `FF 0F` on page 0x28:**
  - The lock bits for its settings can never be set now, but the settings can still be written.
  - The owner can therefore claim the tag first: set a key (option 2 is enough, since the data is
    locked anyway), AUTH1=1, AUTH0=03h. After that, nobody can turn on read protection.
  - This is the only fix for these tags.
- **Already-locked NTAG:** set CFGLCK (with AUTH0=FFh, so no password is involved).

## Pros and cons

Some cons apply to all of these approaches:
- Anyone can still read the ciphertext, the UID and the fact that it's a SeedTag. Only a strong password
  and keeping the tag out of reach help against that.
- Physical destruction isn't stopped by any of them; separate copies are.

### Android's lock only (current)

- **Pros:** already implemented, standard, one call.
- **Cons:** on NTAG, and on Ultralight C formatted without a Lock Control TLV, anyone can still lock the
  owner out. The UI says the tag is protected when it's only half protected.

### Proper lock (read-only, settings frozen)

- **Pros:**
  - No secret to steal, overhear or forget.
  - Nobody can change anything, ever.
  - Little code: a couple of extra page writes before `makeReadOnly()`.
- **Cons:**
  - **Irreversible.** The tag can't be reused or corrected. The decrypt check and the read-back before
    locking matter more.
  - The app must write lock bits itself, and it must identify the chip correctly (GET_VERSION on NTAG).
    A wrong bit is permanent, and testing it uses up tags.
  - Clones sold as NXP chips may put the settings elsewhere or ignore the bits. The read-back has to
    catch that.
  - It can't fix tags that were already locked with `FF 0F` (Ultralight C). Those need the write key
    below.

### Write password, Ultralight C (3DES key)

- **Pros:**
  - The key never goes over the air (112-bit challenge-response).
  - The tag can be reused, and the key can be changed.
  - Reads stay open, so `seedtag.py` and any reader still work.
  - The blob format is unchanged.
- **Cons:**
  - **Much more code:**
    - raw `transceive` commands;
    - 3DES mutual authentication via the platform's `DESede` cipher (no new dependencies);
    - NDEF pages written by hand after authenticating, because `Ndef` can't write to a protected tag;
    - chip detection and recovery from interrupted writes.

    More code means more ways to lose write access through a bug. Reads aren't affected.
  - **It looks writable.** The NDEF header still says write access is granted, so other NFC apps offer
    to write and then fail. The app's own **Locked** state also needs a new meaning.
  - **Reuse isn't guaranteed.** Page 2's static lock bits stay open. A stranger can set them and make
    pages 3–15 read-only forever. Reads are fine, but the tag can't be rewritten.
  - **The key depends on the old password.** Rewriting the tag needs the old backup password and one
    more Argon2 run. Without it, the tag can't be written, which is the same as locked.
  - **Only the app can change the tag.** `seedtag.py` doesn't talk to tags. A user without the app
    can still read, but can't rewrite.
  - **Security depends on the chip.** Clones may implement 3DES authentication badly or not at all, and
    only a test on the real tag shows that.
  - **Setup can be interrupted.** That's harmless if AUTH0 is written last. A torn key change needs the
    recovery described above.
  - **The tag can be claimed first.** If someone else set a key on a fresh tag first, the app can't take
    it over. The app has to check AUTH0 = 30h before using a tag.

### Write password, NTAG (32-bit PWD)

- **Pros:** reusable, reads open, the password can be changed, simpler than 3DES.
- **Cons:**
  - **Sent in plain text.** One overheard write (up to about 1 m) is enough for a full lockout.
  - **Only 32 bits.** Without AUTHLIM it can be guessed over the air with enough time at the tag. With
    AUTHLIM, an attacker can use up the attempts and block all future writes.
  - **A captured password checks guesses offline.** If it's derived from the backup password, an
    attacker who overheard it can test password guesses against it offline. That costs the same Argon2
    work as attacking the blob, so it's no weaker, but it must never use a cheaper derivation.
  - The same code and "looks writable" cons as Ultralight C.

### Key derived from the backup password

- **Pros:** nothing extra to remember or store; it naturally changes with each new backup.
- **Cons:** rewriting needs the old password; one extra Argon2 run per rewrite. On NTAG it adds the
  offline guessing check described above.

### Random key that the app throws away

- **Pros:** no secret anyone could ever know; the only fix for Ultralight C tags already locked with
  `FF 0F`.
- **Cons:** as irreversible as a lock, but with more code. On NTAG it brings no advantage over CFGLCK.

## Implementation risks

- **Write order**, in case the tag is pulled away mid-write:
  1. Write the key.
  2. Authenticate to test it. This works while protection is still off.
  3. Write AUTH1.
  4. Write AUTH0, last.

  Then check that a write without the key is refused. Nothing is protected until AUTH0 is written, so
  an interrupted run can simply be repeated.
- **"Ultralight C compatible" chips** from marketplaces may not implement authentication correctly.
  Only testing the real tags shows that.
- Everything here has to be tested on a phone with spare tags. Unit tests and a build can't cover it.

## Can the write password damage the tag?

Software can't physically destroy the chip. With the safeguards below, the backup can't become
unreadable either: the realistic worst case is a tag that can no longer be *rewritten*, which is
where a read-only tag ends up anyway.

### Why the backup stays readable

The write password only protects **writing** (AUTH1 = 1 on Ultralight C, PROT = 0 on NTAG). Every
phone, NFC app and `seedtag.py` can still read the tag, as now.

The one way to make the backup unreadable would be a bug that sets the opposite value, so reads are
protected too.

- **Safeguard:** after setting the password, read the tag again *without* the key, on a fresh
  connection. If that fails, remove the protection straight away (AUTH0 back to "off", using the key
  just set) and report an error.

### What could go wrong, and the answer to each

1. **The tag is pulled away during setup.** Most writes are a page (4 bytes) at a time, and only the
   lock bytes are protected against interrupted writes. An interrupted page write can leave that
   page with garbage in it.
   - **Ultralight C:** keep the write order above. Protection is either still off, or on with a key
     the app already knows, so it can always finish or undo the setup.
   - **NTAG:** the same idea. The password is a single page, written and tested while protection is
     still off; AUTH0 comes last.
2. **The tag is pulled away while changing the key.** On Ultralight C the key takes four page
   writes, so an interrupted change can leave a mix of old and new pages. The app knows both keys, so
   it can try the possible mixes and recover (see "Changing the write password"). A lost or torn key
   only ever affects writing, never reading.
3. **Failed-attempt limit (NTAG only).** If AUTHLIM is set and the attempts get used up, by a bug or
   an attacker, the tag refuses every password forever. It can never be written again, but it still
   reads fine.
   - Ultralight C has no such limit.
   - On NTAG it's a choice between a limit (anyone can use up the attempts and block future writes)
     and no limit (the 32-bit password can be guessed over the air). The backup stays readable either
     way.
4. **Permanent bits set by mistake.** The lock bits can only ever be set, never cleared, and one of
   them freezes the key forever.
   - **Safeguard:** in password mode the app never touches the lock bytes.
5. **Fake or "compatible" chips.** A clone might ignore AUTH1 and protect reads too, or implement
   authentication badly.
   - **Safeguard:** the read-back check above catches the read case. Test new tags with a throwaway
     phrase first, as the README already says.
6. **The key comes from the password.**
   - Forgetting the password makes the tag non-rewritable, but that backup is useless without the
     password anyway.
   - A later app version that derived the key differently couldn't rewrite older tags. The
     derivation needs a version and fixed test vectors, like the blob format.
7. **Interrupted data writes.** Pulling the tag away while the backup itself is being written can
   corrupt it until it's written again. That risk exists today too; the password doesn't change it.

### Compared with today

- **Read-only (current):** the tag can never be written again, and anyone can still lock the owner
  out (#11).
- **Write password:**
  - Only the key can write; nobody else can lock the owner out.
  - The worst outcome of a bug is "can't be rewritten", the read-only result anyway.
  - A torn page in the backup data is the one way to lose readability, and it exists today too.
- **Write wear:** setting the password adds a handful of page writes, negligible against the
  write endurance in the chips' datasheets.

### Before relying on it

- Test on spare tags, including deliberately pulling the tag away mid-setup, to check that recovery
  works.
- Add a "remove protection" option that undoes it with the key.

## Recommendation

- **Ultralight C:** make write protection, with a key derived from the password, the default instead of
  read-only. Keep the proper lock (settings frozen) as the option for "never change this tag". Add the
  `FF 0F` rescue for tags that are already locked.
- **NTAG:** proper lock only, CFGLCK included.
- **First step either way:** after locking, read the lock and settings bytes back and report them. This
  check is harmless and shows whether existing tags are affected.
- Build the feature on its own branch, then test it on the device before merging.

## Sources

- Android NFC stack: [NativeNfcTag.cpp](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/NfcNci/nci/jni/NativeNfcTag.cpp),
  [rw_t2t_ndef.cc](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/libnfc-nci/src/nfc/tags/rw_t2t_ndef.cc)
- [NXP MIFARE Ultralight C datasheet (MF0ICU2)](https://www.nxp.com/docs/en/data-sheet/MF0ICU2.pdf):
  section "Configuration for memory access via 3DES Authentication", Table 12 (AUTH1), lock byte table
- [NXP NTAG213/215/216 datasheet](https://www.nxp.com/docs/en/data-sheet/NTAG213_215_216.pdf):
  Table 11 (PROT, CFGLCK, AUTHLIM), PWD_AUTH
- [Wakdev: MIFARE Ultralight C](https://www.wakdev.com/en/knowledge-base/nfc-chips/nxp-mifare-ultralight-c.html)
- [NXP community: NTAG password and configuration pages](https://community.nxp.com/t5/NFC/Setting-the-password-and-configuration-pages-on-a-Tag/m-p/795228)
- [Hancke, "Practical Eavesdropping and Skimming Attacks on High-Frequency RFID Tokens" (2010)](https://www.rfidblog.org.uk/Hancke-JoCSSpecialRFIDJune2010.pdf)
