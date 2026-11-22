<!--
SPDX-License-Identifier: 0BSD
Copyright (C) 2026 zivke
-->

# SeedTag tag format

This file describes what SeedTag writes to an NFC tag, precisely enough to write an independent decoder. With
the test vectors in `core/src/test/resources/`, it's all you need to read a SeedTag backup without SeedTag.

Like `tools/seedtag.py`, this file and SeedTag's own vector files are under the BSD Zero Clause license (`0BSD`,
see [License](#license)), unlike the rest of the repository (GPL-3.0-or-later). Anyone can implement the format,
in any program and under any license.

## 1. On the tag

An NDEF message with exactly one record:

| Field | Value |
|---|---|
| Record header | `D1`: message begin and end, short record, TNF 1 (well-known) |
| Type length, payload length | `01`, 3 + the text's length |
| Type | `54` (`T`, a Text record) |
| Payload | `02 65 6E` (UTF-8, language code `en`), then the text |
| Text | the blob in standard Base64 (RFC 4648 §4: `A-Z a-z 0-9 + /`), with its `=` padding, nothing else |

On an NFC Forum Type 2 tag (NTAG213/215/216), the message sits in an NDEF TLV (`03`, length) followed by a
terminator TLV (`FE`). SeedTag zeroes the rest of the data area after the terminator's page. The README's
*What a clean NTAG213 looks like* shows every page.

## 2. Blob, format v1

```
blob    = 0x01 | salt[16] | nonce[12] | ciphertext[len(entropy)] | gcm_tag[16]
key     = Argon2id(password = UTF-8(NFKD(password)), salt = salt,
                   t = 3, m = 65536 KiB, p = 1, version 0x13, output 32 bytes, no secret, no associated data)
ct, tag = AES-256-GCM(key, nonce, plaintext = entropy, AAD = 0x01)
entropy = the BIP39 entropy, 16/20/24/28/32 bytes for 12/15/18/21/24 words
```

- Every parameter is fixed by the version byte; none is stored in the blob.
- The words are the entropy's BIP39 English words. Their checksum isn't stored: a decoder recomputes it from the
  entropy.
- `salt` and `nonce` come from a secure random generator (see the README, *Using it safely*, point 5, for what
  that means for checking a tag).
- Sizes: the blob is 61 to 77 bytes; the text 84 to 104 characters; the NDEF message 91 to 111 bytes.

The **fingerprint** shown by Verify and `seedtag.py verify` is the first 4 bytes of
`SHA-256("seedtag-fp" ‖ entropy)`, as 8 lower-case hex digits. It's computed from the entropy alone, so the same
seed always has the same fingerprint, whatever the password or tag.

## 3. Reading: what decoders accept

A writer writes exactly section 1. A reader may be more forgiving, so that a text that was copied, typed or
written by another app still opens. SeedTag and `seedtag.py` accept the same, checked against `blob-text.tsv`
(v0.4.2 and earlier only drop whitespace, see section 4):

- whitespace anywhere in the text, from this set: U+0009–000D, U+001C–001F, U+0020, U+0085, U+00A0, U+1680,
  U+2000–200A, U+2028, U+2029, U+202F, U+205F, U+3000;
- the language code `en` in front of the text, when it's followed by `A`;
- URL-safe Base64 (`-` and `_` for `+` and `/`), and missing or extra `=` at the end.

Both also say when the text isn't exactly what SeedTag writes (the `yes`/`no` column of `blob-text.tsv`), since a
different text means something other than SeedTag wrote it.

**Password retries.** If the password as typed doesn't open the blob, SeedTag and `seedtag.py` try up to two
variants, in this order, and say which one worked:

1. *Spaces*: whitespace (the set above) trimmed at both ends and collapsed to single U+0020 spaces;
2. *SpacesAndCase*: that, lower-cased code point by code point (simple lower case); U+0130 `İ` is left as it is.

A variant equal to an earlier one isn't tried. `password-variants.tsv` holds the exact results. Only the typed
password changes, never the format: the key is always derived as in section 2.

## 4. The envelope every future format keeps

SeedTag v0.4.2 and later, and `seedtag.py` of the same versions, recognise a backup by the following, without
decrypting it. A future format that keeps to all of it is reported by them as *a backup written by a newer
version of SeedTag* (update the app), and they warn before overwriting it as a backup. Anything else they call
*other data*:

1. The NDEF message's first record with TNF 1 and type `T` holds the text. (Write it as section 1: one record,
   UTF-8, `en`.)
2. The text is standard Base64 with `=` padding. v0.4.2 drops whitespace and decodes with Java's basic decoder,
   so it doesn't accept URL-safe characters.
3. The blob is 61 to 200 bytes. v1 blobs are 61 to 77 bytes; the upper limit leaves room for a later format with
   more fields, and rules out longer data that only happens to start with a version byte. (On an NTAG213 a blob
   can't exceed 96 bytes.)
4. Its first byte is the format version: `01` is v1, `02` to `0F` are reserved for later SeedTag formats, and
   any other value isn't a SeedTag backup. `seedtag.py` looks at this byte only.

Versions before v0.4.2 know only v1, and call any other first byte other data.

A new format gets the next version byte; parameters and layout never change under an existing one. Readers keep
accepting every earlier version: tags in the field depend on it.

### For a v2, not yet decided

Points raised by the expert review (board finding B22), to settle when a v2 is designed:

- Authenticate the whole header (everything before the ciphertext) as AAD, not only the version byte.
- Put a context string with the version into the key derivation, so that no v2 key can equal a v1 key for the
  same password and salt.
- Pad the entropy to 32 bytes, with its length inside the ciphertext, so that every blob has one size and the
  word count doesn't show. 78 bytes, 104 characters: it still fits an NTAG213.
- Derive salt and nonce from the secrets instead of drawing them at random, so that a checker can tell they
  hide nothing (board finding B5, `reports/2026-11-18-salt-nonce-channel.md`). That one may also stay a rule
  for writing v1.

## 5. Test vectors

Tab-separated, UTF-8, one case per line, in `core/src/test/resources/`. Text is given as hex of its UTF-8 bytes
where it might hold whitespace or non-ASCII characters. Both SeedTag's tests and `tools/test_seedtag.py` check
them.

- **`seedtag-v1-vectors.tsv`**: password (hex), entropy (hex), salt (hex), nonce (hex), the tag's text, fingerprint.
  Sealing the entropy with that password, salt and nonce gives exactly the text, and opening the text gives the
  entropy back. It covers every word count, non-ASCII and NFKD-changed passwords, a 128-character password and
  characters outside the BMP.
- **`blob-text.tsv`**: what the row is, the text (hex), the blob it decodes to (hex) or `error`, and `yes`/`no`
  for whether the text is exactly what SeedTag writes (`-` for errors).
- **`password-variants.tsv`**: the typed password (hex), then the variants tried, in order, as pairs of the
  variant's name and the password (hex). A row with only a password has no variants.
- `bip39-vectors.tsv` (entropy, words) is Trezor's BIP39 reference set, from python-mnemonic (MIT), not part of
  this license.

## License

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby
granted.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING
ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL,
DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE
USE OR PERFORMANCE OF THIS SOFTWARE.

This covers this file and `seedtag-v1-vectors.tsv`, `blob-text.tsv` and `password-variants.tsv`; each of those has
a `.license` file next to it saying so.
