// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of tag I/O: which tag an overwrite or erase is bound to, the erase filler, chip size, content. */
class TagModelTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun overwriteTargetIsTheSameTagWithTheSameContent() {
        val uid = bytes(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val msg = bytes(0xD1, 0x01, 0x03, 0x54, 0x02, 0x65, 0x6E)
        val t = OverwriteTarget(uid, msg)
        assertTrue(t.matches(uid.copyOf(), msg.copyOf()))
        assertFalse(t.matches(bytes(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x67), msg)) // another tag
        assertFalse(t.matches(uid, msg.copyOf(6))) // other content
        assertFalse(t.matches(null, msg))
        // A random UID (4 bytes from 08h) changes on every tap, and an unknown one can't be compared: content only.
        val random = OverwriteTarget(bytes(0x08, 1, 2, 3), msg)
        assertTrue(random.matches(bytes(0x08, 9, 9, 9), msg))
        assertFalse(random.matches(bytes(0x08, 1, 2, 3), bytes(0)))
        assertTrue(OverwriteTarget(ByteArray(0), msg).matches(uid, msg))
        // No message now matches a target that had none.
        assertTrue(OverwriteTarget(uid, ByteArray(0)).matches(uid, null))
        assertFalse(t.matches(uid, null))
    }

    @Test
    fun fillerFillsTheNdefArea() {
        // NTAG213/215/216 and the sizes around the end of the short record format.
        for (max in listOf(3, 46, 137, 255, 258, 262, 496, 868)) {
            assertEquals("max $max", max, EraseFiller.messageSize(EraseFiller.payloadFor(max)))
        }
        // Just above 255: a long record needs 3 more bytes, so up to 3 stay as they were.
        assertEquals(255, EraseFiller.payloadFor(259))
        assertEquals(258, EraseFiller.messageSize(255))
        assertEquals(255, EraseFiller.payloadFor(261))
        assertEquals(0, EraseFiller.payloadFor(2)) // below a record's 3 bytes: the smallest there is
    }

    @Test
    fun fillerIsRecognised() {
        val zeros = ByteArray(134)
        assertTrue(EraseFiller.isFiller(1, EraseFiller.TNF_UNKNOWN, ByteArray(0), zeros))
        assertFalse(EraseFiller.isFiller(2, EraseFiller.TNF_UNKNOWN, ByteArray(0), zeros))
        assertFalse(EraseFiller.isFiller(1, 1, ByteArray(0), zeros))
        assertFalse(EraseFiller.isFiller(1, EraseFiller.TNF_UNKNOWN, bytes(0x54), zeros))
        assertFalse(EraseFiller.isFiller(1, EraseFiller.TNF_UNKNOWN, ByteArray(0), zeros.copyOf().also { it[133] = 1 }))
    }

    /** A chip of [pages] pages whose READ wraps around, as Type 2 chips do; page p holds p in every byte. */
    private fun chip(pages: Int): (Int) -> ByteArray = { from ->
        ByteArray(16) { i -> ((from + i / 4) % pages).toByte() }
    }

    @Test
    fun wrapPageGivesTheChipsSize() {
        for (pages in listOf(16, 20, 41, 42, 44, 45, 60, 135, 231)) assertEquals("$pages pages", pages, Type2Memory.wrapPage(chip(pages)))
        assertNull(Type2Memory.wrapPage(chip(1000))) // no wrap where any known chip ends
        assertNull(Type2Memory.wrapPage { ByteArray(4) }) // a short read
        val probed = mutableListOf<Int>()
        Type2Memory.wrapPage { probed += it; chip(45)(it) }
        assertEquals(listOf(0, 0x0D, 0x11, 0x27, 0x2A), probed) // stops at the wrap: never asks for pages past it
    }

    @Test
    fun contentByFirstText() {
        val v1 = javaClass.getResource("/seedtag-v1-vectors.tsv")!!.readText().lines().first().split("\t")[4]
        val newer = SeedBox.toBase64(SeedBox.fromBase64(v1).also { it[0] = 2 })
        assertEquals(TagContent.Empty, TagContent.of(true, v1))
        assertEquals(TagContent.Backup, TagContent.of(false, v1))
        assertEquals(TagContent.NewerBackup, TagContent.of(false, newer))
        assertEquals(TagContent.Other, TagContent.of(false, "https://example.com"))
        assertEquals(TagContent.Other, TagContent.of(false, null))
    }

    @Test
    fun tagCheckSaysWhetherABackupFits() {
        val ntag213 = TagCheck(supported = true, ndef = true, capacity = 137)
        assertEquals(24, ntag213.maxWords)
        assertTrue(ntag213.usable)
        assertEquals(12, ntag213.copy(capacity = 91).maxWords)
        assertTrue(ntag213.copy(capacity = 90).tooSmall)
        assertFalse(ntag213.copy(capacity = 90).usable)
        assertFalse(ntag213.copy(writable = false).usable)
        assertTrue(ntag213.copy(capacity = null).usable) // size unknown: allowed, checked when writing
    }

    @Test
    fun backAction() {
        assertEquals(BackAction.ClosePage, BackAction.of(pageOpen = true, onWordsStep = true, onPasswordStep = false, holdsSecrets = true))
        assertEquals(BackAction.BackToLength, BackAction.of(false, onWordsStep = true, onPasswordStep = false, holdsSecrets = true))
        assertEquals(BackAction.BackToWords, BackAction.of(false, false, onPasswordStep = true, holdsSecrets = false))
        assertEquals(BackAction.AskToLeave, BackAction.of(false, false, false, holdsSecrets = true))
        assertNull(BackAction.of(false, false, false, false))
    }

    @Test
    fun type2Commands() {
        assertArrayEquals(bytes(0x30, 0x03), Type2Memory.readCommand(3))
        assertArrayEquals(bytes(0xA2, 0x1D, 0, 0, 0, 0), Type2Memory.writeCommand(0x1D, ByteArray(4)))
        assertThrows(IllegalArgumentException::class.java) { Type2Memory.writeCommand(4, ByteArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { Type2Memory.readCommand(256) }
    }

    /**
     * An NTAG213's data area (144 bytes, pages 4 to 27h) as a tested NTAG213-compatible chip held it:
     * the factory Lock Control TLV, a 12-word backup (91-byte message) written over a longer text record
     * that repeated "empty", whose end Android left after the terminator.
     */
    private fun overwritten(): ByteArray {
        val text = javaClass.getResource("/seedtag-v1-vectors.tsv")!!.readText().lines()[1].split("\t")[4]
        assertEquals(84, text.length)
        val message = bytes(0xD1, 0x01, 0x57, 0x54, 0x02, 0x65, 0x6E) + text.toByteArray(Charsets.US_ASCII)
        // Android pads the terminator's page with zeros; the old text goes on from the next page.
        val head = bytes(0x01, 0x03, 0xA0, 0x0C, 0x34, 0x03, message.size) + message + bytes(0xFE, 0x00)
        return head + ("mpty" + "empty".repeat(30)).toByteArray(Charsets.US_ASCII).copyOf(144 - head.size)
    }

    @Test
    fun freePagesAreAfterTheTerminatorsPage() {
        val data = overwritten()
        assertEquals(144, data.size)
        // The terminator is in page 1Ch, as on the chip; pages 1Dh to 27h still held "empty…".
        assertEquals(0xFE.toByte(), data[4 * (0x1C - 4) + 2])
        assertEquals(0x1D..0x27, Type2Memory.freePages(data))
        assertFalse(Type2Memory.allZero(data, 0x1D..0x27))
        assertArrayEquals("mpty".toByteArray(), Type2Memory.page(data, 0x1D))
        // Cleared: zeros from page 1Dh to the end, the backup and its page untouched.
        val cleared = data.copyOf().also { it.fill(0, 4 * (0x1D - 4), it.size) }
        assertTrue(Type2Memory.allZero(cleared, 0x1D..0x27))
        assertFalse(Type2Memory.allZero(cleared, 0x1C..0x27))
        // An erased NTAG213: the empty message, then zeros from page 7.
        val erased = bytes(0x01, 0x03, 0xA0, 0x0C, 0x34, 0x03, 0x04, 0xD8, 0, 0, 0, 0xFE) + ByteArray(132)
        assertEquals(7..0x27, Type2Memory.freePages(erased))
        // A message to the very end leaves nothing; an odd layout leaves nothing to write.
        val full = bytes(0x03, 0x8E) + ByteArray(142)
        assertTrue(Type2Memory.freePages(full)!!.isEmpty())
        assertNull(Type2Memory.freePages(bytes(0x02, 0x03, 0, 0, 0) + data.copyOfRange(5, 144))) // Memory Control TLV
    }
}
