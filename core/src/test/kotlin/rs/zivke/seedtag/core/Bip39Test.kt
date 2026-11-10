// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class Bip39Test {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Official Trezor reference vectors (python-mnemonic/vectors.json, English). */
    @Test
    fun trezorVectors() {
        val lines = javaClass.getResource("/bip39-vectors.tsv")!!.readText().lines().filter { it.isNotBlank() }
        assertEquals(24, lines.size)
        for (line in lines) {
            val (ent, words) = line.split("\t")
            assertEquals(words, Bip39.entropyToWords(hex(ent)).joinToString(" "))
            assertArrayEquals(hex(ent), Bip39.wordsToEntropy(words.split(" ")))
        }
    }

    @Test
    fun randomRoundTrip() {
        val rnd = SecureRandom()
        for (len in Bip39.validEntropyLengths) repeat(200) {
            val e = ByteArray(len).also(rnd::nextBytes)
            assertArrayEquals(e, Bip39.wordsToEntropy(Bip39.entropyToWords(e)))
        }
    }

    @Test
    fun badChecksum() {
        try {
            Bip39.wordsToEntropy(List(24) { "abandon" }); fail()
        } catch (e: SeedTagException.BadChecksum) { }
    }

    @Test
    fun unknownWordReportsPosition() {
        val w = MutableList(12) { "abandon" }; w[4] = "bitcoinz"
        try {
            Bip39.wordsToEntropy(w); fail()
        } catch (e: SeedTagException.UnknownWord) { assertEquals(5, e.position) }
    }

    @Test
    fun badCount() {
        try {
            Bip39.wordsToEntropy(List(13) { "abandon" }); fail()
        } catch (e: SeedTagException.BadWordCount) { }
    }

    @Test
    fun suggestAndUniquePrefixes() {
        assertEquals(listOf("abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract"), Bip39.suggest("ab"))
        assertEquals(listOf("zoo"), Bip39.suggest("zoo"))
        assertTrue(Bip39.suggest("xyz").isEmpty())
        // BIP39 guarantees the first 4 letters identify a word
        assertEquals(2048, Bip39.wordlist.map { it.take(4) }.toSet().size)
        Bip39.wordlist.forEach { assertEquals(it, Bip39.suggest(it.take(4), 1).single()) }
    }
}
