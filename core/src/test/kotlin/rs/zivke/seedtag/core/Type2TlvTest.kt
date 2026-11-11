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
}
