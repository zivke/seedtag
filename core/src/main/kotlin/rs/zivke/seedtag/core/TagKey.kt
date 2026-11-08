// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The tag's write password: what the chip itself asks for before it accepts a write once
 * protection is on. It isn't part of the blob and doesn't change the blob format; it comes from
 * the blob's AES key, so the backup password is the only thing needed to remove it again, and
 * knowing it reveals nothing about that key. Same as tag_secret() in seedtag.py:
 *
 *   secret          = HMAC-SHA256(key = AES key of the blob, "seedtag tag key v1")   (32 bytes)
 *   Ultralight C    = secret[0..16)    2-key 3DES key, K1 | K2, as used in AUTHENTICATE
 *   NTAG21x PWD     = secret[16..20)   in the byte order PWD_AUTH sends
 *   NTAG21x PACK    = secret[20..22)
 *
 * Tags protected in the field depend on this: never change it in place.
 */
object TagKey {
    const val SECRET_LEN = 32
    private val LABEL = "seedtag tag key v1".toByteArray(Charsets.US_ASCII)

    /** The 32-byte secret for an AES [key]. The caller zeroes the result. */
    fun secret(key: ByteArray): ByteArray {
        require(key.size == SeedBox.KEY_LEN)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(LABEL)
    }

    fun ultralightCKey(secret: ByteArray): ByteArray = secret.copyOfRange(0, 16)
    fun ntagPwd(secret: ByteArray): ByteArray = secret.copyOfRange(16, 20)
    fun ntagPack(secret: ByteArray): ByteArray = secret.copyOfRange(20, 22)

    /**
     * The 16 bytes to write to pages 2Ch..2Fh of an Ultralight C, in page order, for a 3DES [key]
     * K1 | K2: each 8-byte half goes in reversed (MF0ICU2 datasheet, section 7.5.7: Key1
     * 00 01 .. 07 is written as 07 06 05 04, 03 02 01 00).
     */
    fun ultralightCKeyPages(key: ByteArray): ByteArray {
        require(key.size == 16)
        return ByteArray(16) { i -> key[(i / 8) * 8 + 7 - i % 8] }
    }
}
