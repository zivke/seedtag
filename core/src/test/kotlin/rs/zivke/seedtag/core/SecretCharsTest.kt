// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class SecretCharsTest {

    private fun secret(s: String) = SecretChars().apply { s.forEach { append(it) } }

    /** The live buffer, to check what's left in it. */
    private fun raw(s: SecretChars): CharArray {
        val f = SecretChars::class.java.getDeclaredField("buf").apply { isAccessible = true }
        return f.get(s) as CharArray
    }

    @Test
    fun editInPlace() {
        val s = secret("abc")
        s.removeLast()
        assertEquals("ab", s.reveal())
        assertEquals('\u0000', raw(s)[2]) // the removed char is wiped, not just hidden
        s.append('z')
        assertEquals("abz", s.reveal())
    }

    @Test
    fun capacityIsEnforced() {
        val s = SecretChars(3)
        assertTrue(s.append('a') && s.append('b') && s.append('c'))
        assertFalse(s.append('d'))
        assertEquals(3, s.length)
        assertFalse(s.replaceFrom(0, "abcd"))
    }

    @Test
    fun clearZeroesEverything() {
        val s = secret("correct horse")
        s.clear()
        assertEquals(0, s.length)
        assertTrue(raw(s).all { it == '\u0000' })
    }

    @Test
    fun completeLastWord() {
        val s = secret("zoo aban")
        assertEquals(4, s.lastTokenStart())
        s.replaceFrom(s.lastTokenStart(), "abandon")
        assertEquals("zoo abandon", s.reveal())
        s.append(' ')
        assertEquals(s.length, s.lastTokenStart())
    }

    @Test
    fun setCopyAndEquals() {
        val src = "pässwörd ☃".toCharArray()
        val a = SecretChars().apply { set(src) }
        val b = secret("pässwörd ☃")
        assertTrue(a.contentEquals(b))
        assertArrayEquals(src, a.copy())
        b.removeLast()
        assertFalse(a.contentEquals(b))
        assertEquals("SecretChars(length=10)", a.toString()) // never the content
    }

    @Test
    fun charArrayLookupsMatchStringOnes() {
        for (w in Bip39.wordlist) {
            val c = "  $w ".toCharArray()
            assertEquals(Bip39.indexOf(w), Bip39.indexOf(c, 2, 2 + w.length))
            assertEquals(Bip39.indexOf(w), Bip39.indexOf(w.uppercase().toCharArray(), 0, w.length, lowercase = true))
        }
        for (s in listOf("abando", "abandons", "zzz", "", "Abandon")) {
            assertEquals(-1, Bip39.indexOf(s.toCharArray(), 0, s.length))
        }
        for (p in listOf("a", "ab", "aba", "zo", "zoo", "q", "x", "zz")) {
            assertEquals(Bip39.suggest(p, 6), Bip39.suggest(p.toCharArray(), 0, p.length, 6))
        }
    }

    @Test
    fun asciiPasswordBytesSkipNormalizer() {
        val ascii = (32..126).map { it.toChar() }.joinToString("")
        val expected = Normalizer.normalize(ascii, Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, SeedBox.passwordBytes(ascii.toCharArray()))
        assertArrayEquals(
            Normalizer.normalize("pässwörd ☃", Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8),
            SeedBox.passwordBytes("pässwörd ☃".toCharArray()),
        )
    }
}
