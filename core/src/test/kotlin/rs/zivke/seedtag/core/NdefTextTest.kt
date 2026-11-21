// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** NdefText: decoding a Text record's payload, and what Verify and Restore say about one SeedTag didn't write exactly. */
class NdefTextTest {

    /** The 24-word vector's text, exactly as SeedTag writes it. */
    private val text = javaClass.getResource("/seedtag-v1-vectors.tsv")!!.readText().lines().first().split("\t")[4]

    private fun payload(status: Int, lang: String, body: ByteArray) =
        byteArrayOf(status.toByte()) + lang.toByteArray(Charsets.US_ASCII) + body

    @Test
    fun seedTagsOwnRecordHasNone() {
        assertEquals(emptyList<String>(), NdefText.differences(NdefText.encodePayload(text)))
        assertEquals(text, NdefText.decodePayload(NdefText.encodePayload(text)))
    }

    @Test
    fun eachDifferenceIsNamed() {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        assertEquals(listOf("a language code other than en"), NdefText.differences(payload(2, "de", utf8)))
        assertEquals(listOf("a language code other than en"), NdefText.differences(payload(0, "", utf8)))
        assertEquals(listOf("a language code other than en"), NdefText.differences(payload(5, "en-GB", utf8)))
        assertEquals(listOf("a reserved bit set in the record"), NdefText.differences(payload(0x42, "en", utf8)))
        assertEquals(
            listOf("the text stored as UTF-16"),
            NdefText.differences(payload(0x82, "en", text.toByteArray(Charsets.UTF_16))),
        )
        assertEquals(
            listOf("text that isn't exactly what SeedTag writes (spaces or line breaks in it, or a different ending)"),
            NdefText.differences(NdefText.encodePayload(text.dropLast(1))),
        )
        assertEquals(
            listOf("a language code other than en", "text that isn't exactly what SeedTag writes (spaces or line breaks in it, or a different ending)"),
            NdefText.differences(payload(2, "fr", (text.substring(0, 50) + "\n" + text.substring(50)).toByteArray())),
        )
    }

    @Test
    fun decodesUtf16AndRefusesBrokenPayloads() {
        // UTF-16 as Java writes it (byte-order mark, big-endian), and little-endian with its mark.
        assertEquals(text, NdefText.decodePayload(payload(0x82, "en", text.toByteArray(Charsets.UTF_16))))
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, NdefText.decodePayload(payload(0x82, "en", le)))
        // Empty, and a language code longer than the payload.
        assertThrows(SeedTagException.Malformed::class.java) { NdefText.decodePayload(ByteArray(0)) }
        assertThrows(SeedTagException.Malformed::class.java) { NdefText.decodePayload(byteArrayOf(0x05, 0x65, 0x6E)) }
        // Status byte only, no language and no text: an empty text.
        assertEquals("", NdefText.decodePayload(byteArrayOf(0x00)))
    }
}
