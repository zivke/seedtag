// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PasswordVariantsTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun utf8(h: String) = String(hex(h), Charsets.UTF_8)

    /** Same file test_seedtag.py reads, generated with seedtag.py: both must retry the same passwords. */
    @Test
    fun variantsMatchSeedtagPy() {
        val rows = javaClass.getResource("/password-variants.tsv")!!.readText().lines().filter { it.isNotEmpty() }
        assertEquals(14, rows.size)
        for (row in rows) {
            val cols = row.split("\t")
            val input = utf8(cols[0])
            val expected = cols.drop(1).chunked(2).map { (fix, v) -> fix to utf8(v) }
            val got = SeedBox.passwordVariants(input.toCharArray()).map { (fix, v) -> fix.name to String(v) }
            assertEquals("for \"$input\"", expected, got)
        }
    }

    // First row of seedtag-v1-vectors.tsv: password "correct horse battery staple".
    private val vector = javaClass.getResource("/seedtag-v1-vectors.tsv")!!.readText().lines().first().split("\t")
    private val blob = SeedBox.fromBase64(vector[4])
    private val entropy = hex(vector[1])

    private fun opened(pw: String) = SeedBox.openForgiving(blob, pw.toCharArray())

    @Test
    fun forgivesSpacesAndCapitals() {
        assertEquals(PasswordFix.None, opened("correct horse battery staple").fix)
        assertEquals(PasswordFix.Spaces, opened(" correct  horse battery staple ").fix)
        assertEquals(PasswordFix.Case, opened("Correct Horse Battery Staple").fix)
        val both = opened("  Correct horse\tbattery  staple")
        assertEquals(PasswordFix.SpacesAndCase, both.fix)
        assertArrayEquals(entropy, both.entropy)
    }

    @Test
    fun stillRejectsAWrongPassword() {
        for (pw in listOf("correct horse battery", "Correct  horse battery stapler")) {
            try {
                opened(pw)
                fail("\"$pw\" must not open the blob")
            } catch (_: SeedTagException.WrongPassword) {
            }
        }
    }

    @Test
    fun loneSurrogateIsAPasswordError() {
        try {
            SeedBox.passwordBytes(charArrayOf('a', '\uD800', 'b'))
            fail("encoded a lone surrogate")
        } catch (_: SeedTagException.BadPassword) {
        }
    }

    @Test
    fun straySpacesUseTheSameWhitespace() {
        // U+0085 (NEL) is whitespace for seedtag.py, so it must be stray here too.
        assertEquals(true, Passphrase.hasStraySpaces("a\u0085b".toCharArray()))
    }
}
