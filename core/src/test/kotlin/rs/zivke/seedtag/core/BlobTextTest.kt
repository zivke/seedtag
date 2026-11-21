// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SeedBox.fromBase64 and isExactText against blob-text.tsv, the same file seedtag.py's blob_from_text()
 * and text_is_exact() are tested with.
 */
class BlobTextTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test
    fun matchesPython() {
        val rows = javaClass.getResource("/blob-text.tsv")!!.readText().lines().filter { it.isNotBlank() }
        assertTrue(rows.size >= 20)
        for (row in rows) {
            val (what, input, expected, exact) = row.split("\t")
            val text = String(hex(input), Charsets.UTF_8)
            assertEquals("exact: $what", exact == "yes", SeedBox.isExactText(text))
            if (expected == "error") {
                try {
                    SeedBox.fromBase64(text)
                    fail("accepted: $what")
                } catch (_: SeedTagException.Malformed) {
                }
            } else {
                assertEquals(what, expected, SeedBox.fromBase64(text).toHex())
            }
        }
    }
}
