// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

import java.security.SecureRandom

/** Random passphrase from the BIP39 list: 11 bits per word (7 words ≈ 77 bits). */
object Passphrase {
    const val DEFAULT_WORDS = 7

    fun generate(words: Int = DEFAULT_WORDS, random: SecureRandom = SecureRandom()): CharArray {
        require(words in 4..24)
        val picked = List(words) { Bip39.wordlist[random.nextInt(2048)] }
        return picked.joinToString(" ").toCharArray()
    }

    /** Rough entropy estimate for user-typed passwords, used only for a UI warning. */
    fun estimateBits(password: CharArray): Int {
        if (password.isEmpty()) return 0
        val text = String(password)
        val words = text.trim().split(Regex("\\s+"))
        if (words.size >= 4 && words.all { Bip39.isWord(it) }) return words.size * 11
        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 33
        val perChar = Math.log(pool.coerceAtLeast(2).toDouble()) / Math.log(2.0)
        return (password.size * perChar * 0.6).toInt() // discount for human patterns
    }
}
