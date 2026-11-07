// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

/**
 * A password held in one fixed char array that is edited in place and zeroed by [clear], instead
 * of an immutable String (every edit of a String leaves another unwipeable copy on the heap).
 *
 * The content only leaves as a [copy] for one operation (the caller zeroes it), through [use]
 * for in-place checks, or through [reveal] when the user asks to see it.
 */
class SecretChars(val capacity: Int = 128) {
    private val buf = CharArray(capacity)

    var length = 0
        private set

    fun isEmpty(): Boolean = length == 0

    /** Appends [c]; false if the buffer is full. */
    fun append(c: Char): Boolean {
        if (length >= capacity) return false
        buf[length++] = c
        return true
    }

    fun removeLast() {
        if (length > 0) buf[--length] = '\u0000'
    }

    /** Replaces the content with [chars] (cut to [capacity]). The caller still owns and zeroes [chars]. */
    fun set(chars: CharArray) {
        clear()
        length = minOf(chars.size, capacity)
        chars.copyInto(buf, 0, 0, length)
    }

    /** Zeroes the whole buffer. */
    fun clear() {
        buf.fill('\u0000')
        length = 0
    }

    /** Index where the last space-separated token starts ([length] if the content ends with a space). */
    fun lastTokenStart(): Int {
        var i = length
        while (i > 0 && buf[i - 1] != ' ') i--
        return i
    }

    /** Replaces everything from [start] to the end with [word], e.g. to complete a BIP39 word. */
    fun replaceFrom(start: Int, word: String): Boolean {
        require(start in 0..length)
        if (start + word.length > capacity) return false
        buf.fill('\u0000', start, length)
        word.toCharArray(buf, start)
        length = start + word.length
        return true
    }

    /** A copy for one operation. The caller must zero it. */
    fun copy(): CharArray = buf.copyOf(length)

    /** Runs [block] on the live buffer and its length. [block] must not keep or copy the array. */
    fun <T> use(block: (chars: CharArray, length: Int) -> T): T = block(buf, length)

    /** Same length and content, compared without an early exit. */
    fun contentEquals(other: SecretChars): Boolean {
        if (length != other.length) return false
        var diff = 0
        for (i in 0 until length) diff = diff or (buf[i].code xor other.buf[i].code)
        return diff == 0
    }

    /** The content as a String, only for showing it on screen when the user asks. */
    fun reveal(): String = String(buf, 0, length)

    /** Never the content. */
    override fun toString(): String = "SecretChars(length=$length)"
}
