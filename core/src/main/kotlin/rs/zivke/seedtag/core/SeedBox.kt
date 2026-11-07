// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SeedTag blob format v1 (byte-compatible with tools/seedtag.py):
 *
 *   blob  = 0x01 | salt[16] | nonce[12] | AES-256-GCM(entropy)[entropy.size + 16]
 *   key   = Argon2id(UTF-8(NFKD(password)), salt, t=3, m=65536 KiB, p=1, len=32)
 *   AAD   = 0x01 (the header byte)
 *   tag   = NDEF Text record, lang "en", standard Base64 of blob
 */
/** What [SeedBox.openForgiving] had to change in the typed password before it matched. */
enum class PasswordFix { None, Spaces, Case, SpacesAndCase }

/** Decrypted entropy (the caller zeroes it) and the fix the password needed. */
class Opened(val entropy: ByteArray, val fix: PasswordFix)

object SeedBox {
    const val VERSION: Int = 0x01
    const val SALT_LEN = 16
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    const val ARGON_ITERATIONS = 3
    const val ARGON_MEMORY_KIB = 65536
    const val ARGON_PARALLELISM = 1
    const val KEY_LEN = 32
    const val OVERHEAD = 1 + SALT_LEN + NONCE_LEN + TAG_LEN

    /** Encrypts raw BIP39 entropy. The caller keeps ownership of [entropy] and [password]. */
    fun seal(entropy: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        return seal(entropy, password, salt, nonce)
    }

    /** Deterministic variant – tests only. Never reuse a salt/nonce pair for real data. */
    fun seal(entropy: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray): ByteArray {
        require(entropy.size in Bip39.validEntropyLengths) { "bad entropy length ${entropy.size}" }
        require(salt.size == SALT_LEN && nonce.size == NONCE_LEN)
        val key = deriveKey(password, salt)
        try {
            val header = byteArrayOf(VERSION.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            cipher.updateAAD(header)
            val ct = cipher.doFinal(entropy)
            return header + salt + nonce + ct
        } finally {
            key.fill(0)
        }
    }

    /**
     * Decrypts a blob back to raw entropy. Caller must zero the result when done.
     * @throws SeedTagException.WrongPassword / UnsupportedVersion / Malformed
     */
    fun open(blob: ByteArray, password: CharArray): ByteArray {
        if (blob.isEmpty()) throw SeedTagException.Malformed("empty")
        val version = blob[0].toInt() and 0xFF
        if (version != VERSION) throw SeedTagException.UnsupportedVersion(version)
        val entLen = blob.size - OVERHEAD
        if (entLen !in Bip39.validEntropyLengths) throw SeedTagException.Malformed("length ${blob.size}")
        val salt = blob.copyOfRange(1, 1 + SALT_LEN)
        val nonce = blob.copyOfRange(1 + SALT_LEN, 1 + SALT_LEN + NONCE_LEN)
        val key = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            cipher.updateAAD(blob, 0, 1)
            val offset = 1 + SALT_LEN + NONCE_LEN
            return cipher.doFinal(blob, offset, blob.size - offset)
        } catch (e: AEADBadTagException) {
            throw SeedTagException.WrongPassword()
        } finally {
            key.fill(0)
        }
    }

    /**
     * Like [open], but if the password is wrong, also tries it with extra spaces removed, then
     * also lower-cased ([passwordVariants]): a typo there shouldn't look like a lost backup.
     * Each retry is one more Argon2 run. Caller must zero [Opened.entropy].
     * @throws SeedTagException.WrongPassword if no variant matches, or open's other errors at once.
     */
    fun openForgiving(blob: ByteArray, password: CharArray): Opened {
        val wrong = try {
            return Opened(open(blob, password), PasswordFix.None)
        } catch (e: SeedTagException.WrongPassword) {
            e
        }
        val variants = passwordVariants(password)
        try {
            for ((fix, candidate) in variants) {
                try {
                    return Opened(open(blob, candidate), fix)
                } catch (_: SeedTagException.WrongPassword) {
                    // try the next one
                }
            }
        } finally {
            variants.forEach { it.second.fill('\u0000') }
        }
        throw wrong
    }

    /**
     * Retry candidates for a password that didn't match, each a new array the caller zeroes:
     * whitespace trimmed and collapsed to single spaces, then that lower-cased (char by char,
     * except U+0130 İ, whose lower case is two chars). Only variants that differ are returned.
     * Same rules as password_variants() in seedtag.py.
     */
    fun passwordVariants(password: CharArray): List<Pair<PasswordFix, CharArray>> {
        val collapsed = collapseSpaces(password)
        val lowered = CharArray(collapsed.size) { i -> collapsed[i].let { if (it == '\u0130') it else it.lowercaseChar() } }
        val spaces = !collapsed.contentEquals(password)
        val case = !lowered.contentEquals(collapsed)
        val out = ArrayList<Pair<PasswordFix, CharArray>>(2)
        if (spaces) out += PasswordFix.Spaces to collapsed else collapsed.fill('\u0000')
        if (case) out += (if (spaces) PasswordFix.SpacesAndCase else PasswordFix.Case) to lowered else lowered.fill('\u0000')
        return out
    }

    private fun collapseSpaces(password: CharArray): CharArray {
        val tmp = CharArray(password.size)
        var n = 0
        var gap = false
        for (c in password) {
            if (c.isWhitespace()) {
                if (n > 0) gap = true
            } else {
                if (gap) { tmp[n++] = ' '; gap = false }
                tmp[n++] = c
            }
        }
        try {
            return tmp.copyOf(n)
        } finally {
            tmp.fill('\u0000')
        }
    }

    fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val pw = passwordBytes(password)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ARGON_ITERATIONS)
                .withMemoryAsKB(ARGON_MEMORY_KIB)
                .withParallelism(ARGON_PARALLELISM)
                .withSalt(salt)
                .build()
            val out = ByteArray(KEY_LEN)
            Argon2BytesGenerator().apply { init(params) }.generateBytes(pw, out)
            return out
        } finally {
            pw.fill(0)
        }
    }

    /**
     * NFKD-normalise, then UTF-8 encode. ASCII is unchanged by NFKD and is one byte per char, so it
     * is copied straight across. Anything else goes through Normalizer, which makes one transient
     * String (unavoidable on the JVM).
     */
    internal fun passwordBytes(password: CharArray): ByteArray {
        if (password.all { it.code < 0x80 }) return ByteArray(password.size) { password[it].code.toByte() }
        val normalized = Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFKD)
        val bb: ByteBuffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(normalized))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray()) bb.array().fill(0)
        return out
    }

    fun toBase64(blob: ByteArray): String = Base64.getEncoder().encodeToString(blob)

    /** Accepts Base64 with arbitrary whitespace. */
    fun fromBase64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text.filterNot { it.isWhitespace() })
    } catch (e: IllegalArgumentException) {
        throw SeedTagException.Malformed("not Base64")
    }

    /** Short non-secret check value for comparing backups (same as seedtag.py `verify`). */
    fun fingerprint(entropy: ByteArray): String {
        val input = "seedtag-fp".toByteArray() + entropy
        val h = Bip39.sha256(input)
        try {
            return h.take(4).joinToString("") { "%02x".format(it) }
        } finally {
            input.fill(0); h.fill(0)
        }
    }
}
