// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Type2TlvTest {

    private fun bytes(hex: String) = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun emptyTags() {
        assertEquals(0, Type2Tlv.ndefLength(bytes("03 00 fe 00 00 00 00 00 00 00 00 00 00 00 00 00")))
        // Ultralight C formatted by NXP's tools: a Lock Control TLV first.
        assertEquals(0, Type2Tlv.ndefLength(bytes("01 03 a0 10 44 03 00 fe 00 00 00 00 00 00 00 00")))
        // NTAG213 as NXP ships it: its own Lock Control TLV (as on the tested tag), NDEF message empty.
        assertEquals(0, Type2Tlv.ndefLength(bytes("01 03 a0 0c 34 03 00 fe 00 00 00 00 00 00 00 00")))
        // Only a terminator, or NULL padding before it.
        assertEquals(0, Type2Tlv.ndefLength(bytes("fe 00 00 00")))
        assertEquals(0, Type2Tlv.ndefLength(bytes("00 00 fe 03 10")))
    }

    @Test
    fun tagsWithData() {
        // A Text record holding a 104-character backup: 3 header + 1 type + 3 "\u0002en" + 104 = 111 bytes.
        assertEquals(111, Type2Tlv.ndefLength(bytes("03 6f d1 01 6b 54 02 65 6e 41 51 49 44")))
        assertEquals(111, Type2Tlv.ndefLength(bytes("01 03 a0 10 44 03 6f d1 01 6b 54 02 65 6e 41 51")))
        // Memory Control and proprietary TLVs are skipped too; a long NDEF TLV has a 3-byte length.
        assertEquals(300, Type2Tlv.ndefLength(bytes("02 03 f0 02 03 fd 02 aa bb 03 ff 01 2c d1")))
        // An empty record (some tools format tags this way) is data as far as the TLV goes.
        assertEquals(3, Type2Tlv.ndefLength(bytes("03 03 d0 00 00 fe")))
    }

    @Test
    fun cantTell() {
        assertNull(Type2Tlv.ndefLength(ByteArray(0)))
        assertNull(Type2Tlv.ndefLength(bytes("00 00 00 00")))
        assertNull(Type2Tlv.ndefLength(bytes("01")))
        assertNull(Type2Tlv.ndefLength(bytes("03 ff 01")))
        assertNull(Type2Tlv.ndefLength(bytes("01 20 00 00 00 00"))) // a length that runs past the data
    }
    /** A 144-byte data area (NTAG213, Ultralight C): [head], then [tail] repeated to the end. */
    private fun area(head: String, tail: Int = 0x41) =
        bytes(head).let { h -> h + ByteArray(144 - h.size) { tail.toByte() } }

    @Test
    fun freeBytesStartAfterTheTerminator() {
        // NTAG213's Lock Control TLV (its lock bytes at page 28h, outside the data area), a 4-byte
        // message, the terminator, then old text.
        assertEquals(12, Type2Tlv.freeAfterTerminator(area("01 03 a0 0c 34 03 04 d1 01 00 54 fe 00")))
        // Ultralight C's Lock Control TLV, no Lock Control TLV at all, NULL padding, a three-byte length.
        assertEquals(8, Type2Tlv.freeAfterTerminator(area("01 03 a0 10 44 03 00 fe")))
        assertEquals(3, Type2Tlv.freeAfterTerminator(area("03 00 fe")))
        assertEquals(5, Type2Tlv.freeAfterTerminator(area("00 00 03 00 fe")))
        assertEquals(7, Type2Tlv.freeAfterTerminator(area("03 ff 00 02 d0 00 fe")))
    }

    @Test
    fun aMessageToTheEndLeavesNothingFree() {
        assertEquals(144, Type2Tlv.freeAfterTerminator(bytes("03 8e") + ByteArray(142) { 0x41 }))
    }

    @Test
    fun refusesWhatItCantBeSureOf() {
        assertNull(Type2Tlv.freeAfterTerminator(area("fe")))                     // no NDEF TLV
        assertNull(Type2Tlv.freeAfterTerminator(area("01 03 a0 0c 34 fe")))      // still none
        assertNull(Type2Tlv.freeAfterTerminator(area("02 03 10 04 22 03 00 fe"))) // Memory Control
        assertNull(Type2Tlv.freeAfterTerminator(area("fd 01 00 03 00 fe")))       // proprietary
        assertNull(Type2Tlv.freeAfterTerminator(area("01 03 30 10 44 03 00 fe"))) // lock bytes at 48: inside
        assertNull(Type2Tlv.freeAfterTerminator(area("01 02 a0 0c 03 00 fe")))    // odd Lock Control length
        assertNull(Type2Tlv.freeAfterTerminator(bytes("03 20 d1 01")))            // runs past the end
        assertNull(Type2Tlv.freeAfterTerminator(area("03 ff 01")))                // cut-off length
    }
}
