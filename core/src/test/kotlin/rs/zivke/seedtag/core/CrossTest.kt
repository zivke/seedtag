// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

/**
 * The app's side of `make test-cross` (board B47): opens the random backups `tools/cross_test.py` sealed, and
 * seals its own for it to open. Only runs when the directory `seedtag.cross` is set; `make test` skips it.
 */
class CrossTest {
    private val dir = System.getProperty("seedtag.cross").orEmpty()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun bothWays() {
        assumeTrue("make test-cross sets seedtag.cross", dir.isNotEmpty())
        val fromPython = File(dir, "py.tsv").readLines().filter { it.isNotBlank() }.map { it.split("\t") }
        assertTrue(fromPython.size >= 10)
        for ((pw, entropy, text) in fromPython) {
            val password = String(hex(pw), Charsets.UTF_8).toCharArray()
            assertArrayEquals(hex(entropy), SeedBox.open(SeedBox.fromBase64(text), password))
        }
        val random = SecureRandom()
        val lines = (0 until 12).map { i ->
            val entropy = ByteArray(intArrayOf(16, 20, 24, 28, 32)[i % 5]).also(random::nextBytes)
            // Random code points, none of them whitespace, some outside the BMP.
            val pw = buildString {
                repeat(1 + random.nextInt(30)) {
                    val cp = when (random.nextInt(4)) {
                        0 -> 0x21 + random.nextInt(0x5e)
                        1 -> 0xa1 + random.nextInt(0x15f)
                        2 -> 0x4e00 + random.nextInt(0x500)
                        else -> 0x1f300 + random.nextInt(0x200)
                    }
                    appendCodePoint(cp)
                }
            }
            val text = SeedBox.toBase64(SeedBox.seal(entropy, pw.toCharArray()))
            "${pw.toByteArray(Charsets.UTF_8).hex()}\t${entropy.hex()}\t$text"
        }
        File(dir, "kt.tsv").writeText(lines.joinToString("\n") + "\n")
        println("the app's core opened ${fromPython.size} backups from seedtag.py and sealed ${lines.size}")
    }
}
