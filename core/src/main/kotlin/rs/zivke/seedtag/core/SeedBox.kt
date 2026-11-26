// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.security.GeneralSecurityException
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

/**
 * Decrypted entropy, the fix the password needed and, only if asked for, the tag's write-password
 * secret ([TagKey.secret]). The caller zeroes [entropy] and [tagSecret].
 */
class Opened(val entropy: ByteArray, val fix: PasswordFix, val tagSecret: ByteArray? = null)

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
        } catch (e: GeneralSecurityException) {
            throw SeedTagException.CryptoFailure(e.javaClass.simpleName)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Decrypts a blob back to raw entropy. Caller must zero the result when done.
     * @throws SeedTagException.WrongPassword / UnsupportedVersion / Malformed / CryptoFailure
     */
    fun open(blob: ByteArray, password: CharArray): ByteArray = open(blob, password, PasswordFix.None, false).entropy

    private fun open(blob: ByteArray, password: CharArray, fix: PasswordFix, withTagSecret: Boolean): Opened {
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
            val entropy = cipher.doFinal(blob, offset, blob.size - offset)
            return Opened(entropy, fix, if (withTagSecret) TagKey.secret(key) else null)
        } catch (e: AEADBadTagException) {
            throw SeedTagException.WrongPassword()
        } catch (e: GeneralSecurityException) {
            // Not the password: the platform's AES itself failed (a missing provider, a rejected key).
            throw SeedTagException.CryptoFailure(e.javaClass.simpleName)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Like [open], but if the password is wrong, also tries it with extra spaces removed, then
     * also lower-cased ([passwordVariants]): a typo there shouldn't look like a lost backup.
     * Each retry is one more Argon2 run. With [withTagSecret], also returns the tag's write-password
     * secret, from the key that matched. Caller must zero [Opened.entropy] and [Opened.tagSecret].
     * @throws SeedTagException.WrongPassword if no variant matches, or open's other errors at once.
     */
    fun openForgiving(blob: ByteArray, password: CharArray, withTagSecret: Boolean = false): Opened {
        val wrong = try {
            return open(blob, password, PasswordFix.None, withTagSecret)
        } catch (e: SeedTagException.WrongPassword) {
            e
        }
        val variants = passwordVariants(password)
        try {
            for ((fix, candidate) in variants) {
                try {
                    return open(blob, candidate, fix, withTagSecret)
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
     * whitespace ([isPasswordSpace]) trimmed and collapsed to single spaces, then that lower-cased
     * (code point by code point, except U+0130 İ, whose lower case is two code points). Only
     * variants that differ are returned. Same rules as password_variants() in seedtag.py.
     */
    fun passwordVariants(password: CharArray): List<Pair<PasswordFix, CharArray>> {
        val collapsed = collapseSpaces(password)
        val lowered = lowerCase(collapsed)
        val spaces = !collapsed.contentEquals(password)
        val case = !lowered.contentEquals(collapsed)
        val out = ArrayList<Pair<PasswordFix, CharArray>>(2)
        if (spaces) out += PasswordFix.Spaces to collapsed else collapsed.fill('\u0000')
        if (case) out += (if (spaces) PasswordFix.SpacesAndCase else PasswordFix.Case) to lowered else lowered.fill('\u0000')
        return out
    }

    /**
     * The whitespace the password rules know, spelled out so that the app and seedtag.py agree: exactly
     * Python's `str.isspace()` (Unicode White_Space plus U+001C–U+001F). Kotlin's `isWhitespace()`
     * differs (it leaves out U+0085).
     */
    fun isPasswordSpace(c: Char): Boolean = when (c.code) {
        in 0x09..0x0D, in 0x1C..0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** Lower case by code point, so a capital outside the BMP is lowered too; U+0130 İ is kept. */
    private fun lowerCase(chars: CharArray): CharArray {
        val out = CharArray(chars.size)
        var i = 0
        while (i < chars.size) {
            val cp = Character.codePointAt(chars, i)
            val n = Character.charCount(cp)
            val lower = if (cp == 0x130) cp else Character.toLowerCase(cp)
            Character.toChars(if (Character.charCount(lower) == n) lower else cp, out, i)
            i += n
        }
        return out
    }

    private fun collapseSpaces(password: CharArray): CharArray {
        val tmp = CharArray(password.size)
        var n = 0
        var gap = false
        for (c in password) {
            if (isPasswordSpace(c)) {
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
        val bb: ByteBuffer = try {
            Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(normalized))
        } catch (e: CharacterCodingException) {
            throw SeedTagException.BadPassword() // a lone surrogate: no UTF-8 for it
        }
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray()) bb.array().fill(0)
        return out
    }

    /** Bytes the NDEF message of a [words]-word backup takes on a tag (91 for 12 words, 111 for 24). */
    fun ndefBytesFor(words: Int): Int {
        require(words in Bip39.validWordCounts) { "bad word count $words" }
        val blob = OVERHEAD + words * 4 / 3
        return NdefText.messageSize("A".repeat(4 * ((blob + 2) / 3)))
    }

    /** The longest backup (in words) that fits [capacity] NDEF bytes, or null if not even 12 words do. */
    fun maxWordsFor(capacity: Int): Int? = Bip39.validWordCounts.filter { ndefBytesFor(it) <= capacity }.maxOrNull()

    fun toBase64(blob: ByteArray): String = Base64.getEncoder().encodeToString(blob)

    /**
     * First bytes a future SeedTag might write: any version above [VERSION] up to this is taken for a
     * newer backup, so an old app warns before overwriting one and says why it can't read it.
     */
    const val MAX_FUTURE_VERSION = 0x0F

    /** Blob sizes a backup could have: the smallest v1 blob, up to a margin for a future format. */
    private val BACKUP_SIZES = (OVERHEAD + 16)..200

    enum class Kind { Current, Newer, NotBackup }

    /** What a tag's [text] looks like, from its first byte and size only: nothing is decrypted. */
    fun kindOf(text: String?): Kind {
        val blob = text?.let { runCatching { fromBase64(it) }.getOrNull() } ?: return Kind.NotBackup
        if (blob.size !in BACKUP_SIZES) return Kind.NotBackup
        return when (blob[0].toInt()) {
            VERSION -> Kind.Current
            in VERSION + 1..MAX_FUTURE_VERSION -> Kind.Newer
            else -> Kind.NotBackup
        }
    }

    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * The blob in a tag's text, as another tool may have written it: whitespace anywhere
     * ([isPasswordSpace]), the record's language code "en" in front, URL-safe `-` and `_`, and
     * missing or extra `=` at the end are all accepted. Same rules as blob_from_text() in
     * seedtag.py, checked against blob-text.tsv.
     */
    /**
     * Whether [text] is exactly what SeedTag writes for its blob: standard Base64 with its `=` and
     * nothing else. Whatever [fromBase64] forgives makes it false, and so do unused bits in the last
     * character that aren't zero, which decoders ignore. Same as text_is_exact() in seedtag.py,
     * checked against blob-text.tsv.
     */
    fun isExactText(text: String): Boolean =
        try {
            toBase64(fromBase64(text)) == text
        } catch (_: SeedTagException.Malformed) {
            false
        }

    fun fromBase64(text: String): ByteArray {
        var s = text.filterNot { isPasswordSpace(it) }
        if (s.startsWith("enA")) s = s.substring(2) // every SeedTag text starts with A
        s = s.replace('-', '+').replace('_', '/').trimEnd('=')
        if (s.length % 4 == 1 || s.any { it !in BASE64_CHARS }) throw SeedTagException.Malformed("not Base64")
        return try {
            Base64.getDecoder().decode(s + "=".repeat((4 - s.length % 4) % 4))
        } catch (e: IllegalArgumentException) {
            throw SeedTagException.Malformed("not Base64")
        }
    }

    /**
     * The tag's check code (board B8, FORMAT.md section 5): of the [blob], so the same however its text is written
     * (padding, `-_`, the record's `en` in front) and different after one mistyped character. Not secret, like the
     * text. SeedTag shows it, and seedtag.py prints it before it asks for the password, so a typo in typed-in text
     * shows as a different code rather than as a wrong password. Eight hex digits in two groups: `1a2b-3c4d`,
     * grouped so it isn't taken for the [fingerprint]. Same as check_code() in seedtag.py.
     */
    fun checkCode(blob: ByteArray): String {
        val h = Bip39.sha256("seedtag-check".toByteArray() + blob)
        val hex = h.take(4).joinToString("") { "%02x".format(it) }
        return hex.substring(0, 4) + "-" + hex.substring(4)
    }

    /** [checkCode] of a tag's [text], or null if it isn't Base64 at all. */
    fun checkCodeOf(text: String): String? = try {
        checkCode(fromBase64(text))
    } catch (e: SeedTagException) {
        null
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
