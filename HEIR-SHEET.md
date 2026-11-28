<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright (C) 2026 zivke -->

# SeedTag: for whoever has to recover this

*Print this page and fill it in by hand. It holds no secret: **never write the password or the seed's words on
it.** It does say where the pieces are, so keep it where your heirs will find it and strangers won't (with your
will, or with someone you trust).*

## 1. What this is

One or more small NFC tags (a key fob, card or sticker) labelled ________________________ each hold the **seed
phrase of a cryptocurrency wallet**, encrypted with a password. The tag alone, or the password alone, gives
nothing. **With a tag and its password together, anyone can take the wallet's funds**, so keep both safe and
never give them to anyone who offers to "help".

The tags were written with SeedTag, a free Android app (github.com/zivke/seedtag). They can be opened with that
app on any NFC Android phone, or with `seedtag.py`, a small program for any computer with Python 3.

## 2. Where the pieces are

| Piece | Where |
|---|---|
| Tag 1 | |
| Tag 2 | |
| The password, written down (copy 1) | |
| The password, written down (copy 2) | |
| The seed's words on paper or metal, if kept | |
| `seedtag.py` (printed or on a USB stick) | |

## 3. How to check you have the right things (none of this is secret)

| | |
|---|---|
| Number of words in the seed | ☐ 12 ☐ 15 ☐ 18 ☐ 21 ☐ 24 |
| Fingerprint (shown after decrypting) | ____________ |
| Check code of tag 1 / tag 2 (shown without the password) | ____-____ / ____-____ |
| SHA-256 of the `seedtag.py` kept with this | ________________________________________________________________ |

If the fingerprint shown after decrypting is the one above, it's the right seed. If a check code differs, the tag's
text was copied with a mistake.

## 4. The wallet

| | |
|---|---|
| Wallet app or device | |
| Coins or networks held | |
| Account type or derivation path, if not the wallet's default | |
| Wallet passphrase (a "25th word", set in the wallet, not SeedTag's password) | ☐ none ☐ yes, kept at: __________ |

Without that wallet passphrase, if there is one, the wallet opens **empty**. It isn't on the tag.

## 5. How to recover

Do this somewhere private, with no cameras, ideally on a device that's offline.

**With a phone:** install SeedTag from github.com/zivke/seedtag (not from a link someone sends you), open the
**Restore** tab, type the password, hold the tag to the back of the phone, and the words are shown.

**With a computer:**

1. Install Python 3 (python.org). Copy `seedtag.py` from the USB stick or type it from the printout. Check it:
   `sha256sum seedtag.py` (on Windows: `certutil -hashfile seedtag.py SHA256`) must print the SHA-256 in
   section 3.
2. Read the tag's text with any NFC reader app on a phone (for example NFC Tools: *Read*, then the record's text,
   a long string of letters and digits). Save it, unchanged, into a file `tag.txt` and move that to the computer.
3. Check it, without showing the words: `python3 -I -S seedtag.py verify tag.txt` (on Windows `py` instead of
   `python3`). It prints the check code first, then asks for the password, then prints OK and the fingerprint.
4. Show the words: `python3 -I -S seedtag.py decrypt tag.txt`.

**Then:** type the words into the wallet named in section 4 (a fresh install), add the wallet passphrase if there
is one, and move the funds to a wallet of your own. Write nothing of this into a website or a chat.

## 6. Watch out

- No support person, exchange, lawyer or "recovery service" ever needs the words or the password. Anyone who asks
  for them wants the funds.
- If several tags are listed, they all hold the same seed: one is enough.
- If the password doesn't work, check every character (capitals, spaces) against the written copy, and try the
  other copy. Nothing can open a tag without its password.

Filled in on (date): ____________ by: ____________________
