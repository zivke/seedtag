#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
"""Tests for seedtag.py against the same vectors the Kotlin core uses.

    python3 tools/test_seedtag.py      (or: make test-py)

Standard library only, plus whatever seedtag.py itself needs.
"""
import base64
import pathlib
import sys
import unittest
from unittest import mock

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

    def test_newer_format_says_so(self):
        pw, _, _, _, b64, _ = self.vecs[0]
        blob = bytearray(base64.b64decode(b64))
        blob[0] = 0x02
        with self.assertRaisesRegex(ValueError, "newer version of SeedTag"):
            seedtag.open_blob(bytes(blob), pw)
        blob[0] = 0x80
        with self.assertRaisesRegex(ValueError, "not a SeedTag backup"):
            seedtag.open_blob(bytes(blob), pw)

    def test_random_round_trip_fits_tag(self):
        ent = bytes(range(32))
        blob = seedtag.seal(ent, "pw")
        self.assertEqual(77, len(blob))
        self.assertEqual(104, len(base64.b64encode(blob)))
        self.assertEqual(ent, seedtag.open_blob(blob, "pw"))
        self.assertNotEqual(blob, seedtag.seal(ent, "pw"))  # fresh salt + nonce


class Argon2Backends(unittest.TestCase):
    """Each Argon2id backend that's installed must reproduce the vectors on its own."""

    def test_each_backend_matches_vectors(self):
        vecs = rows("seedtag-v1-vectors.tsv")
        for name, kdf in seedtag.KDF_BACKENDS:
            with self.subTest(backend=name):
                with mock.patch.object(seedtag, "KDF_BACKENDS", ((name, kdf),)):
                    try:
                        seedtag.derive_key("x", bytes(16))
                    except seedtag.NoArgon2 as e:
                        self.skipTest(f"{name} not available: {e}")
                    for pw, ent, salt, nonce, b64, _ in vecs:
                        pw = bytes.fromhex(pw).decode("utf-8")
                        blob = seedtag.seal(bytes.fromhex(ent), pw, bytes.fromhex(salt), bytes.fromhex(nonce))
                        self.assertEqual(b64, base64.b64encode(blob).decode())
                    self.assertEqual(name, seedtag.kdf_used)

    def test_unavailable_backend_falls_through(self):
        def unsupported(pw, salt):
            raise seedtag._Unavailable("this cryptography build has no Argon2id")
        real = [b for b in seedtag.KDF_BACKENDS if self._works(b)]
        if not real:
            self.skipTest("no Argon2id backend installed")
        with mock.patch.object(seedtag, "KDF_BACKENDS", (("cryptography", unsupported), real[0])):
            seedtag.derive_key("x", bytes(16))
            self.assertEqual(real[0][0], seedtag.kdf_used)

    def test_none_available_is_a_clear_error(self):
        def missing(pw, salt):
            raise seedtag._Unavailable("not installed")
        with mock.patch.object(seedtag, "KDF_BACKENDS", (("a", missing), ("b", missing))):
            with self.assertRaises(seedtag.NoArgon2) as cm:
                seedtag.derive_key("x", bytes(16))
        self.assertIsInstance(cm.exception, ValueError)  # main() prints it as FAIL, no traceback
        self.assertIn("pip install", str(cm.exception))

    def test_no_aes_gcm_is_a_clear_error(self):
        blob = base64.b64decode(rows("seedtag-v1-vectors.tsv")[0][4])
        real_import = __import__

        def no_cryptography(name, *args, **kwargs):
            if name.split(".")[0] == "cryptography":
                raise ImportError(name)
            return real_import(name, *args, **kwargs)
        with mock.patch("builtins.__import__", no_cryptography), \
                mock.patch.object(seedtag, "derive_key", side_effect=AssertionError("Argon2 ran first")):
            for call in (lambda: seedtag.open_blob(blob, "x"), lambda: seedtag.seal(bytes(16), "x")):
                with self.assertRaises(seedtag.NoAesGcm) as cm:
                    call()
                self.assertIsInstance(cm.exception, ValueError)  # main() prints it as FAIL, no traceback
                self.assertIn("pip install cryptography", str(cm.exception))

    @staticmethod
    def _works(backend):
        try:
            backend[1](b"x", bytes(16))
            return True
        except seedtag._Unavailable:
            return False


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

    def test_stray_spaces(self):
        for ok in ("", "x", "correct horse battery staple", "a b"):
            self.assertFalse(seedtag.has_stray_spaces(ok), repr(ok))
        for bad in (" x", "x ", "a  b", "a\tb", " ", "a\u00a0b"):
            self.assertTrue(seedtag.has_stray_spaces(bad), repr(bad))
        # exactly the passwords password_variants() would offer a spaces fix for
        for pw in ("a b", " a", "a  b", "a\tb", "Ab"):
            spaces = any(fix in ("Spaces", "SpacesAndCase") for fix, _ in seedtag.password_variants(pw))
            self.assertEqual(spaces, seedtag.has_stray_spaces(pw), repr(pw))

    def test_bad_blob_is_not_retried(self):
        with self.assertRaises(ValueError) as cm:
            seedtag.open_forgiving(b"\x02" + bytes(76), " Pw ")
        self.assertNotIsInstance(cm.exception, seedtag.WrongPassword)


if __name__ == "__main__":
    unittest.main(verbosity=2)
