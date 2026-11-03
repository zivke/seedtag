// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Minimal BIP39 (English) encoder/decoder: mnemonic words <-> raw entropy.
 * No seed derivation (PBKDF2) – SeedTag only stores the entropy.
 */
object Bip39 {

    val wordlist: List<String> by lazy {
        val stream = Bip39::class.java.getResourceAsStream("/rs/zivke/seedtag/core/bip39-english.txt")
            ?: error("bip39-english.txt missing from resources")
        val list = stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        check(list.size == 2048) { "wordlist has ${list.size} words" }
        list
    }

    private val index: Map<String, Int> by lazy {
        wordlist.withIndex().associate { (i, w) -> w to i }
    }

    val validWordCounts = setOf(12, 15, 18, 21, 24)
    val validEntropyLengths = setOf(16, 20, 24, 28, 32)

    fun isWord(word: String): Boolean = word in index

    /** Up to [limit] words starting with [prefix] (lower-case), in list order. */
    fun suggest(prefix: String, limit: Int = 8): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        // wordlist is sorted -> binary search for the first candidate
        var lo = 0
        var hi = wordlist.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (wordlist[mid] < p) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<String>(limit)
        var i = lo
        while (i < wordlist.size && out.size < limit && wordlist[i].startsWith(p)) {
            out += wordlist[i]; i++
        }
        return out
    }

    /** Splits free-form input into normalized lower-case words. */
    fun normalize(input: String): List<String> =
        Normalizer.normalize(input, Normalizer.Form.NFKD)
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

    fun entropyToWords(entropy: ByteArray): List<String> {
        require(entropy.size in validEntropyLengths) { "bad entropy length ${entropy.size}" }
        val csBits = entropy.size * 8 / 32
        val hash = sha256(entropy)
        val buf = entropy.copyOf(entropy.size + 1)
        buf[entropy.size] = hash[0]
        try {
            val n = (entropy.size * 8 + csBits) / 11
            return List(n) { i -> wordlist[readBits(buf, i * 11, 11)] }
        } finally {
            buf.fill(0); hash.fill(0)
        }
    }

    /** @throws SeedTagException on bad count, unknown word or checksum failure. */
    fun wordsToEntropy(words: List<String>): ByteArray {
        if (words.size !in validWordCounts) throw SeedTagException.BadWordCount(words.size)
        val totalBits = words.size * 11
        val csBits = totalBits / 33
        val entLen = (totalBits - csBits) / 8
        val buf = ByteArray((totalBits + 7) / 8)
        try {
            words.forEachIndexed { i, w ->
                val idx = index[w] ?: throw SeedTagException.UnknownWord(i + 1)
                writeBits(buf, i * 11, 11, idx)
            }
            val entropy = buf.copyOf(entLen)
            val hash = sha256(entropy)
            val expected = (hash[0].toInt() and 0xFF) ushr (8 - csBits)
            val actual = readBits(buf, entLen * 8, csBits)
            hash.fill(0)
            if (expected != actual) {
                entropy.fill(0)
                throw SeedTagException.BadChecksum()
            }
            return entropy
        } finally {
            buf.fill(0)
        }
    }

    private fun readBits(buf: ByteArray, bitOffset: Int, count: Int): Int {
        var v = 0
        for (k in 0 until count) {
            val bit = bitOffset + k
            val b = (buf[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1
            v = (v shl 1) or b
        }
        return v
    }

    private fun writeBits(buf: ByteArray, bitOffset: Int, count: Int, value: Int) {
        for (k in 0 until count) {
            val bit = bitOffset + k
            if ((value ushr (count - 1 - k)) and 1 == 1) {
                val i = bit ushr 3
                buf[i] = (buf[i].toInt() or (0x80 ushr (bit and 7))).toByte()
            }
        }
    }

    internal fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
