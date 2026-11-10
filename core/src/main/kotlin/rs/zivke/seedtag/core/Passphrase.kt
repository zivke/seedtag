// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import java.security.SecureRandom

/**
 * Random passphrase from the BIP39 list: 11 bits per word (7 words ≈ 77 bits), and a rough
 * strength estimate. Nothing here makes a String out of a password: words are looked up
 * straight from the char array.
 */
object Passphrase {
    const val DEFAULT_WORDS = 7
    /** Below this estimate the app asks for confirmation before writing a tag. */
    const val MIN_BITS = 60

    /** [words] distinct random BIP39 words, none of them in [exclude] (the seed being backed up). */
    fun generate(
        words: Int = DEFAULT_WORDS,
        exclude: Set<String> = emptySet(),
        random: SecureRandom = SecureRandom(),
    ): CharArray {
        require(words in 4..24 && exclude.size <= 24)
        val picked = LinkedHashSet<String>() // the list's own String objects, no copies
        while (picked.size < words) {
            val w = Bip39.wordlist[random.nextInt(2048)]
            if (w !in exclude) picked += w
        }
        val out = CharArray(picked.sumOf { it.length } + words - 1) { ' ' }
        var at = 0
        for (w in picked) {
            w.toCharArray(out, at)
            at += w.length + 1
        }
        return out
    }

    fun estimateBits(password: CharArray, avoid: Set<String> = emptySet()): Int =
        estimateBits(password, password.size, avoid)

    /**
     * Rough strength estimate of `password[0 until length]`, used only for the UI warning and the
     * confirmation before writing.
     *
     * Four or more BIP39 words count 11 bits each, as if picked at random, but only distinct ones
     * and none from [avoid] (the seed being backed up). Anything else counts per character from the
     * alphabets used, discounted for human patterns; a character that repeats or continues a run
     * from the one before it (aa, 12, cb) counts 1 bit. There's no dictionary check.
     */
    fun estimateBits(password: CharArray, length: Int, avoid: Set<String> = emptySet()): Int {
        if (length == 0) return 0
        val wordIndexes = wordIndexes(password, length, lowercase = false)
        if (wordIndexes != null && wordIndexes.size >= 4 && wordIndexes.all { it >= 0 }) {
            val avoided = avoid.map(Bip39::indexOf).toSet()
            return (wordIndexes.toSet() - avoided).size * 11
        }
        var lower = false; var upper = false; var digit = false; var other = false
        for (i in 0 until length) {
            val c = password[i]
            when {
                c.isLowerCase() -> lower = true
                c.isUpperCase() -> upper = true
                c.isDigit() -> digit = true
                !c.isLetterOrDigit() -> other = true
            }
        }
        val pool = (if (lower) 26 else 0) + (if (upper) 26 else 0) + (if (digit) 10 else 0) + (if (other) 33 else 0)
        val perChar = Math.log(pool.coerceAtLeast(2).toDouble()) / Math.log(2.0) * 0.6 // discount for human patterns
        var bits = 0.0
        for (i in 0 until length) {
            val patterned = i > 0 && password[i] - password[i - 1] in -1..1
            bits += if (patterned) 1.0 else perChar
        }
        return bits.toInt()
    }

    fun hasStraySpaces(password: CharArray): Boolean = hasStraySpaces(password, password.size)

    /**
     * True if `password[0 until length]` isn't already in the form [SeedBox.passwordVariants] retries:
     * it starts or ends with whitespace, has two in a row, or has whitespace other than a plain space.
     * A new password like that is refused, because a written copy rarely shows it and the forgiving
     * retries only ever take spaces away. Same rule as has_stray_spaces() in seedtag.py.
     */
    fun hasStraySpaces(password: CharArray, length: Int): Boolean {
        for (i in 0 until length) {
            val c = password[i]
            if (!c.isWhitespace()) continue
            if (c != ' ' || i == 0 || i == length - 1 || password[i - 1].isWhitespace()) return true
        }
        return false
    }

    fun sharesWords(password: CharArray, seedWords: Set<String>): Boolean =
        sharesWords(password, password.size, seedWords)

    /** True if any whitespace-separated word of `password[0 until length]` is one of [seedWords] (any case). */
    fun sharesWords(password: CharArray, length: Int, seedWords: Set<String>): Boolean {
        if (seedWords.isEmpty()) return false
        val seed = seedWords.map(Bip39::indexOf).toSet()
        return wordIndexes(password, length, lowercase = true).orEmpty().any { it >= 0 && it in seed }
    }

    /** Wordlist index (or -1) of each whitespace-separated token; null if there are no tokens. */
    private fun wordIndexes(chars: CharArray, length: Int, lowercase: Boolean): List<Int>? {
        val out = ArrayList<Int>()
        var i = 0
        while (i < length) {
            while (i < length && chars[i].isWhitespace()) i++
            val start = i
            while (i < length && !chars[i].isWhitespace()) i++
            if (i > start) out += Bip39.indexOf(chars, start, i, lowercase)
        }
        return out.ifEmpty { null }
    }
}
