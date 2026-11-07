// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

import java.security.SecureRandom

/** Random passphrase from the BIP39 list: 11 bits per word (7 words ≈ 77 bits). */
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
        val picked = LinkedHashSet<String>()
        while (picked.size < words) {
            val w = Bip39.wordlist[random.nextInt(2048)]
            if (w !in exclude) picked += w
        }
        return picked.joinToString(" ").toCharArray()
    }

    /**
     * Rough strength estimate, used only for the UI warning and the confirmation before writing.
     *
     * Four or more BIP39 words count 11 bits each, as if picked at random, but only distinct ones
     * and none from [avoid] (the seed being backed up). Anything else counts per character from the
     * alphabets used, discounted for human patterns; a character that repeats or continues a run
     * from the one before it (aa, 12, cb) counts 1 bit. There's no dictionary check.
     */
    fun estimateBits(password: CharArray, avoid: Set<String> = emptySet()): Int {
        if (password.isEmpty()) return 0
        val words = String(password).trim().split(Regex("\\s+"))
        if (words.size >= 4 && words.all { Bip39.isWord(it) }) return (words.toSet() - avoid).size * 11
        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 33
        val perChar = Math.log(pool.coerceAtLeast(2).toDouble()) / Math.log(2.0) * 0.6 // discount for human patterns
        var bits = 0.0
        for (i in password.indices) {
            val patterned = i > 0 && password[i] - password[i - 1] in -1..1
            bits += if (patterned) 1.0 else perChar
        }
        return bits.toInt()
    }

    /** True if any whitespace-separated word of [password] is one of [seedWords] (any case). */
    fun sharesWords(password: CharArray, seedWords: Set<String>): Boolean =
        String(password).lowercase().split(Regex("\\s+")).any { it in seedWords }
}
