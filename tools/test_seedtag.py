#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
"""Tests for seedtag.py against the same vectors the Kotlin core uses.

    python3 tools/test_seedtag.py      (or: make test-py)

Standard library only, plus whatever seedtag.py itself needs. SEEDTAG_SLOW_TESTS=1 also decrypts a
vector with the built-in Argon2id at the real parameters (about a minute).
"""
import base64
import os
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


# Backends that must be installed (comma-separated), e.g. in CI: a skip is then a failure.
REQUIRED_BACKENDS = set(filter(None, os.environ.get("SEEDTAG_REQUIRE_BACKENDS", "").split(",")))


class Argon2Backends(unittest.TestCase):
    """Each Argon2id backend that's installed must reproduce the vectors on its own."""

    def test_each_backend_matches_vectors(self):
        vecs = rows("seedtag-v1-vectors.tsv")
        for name, kdf in seedtag.KDF_BACKENDS:
            if name == "built-in":
                continue  # a minute per vector: BuiltinCrypto checks it
            with self.subTest(backend=name):
                with mock.patch.object(seedtag, "KDF_BACKENDS", ((name, kdf),)):
                    try:
                        seedtag.derive_key("x", bytes(16))
                    except seedtag.NoArgon2 as e:
                        if name in REQUIRED_BACKENDS:
                            self.fail(f"{name} is required (SEEDTAG_REQUIRE_BACKENDS) but not available: {e}")
                        self.skipTest(f"{name} not available: {e}")
                    for pw, ent, salt, nonce, b64, _ in vecs:
                        pw = bytes.fromhex(pw).decode("utf-8")
                        blob = seedtag.seal(bytes.fromhex(ent), pw, bytes.fromhex(salt), bytes.fromhex(nonce))
                        self.assertEqual(b64, base64.b64encode(blob).decode())
                    self.assertEqual(name, seedtag.kdf_used)

    def test_unavailable_backend_falls_through(self):
        def unsupported(pw, salt):
            raise seedtag._Unavailable("this cryptography build has no Argon2id")
        real = [b for b in seedtag.KDF_BACKENDS if b[0] != "built-in" and self._works(b)]
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
        with without("cryptography"), \
                mock.patch.object(seedtag, "derive_key", side_effect=AssertionError("Argon2 ran first")):
            with self.assertRaises(seedtag.NoAesGcm) as cm:
                seedtag.seal(bytes(16), "x")
        self.assertIsInstance(cm.exception, ValueError)  # main() prints it as FAIL, no traceback
        self.assertIn("pip install cryptography", str(cm.exception))

    @staticmethod
    def _works(backend):
        try:
            backend[1](b"x", bytes(16))
            return True
        except seedtag._Unavailable:
            return False


def without(*packages):
    """Imports of these packages fail, as if they weren't installed."""
    real_import = __import__

    def fake(name, *args, **kwargs):
        if name.split(".")[0] in packages:
            raise ImportError(name)
        return real_import(name, *args, **kwargs)
    return mock.patch("builtins.__import__", fake)


class BuiltinCrypto(unittest.TestCase):
    """The plain-Python Argon2id and AES-GCM decryption, for when neither package can be installed."""

    # t, m (KiB), password, salt, tag: made with both cryptography 50 and argon2-cffi (lanes 1).
    ARGON2ID = [
        (1, 8, "", "0000000000000000",
         "9f147c5923c505e876f831c165fbd5a261670c60f85bea5d8fed76941c60a24e"),
        (3, 64, "70617373776f7264", "736f6d6573616c743132333435363738",
         "bfbd88e639fa991331f6e10953221dbd873896e754050e45d3871dfbb08b75f3"),
        (2, 260, "70c3a4737377c3b67264", "000102030405060708090a0b0c0d0e0f",
         "e3904cc043f838338ca0150ffeb8ee8419739ca6dee3902c0a1966e33a997b62"),
        (3, 1024, "78" * 70, "ff" * 16,
         "c989fb9d9661ab2ec2009c4a7e081ab4084e0aba6c7360d8ab9312b64d1cab1470b9b97bf963833f67e0c8216c796f39"
         "2ea3a8168b3e6d11ff27248fb730ba33b90aab48f26b2530ed57d810b6f61f7f203e49b53acbdc03b84524aa10a9945a"
         "78684ac8"),
        (4, 37, "61", "00" * 16,
         "fa7ab79d8f8b6307ef430fb343d2b3dd47d8d793efd34448929e94deb78a67ce1b125c3975ecca449999dcc22c223a2d"
         "9d1b431f3087ef7ca039786bc006c428"),
    ]

    def test_argon2id_known_answers(self):
        for t, m, pw, salt, tag in self.ARGON2ID:
            got = seedtag.argon2id_builtin(bytes.fromhex(pw), bytes.fromhex(salt), t, m, len(tag) // 2)
            self.assertEqual(tag, got.hex(), msg=f"t={t} m={m}")

    def test_aes_gcm_nist(self):
        # The GCM specification's test case 16 (AES-256, 96-bit IV, with AAD).
        key = bytes.fromhex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        iv = bytes.fromhex("cafebabefacedbaddecaf888")
        aad = bytes.fromhex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        pt = bytes.fromhex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72"
                           "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39")
        ct = bytes.fromhex("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa"
                           "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662"
                           "76fc6ece0f4e1768cddf8853bb2d551b")
        gcm = seedtag.AesGcmBuiltin(key)
        self.assertEqual(pt, gcm.decrypt(iv, ct, aad))
        for pos in (0, 59, 60, 75):
            bad = bytearray(ct)
            bad[pos] ^= 0x80
            with self.assertRaises(seedtag.InvalidTagBuiltin):
                gcm.decrypt(iv, bytes(bad), aad)
        with self.assertRaises(seedtag.InvalidTagBuiltin):
            gcm.decrypt(iv, ct, aad[:-1])

    def test_open_without_cryptography(self):
        """Every vector decrypts with the built-in AES-GCM (the key from a fast Argon2id, if any)."""
        try:
            keys = {(pw, salt): seedtag.derive_key(bytes.fromhex(pw).decode("utf-8"), bytes.fromhex(salt))
                    for pw, _, salt, _, _, _ in rows("seedtag-v1-vectors.tsv")}
        except seedtag.NoArgon2:
            self.skipTest("no Argon2id package installed")
        if seedtag.kdf_used == "built-in":
            self.skipTest("no Argon2id package installed")
        with without("cryptography"), \
                mock.patch.object(seedtag, "derive_key", lambda pw, salt: keys[(pw.encode().hex(), salt.hex())]):
            for pw, ent, _, _, b64, _ in rows("seedtag-v1-vectors.tsv"):
                pw = bytes.fromhex(pw).decode("utf-8")
                self.assertEqual(bytes.fromhex(ent), seedtag.open_blob(base64.b64decode(b64), pw))
            pw, _, _, _, b64, _ = rows("seedtag-v1-vectors.tsv")[0]
            blob = bytearray(base64.b64decode(b64))
            blob[-1] ^= 1
            with self.assertRaises(seedtag.WrongPassword):
                seedtag.open_blob(bytes(blob), bytes.fromhex(pw).decode("utf-8"))

    @unittest.skipUnless(os.environ.get("SEEDTAG_SLOW_TESTS") == "1", "set SEEDTAG_SLOW_TESTS=1 (about a minute)")
    def test_open_without_any_package(self):
        pw, ent, _, _, b64, _ = rows("seedtag-v1-vectors.tsv")[0]
        with without("cryptography", "argon2"):
            got = seedtag.open_blob(base64.b64decode(b64), bytes.fromhex(pw).decode("utf-8"))
        self.assertEqual(bytes.fromhex(ent), got)
        self.assertEqual("built-in", seedtag.kdf_used)


class PasswordVariants(unittest.TestCase):
    """Same file PasswordVariantsTest.kt reads: both tools must retry the same passwords."""

    def test_variants_match_vectors(self):
        vecs = rows("password-variants.tsv")
        self.assertEqual(14, len(vecs))
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
