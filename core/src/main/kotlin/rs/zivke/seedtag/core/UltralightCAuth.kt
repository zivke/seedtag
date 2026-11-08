// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The reader's side of the MIFARE Ultralight C mutual 3DES authentication (MF0ICU2 datasheet,
 * sections 7.5.5 and 9.5), without any I/O so it can be tested on the JVM:
 *
 *   reader → tag   1A 00
 *   tag → reader   AF | ek(RndB)                      CBC, IV = 0
 *   reader → tag   AF | ek(RndA | rotl(RndB))         CBC, IV = ek(RndB)
 *   tag → reader   00 | ek(rotl(RndA))                CBC, IV = last 8 bytes sent
 *
 * The key is 2-key 3DES, K1 | K2 (16 bytes), used as K1 K2 K1. Call [wipe] when done.
 */
class UltralightCAuth(key: ByteArray, rndA: ByteArray) {
    private val key24 = ByteArray(24).also {
        require(key.size == 16 && rndA.size == 8)
        key.copyInto(it, 0)
        key.copyInto(it, 16, 0, 8)
    }
    private val rndA = rndA.copyOf()
    private var iv = ByteArray(8)

    /** Takes the 8 bytes after AF in the tag's first answer; returns the 16 bytes to send after AF. */
    fun answer(ekRndB: ByteArray): ByteArray {
        require(ekRndB.size == 8)
        val rndB = crypt(Cipher.DECRYPT_MODE, iv, ekRndB)
        val plain = rndA + rotate(rndB)
        try {
            val out = crypt(Cipher.ENCRYPT_MODE, ekRndB, plain)
            iv = out.copyOfRange(8, 16)
            return out
        } finally {
            rndB.fill(0); plain.fill(0)
        }
    }

    /** Takes the 8 bytes after 00 in the tag's last answer; true if the tag holds the same key. */
    fun verify(ekRndA2: ByteArray): Boolean {
        if (ekRndA2.size != 8) return false
        val got = crypt(Cipher.DECRYPT_MODE, iv, ekRndA2)
        val expected = rotate(rndA)
        try {
            return MessageDigest.isEqual(got, expected)
        } finally {
            got.fill(0); expected.fill(0)
        }
    }

    fun wipe() {
        key24.fill(0); rndA.fill(0); iv.fill(0)
    }

    private fun crypt(mode: Int, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("DESede/CBC/NoPadding")
        c.init(mode, SecretKeySpec(key24, "DESede"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun rotate(b: ByteArray) = ByteArray(b.size) { b[(it + 1) % b.size] }
}
