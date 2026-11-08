#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke85
"""Tests for seedtag.py against the same vectors the Kotlin core uses.

    python3 tools/test_seedtag.py      (or: make test-py)

Standard library only, plus whatever seedtag.py itself needs.
"""
import base64
import pathlib
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
VECTORS = HERE.parent / "core" / "src" / "test" / "resources"
sys.path.insert(0, str(HERE))

import seedtag  # noqa: E402


def rows(name):
    text = (VECTORS / name).read_text(encoding="utf-8")
    return [line.split("\t") for line in text.splitlines() if line.strip()]


class Bip39(unittest.TestCase):
    def test_trezor_vectors(self):
        vecs = rows("bip39-vectors.tsv")
        self.assertEqual(24, len(vecs))
        for ent, words in vecs:
            self.assertEqual(words, " ".join(seedtag.entropy_to_words(bytes.fromhex(ent))))
            self.assertEqual(bytes.fromhex(ent), seedtag.words_to_entropy(words.split()))

    def test_bad_checksum(self):
        with self.assertRaises(ValueError):
            seedtag.words_to_entropy(["abandon"] * 24)

    def test_unknown_word_and_count(self):
        with self.assertRaises(ValueError) as cm:
            seedtag.words_to_entropy(["abandon"] * 11 + ["bitcoinz"])
        self.assertIn("#12", str(cm.exception))
        self.assertNotIn("bitcoinz", str(cm.exception))  # never echo a seed word
        with self.assertRaises(ValueError):
            seedtag.words_to_entropy(["abandon"] * 13)

    def test_wordlist_matches_app(self):
        app = (HERE.parent / "core/src/main/resources/rs/zivke/seedtag/core/bip39-english.txt")
        self.assertEqual(app.read_text(encoding="utf-8").split(), seedtag.WORDLIST)


class SeedBoxV1(unittest.TestCase):
    """Same file SeedBoxTest.kt reads: the two implementations must agree byte for byte."""

    def setUp(self):
        self.vecs = [
            (bytes.fromhex(pw).decode("utf-8"), bytes.fromhex(ent), bytes.fromhex(salt),
             bytes.fromhex(nonce), b64, fp)
            for pw, ent, salt, nonce, b64, fp in rows("seedtag-v1-vectors.tsv")
        ]
        self.assertGreaterEqual(len(self.vecs), 5)

    def test_seal_matches_vectors(self):
        for pw, ent, salt, nonce, b64, _ in self.vecs:
            blob = seedtag.seal(ent, pw, salt, nonce)
            self.assertEqual(b64, base64.b64encode(blob).decode())

    def test_open_and_fingerprint(self):
        for pw, ent, _, _, b64, fp in self.vecs:
            got = seedtag.open_blob(base64.b64decode(b64), pw)
            self.assertEqual(ent, got)
            self.assertEqual(fp, seedtag.fingerprint(got))

    def test_wrong_password(self):
        _, _, _, _, b64, _ = self.vecs[0]
        with self.assertRaises(ValueError):
            seedtag.open_blob(base64.b64decode(b64), "nope")

    def test_tampering(self):
        pw, _, _, _, b64, _ = self.vecs[0]
        for pos in (0, 5, 20, 40, 76):
            blob = bytearray(base64.b64decode(b64))
            blob[pos] ^= 1
            with self.assertRaises(ValueError, msg=f"pos {pos}"):
                seedtag.open_blob(bytes(blob), pw)

    def test_random_round_trip_fits_tag(self):
        ent = bytes(range(32))
        blob = seedtag.seal(ent, "pw")
        self.assertEqual(77, len(blob))
        self.assertEqual(104, len(base64.b64encode(blob)))
        self.assertEqual(ent, seedtag.open_blob(blob, "pw"))
        self.assertNotEqual(blob, seedtag.seal(ent, "pw"))  # fresh salt + nonce


class PasswordVariants(unittest.TestCase):
    """Same file PasswordVariantsTest.kt reads: both tools must retry the same passwords."""

    def test_variants_match_vectors(self):
        vecs = rows("password-variants.tsv")
        self.assertEqual(11, len(vecs))
        for cols in vecs:
            pw = bytes.fromhex(cols[0]).decode("utf-8")
            expected = [(cols[i], bytes.fromhex(cols[i + 1]).decode("utf-8")) for i in range(1, len(cols), 2)]
            self.assertEqual(expected, seedtag.password_variants(pw), msg=repr(pw))

    def test_forgiving_open(self):
        pw, ent, _, _, b64, _ = rows("seedtag-v1-vectors.tsv")[0]
        self.assertEqual("correct horse battery staple", bytes.fromhex(pw).decode())
        blob = base64.b64decode(b64)
        self.assertEqual((bytes.fromhex(ent), None), seedtag.open_forgiving(blob, "correct horse battery staple"))
        self.assertEqual("Spaces", seedtag.open_forgiving(blob, " correct  horse battery staple ")[1])
        self.assertEqual("Case", seedtag.open_forgiving(blob, "Correct Horse Battery Staple")[1])
        self.assertEqual("SpacesAndCase", seedtag.open_forgiving(blob, "  Correct horse\tbattery  staple")[1])
        for wrong in ("correct horse battery", "Correct  horse battery stapler"):
            with self.assertRaises(seedtag.WrongPassword):
                seedtag.open_forgiving(blob, wrong)

    def test_bad_blob_is_not_retried(self):
        with self.assertRaises(ValueError) as cm:
            seedtag.open_forgiving(b"\x02" + bytes(76), " Pw ")
        self.assertNotIsInstance(cm.exception, seedtag.WrongPassword)


class TagKey(unittest.TestCase):
    """Same file TagKeyTest.kt reads: both must derive the same write password for a tag."""

    def test_tag_keys_match_vectors(self):
        vecs = rows("tag-key-vectors.tsv")
        self.assertGreaterEqual(len(vecs), 5)
        for pw, b64, ulc_key, ulc_pages, pwd, pack in vecs:
            _, fix, key = seedtag._open_forgiving_key(base64.b64decode(b64), bytes.fromhex(pw).decode("utf-8"))
            self.assertIsNone(fix)
            k = seedtag.tag_keys(seedtag.tag_secret(key))
            self.assertEqual([ulc_key, ulc_pages, pwd, pack],
                             [k[n].hex() for n in ("ulc_key", "ulc_pages", "ntag_pwd", "ntag_pack")])

    def test_ultralight_c_page_order(self):
        # MF0ICU2 datasheet, section 7.5.7: Key1 00..07 and Key2 08..0F as stored in pages 2Ch..2Fh.
        k = seedtag.tag_keys(bytes.fromhex("000102030405060708090a0b0c0d0e0f") + bytes(16))
        self.assertEqual("07060504" "03020100" "0f0e0d0c" "0b0a0908", k["ulc_pages"].hex())


if __name__ == "__main__":
    unittest.main(verbosity=2)
