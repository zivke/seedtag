#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
"""Tests for seedtag.py against the same vectors the Kotlin core uses.

    python3 tools/test_seedtag.py      (or: make test-py)

Standard library only, plus whatever seedtag.py itself needs. SEEDTAG_SLOW_TESTS=1 also decrypts a
vector with the built-in Argon2id at the real parameters (about a minute).
"""
import ast
import base64
import contextlib
import hashlib
import io
import os
import pathlib
import sys
import tempfile
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

    def test_wordlist_is_the_official_file(self):
        # bitcoin/bips, bip-0039/english.txt: 2048 lines, each ending in a newline.
        official = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"
        self.assertEqual(official, hashlib.sha256(("\n".join(seedtag.WORDLIST) + "\n").encode()).hexdigest())


class SeedBoxV1(unittest.TestCase):
    """Same file SeedBoxTest.kt reads: the two implementations must agree byte for byte."""

    def setUp(self):
        self.vecs = [
            (bytes.fromhex(pw).decode("utf-8"), bytes.fromhex(ent), bytes.fromhex(salt),
             bytes.fromhex(nonce), b64, fp)
            for pw, ent, salt, nonce, b64, fp in rows("seedtag-v1-vectors.tsv")
        ]
        self.assertGreaterEqual(len(self.vecs), 5)

    def test_vectors_cover_long_and_non_bmp_passwords(self):
        self.assertTrue(any(len(pw) == 128 for pw, *_ in self.vecs))
        # Outside the BMP, and one that NFKD changes (fraktur U to U).
        self.assertTrue(any("\U0001D518" in pw and any(ord(c) > 0xFFFF for c in pw) for pw, *_ in self.vecs))

    def test_seal_matches_vectors(self):
        for pw, ent, salt, nonce, b64, _ in self.vecs:
            blob = seedtag.seal(ent, pw, salt, nonce)
            self.assertEqual(b64, base64.b64encode(blob).decode())

    def test_open_and_fingerprint(self):
        for pw, ent, _, _, b64, fp in self.vecs:
            got = seedtag.open_blob(base64.b64decode(b64), pw)
            self.assertEqual(ent, got)
            self.assertEqual(fp, seedtag.fingerprint(got))

    def test_check_codes_match_app(self):
        codes = rows("check-codes.tsv")
        self.assertGreaterEqual(len(codes), 5)
        for text, code in codes:
            self.assertEqual(code, seedtag.check_code(seedtag.blob_from_text(text)))
            variant = "en" + text.replace("+", "-").replace("/", "_").rstrip("=")
            self.assertEqual(code, seedtag.check_code(seedtag.blob_from_text(variant)))
            typo = text[:10] + ("B" if text[10] == "A" else "A") + text[11:]
            self.assertNotEqual(code, seedtag.check_code(seedtag.blob_from_text(typo)))

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


class Robustness(unittest.TestCase):
    """A broken or changed package must not stop a recovery the built-in code can do."""

    def setUp(self):
        for patcher in (mock.patch.object(seedtag, "_broken", set()), mock.patch.object(seedtag, "builtin_only", False)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_python_38_syntax_without_future_import(self):
        tree = ast.parse((HERE / "seedtag.py").read_text(encoding="utf-8"), feature_version=(3, 8))
        future = [n for n in ast.walk(tree) if isinstance(n, ast.ImportFrom) and n.module == "__future__"]
        self.assertEqual([], future)

    def test_failing_backend_falls_through(self):
        def broken(pw, salt):
            raise TypeError("unexpected keyword argument 'lanes'")
        stderr = io.StringIO()
        with mock.patch.object(seedtag, "KDF_BACKENDS", (("cryptography", broken), ("built-in", lambda pw, s: b"k"))), \
                contextlib.redirect_stderr(stderr):
            self.assertEqual(b"k", seedtag.derive_key("x", bytes(16)))
            self.assertEqual("built-in", seedtag.kdf_used)
            self.assertIn("cryptography's Argon2id failed (TypeError)", stderr.getvalue())
            self.assertNotIn("lanes", stderr.getvalue())  # the type only, never the message
            seedtag.derive_key("x", bytes(16))
        self.assertEqual(1, stderr.getvalue().count("failed"))  # not tried again

    def test_builtin_only_skips_packages(self):
        def package(pw, salt):
            raise AssertionError("a package was used")
        with mock.patch.object(seedtag, "KDF_BACKENDS", (("cryptography", package), ("built-in", lambda pw, s: b"k"))), \
                mock.patch.object(seedtag, "builtin_only", True):
            self.assertEqual(b"k", seedtag.derive_key("x", bytes(16)))
            self.assertIs(seedtag.AesGcmBuiltin, seedtag._aesgcm(decrypt_only=True)[0])

    def test_failing_aes_gcm_falls_back_to_builtin(self):
        class Broken:
            def __init__(self, key):
                pass

            def decrypt(self, nonce, data, aad):
                raise AttributeError("renamed")
        pw, ent, salt, _, b64, _ = rows("seedtag-v1-vectors.tsv")[0]
        pw = bytes.fromhex(pw).decode("utf-8")
        try:
            key = seedtag.derive_key(pw, bytes.fromhex(salt))
        except seedtag.NoArgon2:
            self.skipTest("no Argon2id package installed")
        if seedtag.kdf_used == "built-in":
            self.skipTest("no Argon2id package installed")

        class NeverRaised(Exception):
            pass
        with mock.patch.object(seedtag, "_aesgcm", lambda decrypt_only=False: (Broken, NeverRaised)), \
                mock.patch.object(seedtag, "derive_key", lambda p, s: key), \
                contextlib.redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(bytes.fromhex(ent), seedtag.open_blob(base64.b64decode(b64), pw))
            with self.assertRaises(seedtag.WrongPassword):
                seedtag.open_blob(base64.b64decode(b64)[:-1] + b"\0", pw)
        self.assertIn("cryptography's AES-GCM failed (AttributeError)", stderr.getvalue())

    def test_unexpected_error_suggests_standard_library_only(self):
        stderr = io.StringIO()
        with mock.patch.object(seedtag, "open_forgiving", side_effect=TypeError("secret")), \
                mock.patch.object(seedtag.getpass, "getpass", return_value="pw"), \
                contextlib.redirect_stderr(stderr):
            self.assertEqual(1, seedtag.main(["decrypt", "AAAA"]))
        self.assertIn("FAIL: unexpected error (TypeError)", stderr.getvalue())
        self.assertIn("python3 -I -S seedtag.py decrypt --builtin", stderr.getvalue())
        self.assertNotIn("secret", stderr.getvalue())

    def test_builtin_flag_is_for_decrypt_and_verify(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, seedtag.main(["encrypt", "--builtin"]))


class BlobText(unittest.TestCase):
    """Same file BlobTextTest.kt reads: the tool and the app accept the same tag text."""

    def test_matches_vectors(self):
        vecs = rows("blob-text.tsv")
        self.assertGreaterEqual(len(vecs), 20)
        for what, inp, expected, exact in vecs:
            text = bytes.fromhex(inp).decode("utf-8")
            with self.subTest(what):
                self.assertEqual(exact == "yes", seedtag.text_is_exact(text))
                if expected == "error":
                    with self.assertRaises(ValueError):
                        seedtag.blob_from_text(text)
                else:
                    self.assertEqual(expected, seedtag.blob_from_text(text).hex())

    def test_messages_say_what_to_check(self):
        v12 = rows("seedtag-v1-vectors.tsv")[1][4]
        with self.assertRaisesRegex(ValueError, "missing or one too many.*84, 88, 92, 100 or 104"):
            seedtag.blob_from_text(v12[:30] + v12[31:])
        with self.assertRaisesRegex(ValueError, "contains '\\*'"):
            seedtag.blob_from_text("*" + v12)
        with self.assertRaisesRegex(ValueError, "missing or one too many"):
            seedtag.open_blob(seedtag.blob_from_text(v12[:-6]), "x")  # a whole group short
        with self.assertRaisesRegex(ValueError, "starts with A"):
            seedtag.open_blob(seedtag.blob_from_text("B" + v12[1:]), "x")

    def test_read_from_a_file(self):
        v24 = rows("seedtag-v1-vectors.tsv")[0][4]
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "tag.txt"
            path.write_text("en" + v24[:50] + "\n" + v24[50:] + "\n", encoding="utf-8")
            self.assertEqual(base64.b64decode(v24), seedtag._read_blob([str(path)]))

    def test_changes_are_named(self):
        v24 = rows("seedtag-v1-vectors.tsv")[0][4]
        self.assertEqual([], seedtag.text_changes(v24))
        self.assertEqual(["spaces or line breaks", "the letters en in front"],
                         seedtag.text_changes("en " + v24[:40] + "\n" + v24[40:]))
        self.assertEqual(["- or _ in place of + or /"], seedtag.text_changes(v24.replace("+", "-")))
        self.assertEqual(["too few or too many = at the end"], seedtag.text_changes(v24.rstrip("=")))
        self.assertEqual(["a last character SeedTag wouldn't write (it decodes to the same bytes)"],
                         seedtag.text_changes(v24[:-2] + "1="))

    def run_verify(self, text):
        pw, _, _, _, _, _ = rows("seedtag-v1-vectors.tsv")[0]
        stderr = io.StringIO()
        with mock.patch.object(seedtag.getpass, "getpass", return_value=pw), \
                mock.patch.object(seedtag, "open_forgiving", return_value=(bytes(32), None)), \
                contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, seedtag.main(["verify", text]))
        return stderr.getvalue()

    def test_verify_says_whether_the_text_is_exact(self):
        v24 = rows("seedtag-v1-vectors.tsv")[0][4]
        self.assertIn("exactly what SeedTag writes.", self.run_verify(v24))
        out = self.run_verify(v24.rstrip("="))
        self.assertIn("isn't exactly what SeedTag writes: it has too few or too many = at the end", out)
        self.assertIn("may have hidden data", out)

    def test_a_files_last_line_break_is_not_a_change(self):
        v24 = rows("seedtag-v1-vectors.tsv")[0][4]
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "tag.txt"
            for ending in ("", "\n", "\r\n"):
                path.write_bytes((v24 + ending).encode())
                self.assertIn("exactly what SeedTag writes.", self.run_verify(str(path)))
            path.write_bytes((v24 + "\n\n").encode())
            self.assertIn("it has spaces or line breaks", self.run_verify(str(path)))

    def test_wrong_password_mentions_the_text(self):
        pw, _, _, _, b64, _ = rows("seedtag-v1-vectors.tsv")[0]
        stderr = io.StringIO()
        with mock.patch.object(seedtag, "open_forgiving", side_effect=seedtag.WrongPassword("wrong password")), \
                mock.patch.object(seedtag.getpass, "getpass", return_value="pw"), \
                contextlib.redirect_stderr(stderr):
            self.assertEqual(1, seedtag.main(["verify", b64]))
        self.assertIn("one wrong character gives the same error", stderr.getvalue())



def ntag213_pages(message: bytes, rest: bytes = b"", cfg0="04 00 00 FF", cfg1="00 05 00 00", lock="00 00",
                   uid="04 11 22 33 44 55 66", dyn="00 00 00", cc3=0x00) -> "list[bytes]":
    """The 45 pages of an NTAG213 (made-up serial number) holding [message], then [rest]."""
    uid = bytes.fromhex(uid)
    pages = [uid[:3] + bytes([0x88 ^ uid[0] ^ uid[1] ^ uid[2]]), uid[3:7],
             bytes([uid[3] ^ uid[4] ^ uid[5] ^ uid[6], 0x48]) + bytes.fromhex(lock), bytes.fromhex("E1 10 12") + bytes([cc3])]
    data = bytes.fromhex("01 03 A0 0C 34") + bytes([0x03, len(message)]) + message + b"\xfe" + rest
    data += bytes(144 - len(data))
    pages += [data[i:i + 4] for i in range(0, 144, 4)]
    pages += [bytes.fromhex(dyn) + b"\xbd", bytes.fromhex(cfg0), bytes.fromhex(cfg1), bytes(4), bytes(4)]
    return pages


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

    def test_cli_prints_the_password_for_the_backup(self):
        pw, b64, _, _, pwd, pack = rows("tag-key-vectors.tsv")[0]
        with mock.patch("getpass.getpass", return_value=bytes.fromhex(pw).decode("utf-8")), \
                contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(0, seedtag.main(["tag-key", b64]))
        self.assertIn(f"PWD: {pwd}  PACK: {pack}", out.getvalue())


def make_dump(message: bytes, rest: bytes = b"", drop=None, **kw) -> str:
    """A Flipper .nfc dump of ntag213_pages(message, rest, ...)."""
    pages = ntag213_pages(message, rest, **kw)
    lines = ["Filetype: Flipper NFC device", "Version: 4", "Device type: NTAG/Ultralight",
             "NTAG/Ultralight type: NTAG213", "Mifare version: 00 04 04 02 01 00 0F 03",
             "Pages total: 45", f"Pages read: {45 - (drop is not None)}"]
    lines += [f"Page {n}: {p.hex(' ').upper()}" for n, p in enumerate(pages) if n != drop]
    return "\n".join(lines) + "\n"


def lookalike_pages(message: bytes, cfg0="00 00 00 FF", counter="00 00 00 00", uid="1D 11 22 33 44 55 66") -> "list[bytes]":
    """The 46 pages of an NTAG213 lookalike (made-up serial number), like the one tested (a Shanghai Fudan
    FM11NT021): an NTAG213's layout, its NTAG-only settings bytes unused, and a read counter at 2Dh."""
    return ntag213_pages(message, cfg0=cfg0, cfg1="00 00 00 00", uid=uid) + [bytes.fromhex(counter)]


def make_text_dump(message: bytes, rest: bytes = b"", unread=None, pages=None, **kw) -> str:
    """The same pages as NFC Tools' text export of Read memory; page [unread] as one it couldn't read."""
    lines = []
    for n, p in enumerate(pages or ntag213_pages(message, rest, **kw)):
        label = "DATA" if n != unread else "READ ERROR"
        lines.append(f"[ {p.hex(':').upper()} ] Addr. {n:02X} : {label}")
    return "\n".join(lines) + "\n"


class TagDump(unittest.TestCase):
    """seedtag.py check: a tag's whole memory, from a Flipper .nfc dump."""

    def setUp(self):
        self.text = rows("seedtag-v1-vectors.tsv")[0][4]
        self.message = seedtag.seedtag_message(self.text)

    def levels(self, dump):
        lines, backup = seedtag.check_dump(dump)
        return [level for level, _ in lines], lines, backup

    def test_a_clean_backup(self):
        levels, lines, backup = self.levels(make_dump(self.message))
        self.assertNotIn("FAIL", levels)
        self.assertNotIn("WARN", levels)
        self.assertEqual(self.text, backup)
        self.assertIn("exactly a SeedTag backup (24 words)", " ".join(m for _, m in lines))

    def test_an_empty_tag(self):
        for empty in (b"", bytes([0xD8, 0, 0, 0])):
            levels, _, backup = self.levels(make_dump(empty))
            self.assertNotIn("FAIL", levels)
            self.assertIsNone(backup)

    def test_what_isnt_clean(self):
        def fails(dump, words):
            _, lines, _ = self.levels(dump)
            found = [m for level, m in lines if level == "FAIL"]
            self.assertTrue(any(words in m for m in found), (words, found))
        fails(make_dump(self.message, rest=b"\x00old text"), "bytes after the message aren't zero")
        fails(make_dump(self.message, cfg0="04 00 00 10"), "behind a password for writing")
        fails(make_dump(self.message, cfg0="04 00 00 03", cfg1="80 05 00 00"), "for reading and writing")
        fails(make_dump(seedtag.seedtag_message(self.text[:50] + " " + self.text[50:])), "isn't exactly")
        two = bytes([0x91]) + self.message[1:] + bytes([0x51, 1, 1]) + b"T\x00"  # a second record
        fails(make_dump(two), "isn't exactly")
        fails(make_dump(self.message, drop=20), "pages weren't read")
        fails(make_dump(self.message) + "Counter 2: 1234\n", "counter isn't zero")

    def test_seedtags_write_password_is_clean(self):
        # As SeedTag's Protect from writes leaves an NTAG213: AUTH0 03, PROT 0, AUTHLIM 0.
        levels, lines, backup = self.levels(make_dump(self.message, cfg0="04 00 00 03"))
        self.assertNotIn("FAIL", levels)
        self.assertIsNotNone(backup)
        text = " ".join(m for _, m in lines)
        self.assertIn("A write password is on", text)
        self.assertIn("tag-key", text)

    def test_a_lookalikes_version_is_a_warning(self):
        # Shanghai Feiju's NTAG213 puts its own maker code (53) in GET_VERSION.
        dump = make_dump(self.message, uid="53 11 22 33 44 55 66").replace(
            "Mifare version: 00 04 04", "Mifare version: 00 53 04")
        levels, lines, _ = self.levels(dump)
        self.assertNotIn("FAIL", levels)
        self.assertIn("maker code 53", " ".join(m for _, m in lines))

    def test_a_lookalike_with_an_extra_page(self):
        def check(**kw):
            return self.levels(make_text_dump(self.message, pages=lookalike_pages(self.message, **kw)))
        levels, lines, backup = check()
        text = " ".join(m for _, m in lines)
        self.assertNotIn("FAIL", levels)
        self.assertIn("NTAG213 lookalike: all 46 pages read", text)
        self.assertIn("one page more than an NXP NTAG213", text)
        self.assertIn("doesn't officially support", text)
        self.assertIn("factory defaults", text)
        self.assertEqual(self.text, backup)
        # SeedTag's write password on it: AUTH0 03, the unused bytes as they were.
        levels, lines, _ = check(cfg0="00 00 00 03")
        self.assertNotIn("FAIL", levels)
        self.assertIn("A write password is on", " ".join(m for _, m in lines))

        def fails(words, **kw):
            found = [m for level, m in check(**kw)[1] if level == "FAIL"]
            self.assertTrue(any(words in m for m in found), (words, found))
        fails("read counter", counter="00 00 07 00")
        # The PACK reads back on this lookalike (fudan_pwd_pack_stored.txt): a NOTE. Its password never may.
        def with_page(n, value, pages):
            pages[n] = bytes.fromhex(value)
            return self.levels(make_text_dump(self.message, pages=pages))
        levels, lines, _ = with_page(0x2C, "AA A8 00 00", lookalike_pages(self.message))
        self.assertNotIn("FAIL", levels)
        self.assertIn("PACK AA A8", " ".join(m for _, m in lines))
        levels, _, _ = with_page(0x2B, "12 34 56 78", lookalike_pages(self.message))
        self.assertIn("FAIL", levels)
        # On an NXP chip, a PACK that reads back is a FAIL.
        levels, _, _ = with_page(0x2C, "AA A8 00 00", ntag213_pages(self.message))
        self.assertIn("FAIL", levels)
        fails("aren't the factory defaults", cfg0="00 00 07 FF")
        # Any maker: the layout decides, not the serial number.
        levels, _, _ = check(uid="04 11 22 33 44 55 66")
        self.assertNotIn("FAIL", levels)

    def test_lock_bits_are_a_warning(self):
        levels, lines, _ = self.levels(make_dump(self.message, lock="FF FF"))
        self.assertIn("WARN", levels)
        self.assertNotIn("FAIL", levels)
        self.assertIn("missing the dynamic lock bytes", " ".join(m for _, m in lines))

    def test_seedtags_read_only_is_clean(self):
        # Make tag read-only: static FF FF, the datasheet's dynamic lock bytes, CC 0F, CFGLCK with AUTH0 FF.
        dump = make_dump(self.message, lock="FF FF", dyn="FF 0F 3F", cc3=0x0F, cfg1="40 05 00 00")
        levels, lines, backup = self.levels(dump)
        self.assertNotIn("WARN", levels)
        self.assertNotIn("FAIL", levels)
        self.assertIn("Read-only for good", " ".join(m for _, m in lines))
        self.assertEqual(self.text, backup)
        # Cut short after the CC (Android's lock, board B3): a warning that names what's missing.
        levels, lines, _ = self.levels(make_dump(self.message, cc3=0x0F))
        self.assertIn("WARN", levels)
        self.assertNotIn("FAIL", levels)
        text = " ".join(m for _, m in lines)
        self.assertIn("the static lock bytes", text)
        self.assertIn("the settings (CFGLCK", text)
        # A reserved bit set in the dynamic lock bytes: a warning, even with everything else locked.
        levels, _, _ = self.levels(make_dump(self.message, lock="FF FF", dyn="FF FF 3F", cc3=0x0F, cfg1="40 05 00 00"))
        self.assertIn("WARN", levels)
        # Only the settings frozen: the first step of a lock cut short, a warning.
        levels, lines, _ = self.levels(make_dump(self.message, cfg1="40 05 00 00"))
        self.assertEqual(["OK", "WARN"], sorted(set(levels) - {"NOTE"}))
        # CFGLCK with a password behind it isn't SeedTag's.
        levels, _, _ = self.levels(make_dump(self.message, cfg0="04 00 00 10", cfg1="40 05 00 00"))
        self.assertIn("FAIL", levels)

    def test_only_ntag21x(self):
        with self.assertRaisesRegex(ValueError, "only NTAG213, NTAG215 and NTAG216, and lookalikes"):
            seedtag.check_dump(make_dump(self.message).replace("Pages total: 45", "Pages total: 48"))
        with self.assertRaisesRegex(ValueError, "isn't a memory dump"):
            seedtag.check_dump(self.text)

    def test_cli_and_verify_read_the_dump(self):
        with tempfile.TemporaryDirectory() as d:
            clean, dirty = pathlib.Path(d) / "clean.nfc", pathlib.Path(d) / "dirty.nfc"
            clean.write_text(make_dump(self.message), encoding="utf-8")
            dirty.write_text(make_dump(self.message, rest=b"\x00secret"), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(0, seedtag.main(["check", str(clean)]))
                self.assertEqual(1, seedtag.main(["check", str(dirty)]))
            self.assertIn("Clean:", out.getvalue())
            self.assertIn("NOT CLEAN", out.getvalue())
            self.assertEqual(self.text, seedtag._read_text([str(clean)]))

    def test_nfc_tools_text_export(self):
        # The same checks as for a .nfc dump, from the pages alone.
        levels, lines, backup = self.levels(make_text_dump(self.message))
        self.assertNotIn("FAIL", levels)
        self.assertEqual(self.text, backup)
        said = " ".join(m for _, m in lines)
        self.assertIn("exactly a SeedTag backup (24 words)", said)
        self.assertIn("can't show whether this is a genuine NXP NTAG213", said)

        def fails(dump, words):
            _, lines, _ = self.levels(dump)
            found = [m for level, m in lines if level == "FAIL"]
            self.assertTrue(any(words in m for m in found), (words, found))
        fails(make_text_dump(self.message, rest=b"\x00old text"), "bytes after the message aren't zero")
        fails(make_text_dump(self.message, cfg0="04 00 00 10"), "behind a password")
        fails(make_text_dump(self.message, unread=20), "pages weren't read")
        self.assertTrue(seedtag.is_dump(make_text_dump(self.message)))
        self.assertFalse(seedtag.is_dump(self.text))
        with self.assertRaisesRegex(ValueError, "isn't one of NFC Tools' memory lines"):
            seedtag.check_dump(make_text_dump(self.message) + "something else\n")
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d) / "dump.txt"
            f.write_text(make_text_dump(self.message), encoding="utf-8")
            self.assertEqual(self.text, seedtag._read_text([str(f)]))
            with contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(0, seedtag.main(["check", str(f)]))
            self.assertIn("Clean:", out.getvalue())

    def test_another_makers_chip(self):
        # NXP's serial numbers start with 04; another maker's chip with the same layout is a warning, in both formats.
        for dump in (make_dump(self.message, uid="53 11 22 33 44 55 66"),
                     make_text_dump(self.message, uid="53 11 22 33 44 55 66")):
            levels, lines, _ = self.levels(dump)
            self.assertTrue(any(level == "WARN" and "not 04 (NXP's maker code)" in m for level, m in lines))
            self.assertNotIn("FAIL", levels)

if __name__ == "__main__":
    unittest.main(verbosity=2)
