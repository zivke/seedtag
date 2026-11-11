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

    /** Bits per word of a passphrase from [generate]: one of 2048, picked at random. */
    const val RANDOM_WORD_BITS = 11
    /** Bits per word the user chose: people pick common words and natural phrases, far from random. */
    const val CHOSEN_WORD_BITS = 6

    fun estimateBits(password: CharArray, avoid: Set<String> = emptySet(), random: Boolean = false): Int =
        estimateBits(password, password.size, avoid, random)

    /**
     * Rough strength estimate of `password[0 until length]`, used only for the UI warning and the
     * confirmation before writing. It's an upper bound at best: there's no dictionary beyond the BIP39
     * list, so a well-known phrase made of other words can still score far above its real strength.
     * The app therefore only calls the generated passphrase strong.
     *
     * The password is split into tokens: runs of letters, runs of digits, and single other characters
     * (whitespace only separates, and counts nothing). Case doesn't matter, a token that already
     * appeared counts nothing, and a password that repeats a shorter pattern counts the pattern once.
     * - If [random] (the password is exactly what [generate] produced) and it's four or more BIP39
     *   words, each distinct word not in [avoid] (the seed being backed up) counts [RANDOM_WORD_BITS].
     * - Otherwise a BIP39 word counts [CHOSEN_WORD_BITS], 1 bit if it's next to the word before it in
     *   the list (abandon ability able), and nothing if it's in [avoid].
     * - Any other token counts per character, from the alphabets used in the whole password and
     *   discounted for human patterns. A token that repeats a shorter pattern (iloveyouiloveyou) counts
     *   the pattern once, and a character next to the one before it (aa, 12, cb) counts 1 bit.
     */
    fun estimateBits(password: CharArray, length: Int, avoid: Set<String> = emptySet(), random: Boolean = false): Int {
        if (length == 0) return 0
        val tokens = tokens(password, length)
        if (tokens.isEmpty()) return 0
        val avoided = avoid.map(Bip39::indexOf).toSet()
        val wordIndex = IntArray(tokens.size) { t ->
            val (start, end, kind) = tokens[t]
            if (kind == LETTERS) Bip39.indexOf(password, start, end, true) else -1
        }
        if (random && tokens.size >= 4 && wordIndex.all { it >= 0 }) {
            return (wordIndex.toSet() - avoided).size * RANDOM_WORD_BITS
        }
        // The whole password repeating a shorter pattern (x7!qx7!q) counts the pattern once.
        val whole = period(password, 0, length)
        if (whole < length) return estimateBits(password, whole, avoid)

        var lower = false; var upper = false; var digit = false; var other = false
        for (i in 0 until length) {
            val c = password[i]
            when {
                c.isWhitespace() -> {}
                c.isLowerCase() -> lower = true
                c.isUpperCase() -> upper = true
                c.isDigit() -> digit = true
                else -> other = true
            }
        }
        val pool = (if (lower) 26 else 0) + (if (upper) 26 else 0) + (if (digit) 10 else 0) + (if (other) 33 else 0)
        val perChar = Math.log(pool.coerceAtLeast(2).toDouble()) / Math.log(2.0) * 0.6 // discount for human patterns

        var bits = 0.0
        var prevWord = -2
        for (t in tokens.indices) {
            val (start, end, _) = tokens[t]
            if ((0 until t).any { sameToken(password, tokens[it], tokens[t]) }) continue
            val w = wordIndex[t]
            if (w >= 0) {
                bits += when {
                    w in avoided -> 0
                    w - prevWord == 1 || prevWord - w == 1 -> 1
                    else -> CHOSEN_WORD_BITS
                }
                prevWord = w
                continue
            }
            prevWord = -2
            val counted = start + period(password, start, end)
            for (i in start until counted) {
                val patterned = i > start && password[i] - password[i - 1] in -1..1
                bits += if (patterned) 1.0 else perChar
            }
        }
        return bits.toInt()
    }

    private const val LETTERS = 0
    private const val DIGITS = 1
    private const val OTHER = 2

    /** Tokens of `chars[0 until length]` as (start, end, kind); whitespace only separates them. */
    private fun tokens(chars: CharArray, length: Int): List<Triple<Int, Int, Int>> {
        fun kind(c: Char) = when {
            c.isLetter() -> LETTERS
            c.isDigit() -> DIGITS
            else -> OTHER
        }
        val out = ArrayList<Triple<Int, Int, Int>>()
        var i = 0
        while (i < length) {
            if (chars[i].isWhitespace()) { i++; continue }
            val k = kind(chars[i])
            val start = i++
            if (k != OTHER) while (i < length && !chars[i].isWhitespace() && kind(chars[i]) == k) i++
            out += Triple(start, i, k)
        }
        return out
    }

    /** The same characters, ignoring case. */
    private fun sameToken(chars: CharArray, a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Boolean {
        val n = a.second - a.first
        if (n != b.second - b.first) return false
        for (k in 0 until n) if (chars[a.first + k].lowercaseChar() != chars[b.first + k].lowercaseChar()) return false
        return true
    }

    /** Length of the shortest pattern that `chars[start until end]` repeats (the whole token if none). */
    private fun period(chars: CharArray, start: Int, end: Int): Int {
        val n = end - start
        for (p in 1 until n) {
            if ((start + p until end).all { chars[it].lowercaseChar() == chars[it - p].lowercaseChar() }) return p
        }
        return n
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

    /** True if any word of `password[0 until length]` (a run of letters, any case) is one of [seedWords]. */
    fun sharesWords(password: CharArray, length: Int, seedWords: Set<String>): Boolean {
        if (seedWords.isEmpty()) return false
        val seed = seedWords.map(Bip39::indexOf).toSet()
        return tokens(password, length).any { (start, end, kind) ->
            kind == LETTERS && Bip39.indexOf(password, start, end, true).let { it >= 0 && it in seed }
        }
    }
}
