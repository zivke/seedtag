# Salt and nonce as a hidden channel (board finding B5)

- **Date:** 2026-11-18
- **Status:** option C chosen by the owner and implemented on `develop` (2026-11-28; FORMAT.md section 2,
  `derived-vectors.tsv`). The outside review of the construction asked for below hasn't happened.
- **Source:** finding B5 of the [expert board review](2026-11-16-expert-board-review.md), and the discussion
  after it. The README wording (`b1d666e`) is done; the fix in the code is open (`TODO.md`, B5).

## The problem

A blob is `0x01 | salt[16] | nonce[12] | AES-256-GCM(entropy)`. A correct build of SeedTag takes salt and nonce
from the phone's random generator (`SeedBox.seal`, `SeedBox.kt:45-48`; `seedtag.py`'s `seal` uses `os.urandom`).

That is fine in normal use. It only matters if the app itself is malicious: a poisoned library (Bouncy Castle,
Compose, AndroidX…), or an APK built from changed source. On a phone that's never online, the tag is the only
thing such an app can send a secret out through, and the 28 random bytes are the place to hide it. 16 bytes are
the whole entropy of a 12-word seed. Encrypted for an attacker, they look exactly like random bytes, the blob
still decrypts everywhere, the fingerprint matches, and the tag still holds one Text record. Whoever later reads
the tag with the attacker's key has the seed, without the password.

README point 5 ("check a new tag on another computer") said that check rules out a poisoned library. It rules out
a weakened key and extra records on the tag, but not this: nothing can tell random bytes from a secret encrypted
to look random. The README now says so (`b1d666e`). Nor does it see the tag's memory outside the message (below,
"What it doesn't close", point 2).

## Why it isn't a simple fix

The only way to close it is for the app not to choose those bytes freely: compute them from the secrets, so that
`seedtag.py verify`, which has the secrets once it has decrypted the tag, can compute them too and catch a
mismatch. Then every byte of the blob is fixed by the seed and the password, and checkable.

`open` doesn't change: salt and nonce are read from the blob, as now. So:

- every existing tag still opens, in every version;
- a tag written this way opens in every older version of the app and `seedtag.py`;
- no new version byte is needed. It's a rule for writing v1 tags, not a new format.

It still changes how new tags are written, which `CLAUDE.md` treats as a format decision for the owner.

## Options

### A. The board's proposal: hash password and entropy

`salt ‖ nonce = SHA-256("seedtag-v1-sn" ‖ len ‖ NFKD(password) ‖ entropy)[:28]`

- **Against:** someone who already knows the seed (from another copy) can test passwords at SHA-256 speed against
  the salt, instead of one Argon2 run per guess. That matters if the same password protects anything else.
- **Against:** someone who holds the tag and knows part of the seed and part of the password can test guesses of
  both at SHA-256 speed.

### B. Salt from the entropy alone, nonce from the key (proposed in the discussion)

`salt = SHA-256("seedtag-v1-salt" ‖ entropy)[:16]`, `nonce = HMAC-SHA-256(key, "seedtag-v1-nonce")[:12]`

- **For:** the password isn't in the salt, so guessing it stays one Argon2 run per guess, even for someone who
  knows the seed.
- **For:** a repeated nonce is harmless. The same key only comes from the same seed and password, so the same nonce
  always encrypts the same plaintext, and AES-GCM never sees one nonce with two different messages.
- **Against, found while writing this up:** the salt becomes a fast check of the seed that needs no password.
  Someone who holds the tag and saw most of the seed (say 20 of 24 words) can test the missing words at SHA-256
  speed: about 10¹⁰ guesses per second on a GPU, so four missing words (about 2⁴⁴ guesses before the checksum)
  in under an hour. Today, without the password, the tag gives them nothing, and their best check is the
  blockchain: deriving addresses (PBKDF2 with 2048 rounds of HMAC-SHA-512, then the curve), roughly a thousand
  times slower. So option B as stated makes a stolen tag a much better tool against a partly seen seed.

### C. Like B, with a slow hash for the salt (recommended to review)

`salt = Argon2id(entropy, salt = "seedtag-v1-salt", same parameters as the key)[:16]`, nonce as in B.

- **For:** everything B has.
- **For:** each guess of a missing word costs one 64 MiB Argon2id run, slower than checking against the
  blockchain, so the tag is no better a check of a partly seen seed than the blockchain already is.
- **Against:** one more Argon2id run when writing (about 1-3 s on a phone), and in `verify` (about a minute with
  `seedtag.py`'s built-in Argon2id).

### Common cost of A, B and C

Two tags with the same seed and password become byte-identical, and with B and C the same seed gets the same salt
whatever the password. Copies can then be linked by their bytes. That's harmless: the fingerprint already links
your own copies, and identical copies are fine (board finding B7 wants them anyway).

## What it doesn't close

The earlier discussion said that with the fix "nothing in the blob is left for hiding a secret". That's true for
the blob. Two ways out remain for a malicious app:

1. **The generated passphrase.** It comes from the app's own random generator (`Passphrase.generate`), and nothing
   outside the app can tell a random 7-word passphrase from one the attacker can compute (from the salt, say). With
   that, the attacker who reads the tag has the password too. This is the passphrase the app recommends. Closing it
   means generating the passphrase outside the app: with dice and the BIP39 list, or with `seedtag.py` on another
   computer (a `passphrase` command would be new). The app would then need a way to accept a typed passphrase
   without calling it weak (board finding B7 has the same problem for a second tag).
2. **Tag memory outside the NDEF message**, such as pages after the terminator or lock and configuration bytes. An
   NDEF reader doesn't show those. A raw memory dump (NFC Tools' *Read memory*, or NXP TagInfo) does. *Update,
   2026-11-21:* Back up now zeroes the data area after the backup (`TODO.md`, H3), and `seedtag.py check` checks
   a dump of an NTAG213/215/216 page by page (H4; README, *Recovering on a computer*). What no dump shows: the
   chip's password and its reply (6 bytes), and the Ultralight C, which `check` doesn't support yet.

Neither is made worse by the fix, but the README shouldn't call the blob check complete while they're open.

## What the fix needs

1. **A decision:** A, B or C. The recommendation is C, after an outside review of the construction.
2. **`SeedBox.kt`:** `seal` computes salt and nonce instead of drawing them. Keep the deterministic test variant.
   Add a function that tells whether a decrypted blob's salt and nonce are the derived ones.
3. **`seedtag.py`:** the same derivation in `seal`, and `verify` (and `decrypt`) prints
   "salt and nonce derived ✓" or "random: written by an older version", with a non-zero exit code if they're
   neither the derived ones nor plausibly random, so a script can check.
4. **Vectors:** new rows in `seedtag-v1-vectors.tsv` with derived salt and nonce, checked by both test suites, for
   every word count. Existing rows stay, since old tags must keep opening.
5. **The app:** Verify could say the same as `verify`. Not required: the point is a check outside the app.
6. **Docs:** the README's format section and point 5, `CLAUDE.md`'s format section (a rule for writing, not a new
   version), the changelog.
7. **Tests on the phone:** back up a throwaway seed and check it with `seedtag.py verify`; an older tag still says
   "random"; two tags with the same seed and password are byte-identical.

## Where it fits

Its own branch, after the board's simpler items, not mixed with them. If the generated passphrase is to be made
checkable too (point 1 above), that's a second step and a change to the Back up screens, best decided together
with board finding B7.
