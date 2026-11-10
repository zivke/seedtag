// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import java.security.MessageDigest

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

    /** Position of [word] in the list, or -1. */
    fun indexOf(word: String): Int = index[word] ?: -1

    /**
     * Position of the word spelled by `chars[from until to]` (lower-cased first if [lowercase]),
     * or -1. Works on the char array directly, so no String copy of a password is made.
     */
    fun indexOf(chars: CharArray, from: Int, to: Int, lowercase: Boolean = false): Int {
        var lo = 0
        var hi = wordlist.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(wordlist[mid], chars, from, to, lowercase)
            when {
                c == 0 -> return mid
                c < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    /** Like [suggest], for the lower-case prefix `chars[from until to]`, without a String copy of it. */
    fun suggest(chars: CharArray, from: Int, to: Int, limit: Int = 8): List<String> {
        if (from >= to) return emptyList()
        var lo = 0
        var hi = wordlist.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(wordlist[mid], chars, from, to, false) < 0) lo = mid + 1 else hi = mid
        }
        val n = to - from
        val out = ArrayList<String>(limit)
        var i = lo
        while (i < wordlist.size && out.size < limit) {
            val w = wordlist[i]
            if (w.length < n || (0 until n).any { k -> w[k] != chars[from + k] }) break
            out += w; i++
        }
        return out
    }

    /** String order of [word] against the char slice, as String.compareTo would give. */
    private fun compare(word: String, chars: CharArray, from: Int, to: Int, lowercase: Boolean): Int {
        val n = to - from
        for (k in 0 until minOf(word.length, n)) {
            val c = chars[from + k].let { if (lowercase) it.lowercaseChar() else it }
            if (word[k] != c) return word[k] - c
        }
        return word.length - n
    }

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
