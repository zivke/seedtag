// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseTest {

    // Trezor test vector, never a real seed.
    private val seed = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ").toSet()

    private fun bits(s: String, avoid: Set<String> = emptySet()) = Passphrase.estimateBits(s.toCharArray(), avoid)

    @Test
    fun generatedIsDistinctAndAvoidsTheSeed() {
        repeat(200) {
            val words = String(Passphrase.generate(exclude = seed)).split(" ")
            assertEquals(7, words.size)
            assertEquals(7, words.toSet().size)
            assertTrue(words.none { it in seed })
            assertEquals(77, bits(words.joinToString(" "), seed))
        }
    }

    @Test
    fun repeatedWordsCountOnce() {
        assertEquals(11, bits("abandon abandon abandon abandon abandon abandon abandon"))
        assertEquals(22, bits("zoo abandon zoo abandon"))
    }

    @Test
    fun seedWordsCountForNothing() {
        assertEquals(0, bits("legal winner thank year wave sausage worth", seed))
        assertEquals(22, bits("legal winner zoo abandon", seed))
    }

    @Test
    fun patternedPasswordsAreWeak() {
        assertTrue(bits("hunter2") < 40)
        assertTrue(bits("aaaaaaaaaaaaaaaaaaaa") < 40)
        assertTrue(bits("Password1234567890!") < Passphrase.MIN_BITS)
        assertTrue(bits("abcdefghijklmnopqrstuvwxyz") < 40)
    }

    @Test
    fun randomLongPasswordIsStrong() {
        assertTrue(bits("q7#Lm!x2Vr9@kPz4&Wt8") >= Passphrase.MIN_BITS)
    }

    @Test
    fun lengthLimitsTheCheck() {
        // Only password[0 until length] counts: the rest of a buffer is ignored.
        val buf = "zoo abandon ability able legal winner".toCharArray()
        assertEquals(44, Passphrase.estimateBits(buf, 24))
        assertFalse(Passphrase.sharesWords(buf, 24, seed))
        assertTrue(Passphrase.sharesWords(buf, buf.size, seed))
    }

    @Test
    fun sharesWords() {
        assertTrue(Passphrase.sharesWords("my Legal password".toCharArray(), seed))
        assertFalse(Passphrase.sharesWords("zoo abandon ability able".toCharArray(), seed))
        assertFalse(Passphrase.sharesWords("legalese".toCharArray(), seed))
    }
}
