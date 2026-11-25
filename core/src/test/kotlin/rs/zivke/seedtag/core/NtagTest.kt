// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtagTest {

    private fun hex(s: String) = s.replace(" ", "").let { h -> ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }

    // Pages 0-3 and the GET_VERSION reply from memory dumps of real tags (Flipper .nfc / NFC Tools).
    private val nxpHead = hex("04 8D B4 B5  B2 A5 1C 90  9B 48 00 00  E1 10 12 00")
    private val otherMakerHead = hex("53 5D F1 77  FB 93 00 01  69 48 00 00  E1 10 12 00")
    private val ntag213Version = hex("00 04 04 02 01 00 0F 03")
    /** NTAG213 pages 29h-2Ch at the factory defaults, as an NXP chip reads them. */
    private val factory = hex("04 00 00 FF  00 05 00 00  00 00 00 00  00 00 00 00")

    @Test
    fun identifiesByCapabilityContainerAndVersion() {
        assertEquals(NtagIdentity(NtagChip.Ntag213, genuine = true), NtagIdentity.of(nxpHead, ntag213Version))
        // Another maker's chip that answers like an NTAG213: supported, but not genuine.
        assertEquals(NtagIdentity(NtagChip.Ntag213, genuine = false), NtagIdentity.of(otherMakerHead, ntag213Version))
        // Shanghai Feiju's NTAG213 (debug chip test, 2026-11-25): its own maker code in the version too.
        val feijuVersion = hex("00 53 04 02 01 00 0F 03")
        assertEquals(NtagIdentity(NtagChip.Ntag213, genuine = false), NtagIdentity.of(otherMakerHead, feijuVersion))
        assertEquals(NtagIdentity(NtagChip.Ntag213, genuine = false), NtagIdentity.of(nxpHead, feijuVersion))
        // The size byte and the version must agree.
        assertNull(NtagIdentity.of(nxpHead, hex("00 04 04 02 01 00 11 03")))
        assertEquals(NtagChip.Ntag215, NtagIdentity.of(nxpHead.copyOf().also { it[14] = 0x3E }, hex("00 04 04 02 01 00 11 03"))!!.chip)
        assertEquals(NtagChip.Ntag216, NtagIdentity.of(nxpHead.copyOf().also { it[14] = 0x6D }, hex("00 04 04 02 01 00 13 03"))!!.chip)
        // No version (the chip didn't answer GET_VERSION), another chip's version, no NDEF, an unknown size.
        assertNull(NtagIdentity.of(nxpHead, null))
        assertNull(NtagIdentity.of(nxpHead, hex("00 04 03 01 01 00 0B 03"))) // MIFARE Ultralight EV1
        assertNull(NtagIdentity.of(nxpHead.copyOf().also { it[12] = 0 }, ntag213Version))
        assertNull(NtagIdentity.of(nxpHead.copyOf().also { it[14] = 0x06 }, ntag213Version))
    }

    // Pages from the debug chip test (2026-11-25), READ from 00h, 28h and 2Ch: a lookalike without
    // GET_VERSION and with one extra page (2Dh, a read counter), and the Feiju lookalike with 45 pages.
    private val counterHead = hex("1D 97 B7 B5  22 09 10 80  BB C0 00 00  E1 10 12 00")
    private val counter28 = hex("00 00 00 BD  00 00 00 FF  00 00 00 00  00 00 00 00")
    private val counter2C = hex("00 00 00 00  00 00 00 00  1D 97 B7 B5  22 09 10 80")
    private val feijuHead = hex("53 95 F8 B6  FB 93 00 01  69 48 00 00  E1 10 12 00")
    private val feiju28 = hex("00 00 00 BD  04 00 00 FF  00 05 00 00  00 00 00 00")
    private val feiju2C = hex("00 00 00 00  53 95 F8 B6  FB 93 00 01  69 48 00 00")

    /** READ as a chip with these pages answers it; any other page fails the test. */
    private fun reads(vararg pages: Pair<Int, ByteArray>): (Int) -> ByteArray = { page ->
        pages.firstOrNull { it.first == page }?.second ?: throw AssertionError("READ of page %02Xh".format(page))
    }

    @Test
    fun recognisesLookalikesByTheirLayout() {
        val counter = NtagIdentity(NtagChip.Ntag213, genuine = false, extraPage = true)
        assertEquals(counter, NtagIdentity.byLayout(counterHead, reads(0x28 to counter28, 0x2C to counter2C)))
        assertEquals(0x2D, counter.lastPage)
        val feiju = NtagIdentity.byLayout(feijuHead, reads(0x28 to feiju28, 0x2C to feiju2C))
        assertEquals(NtagIdentity(NtagChip.Ntag213, genuine = false), feiju)
        // No BDh: the last page isn't even read (a MIFARE Ultralight C has key pages there).
        assertNull(NtagIdentity.byLayout(counterHead, reads(0x28 to counter28.copyOf().also { it[3] = 0 })))
        // Not wrapping where an NTAG213 ends (48 pages), an unknown size, no capability container.
        assertNull(NtagIdentity.byLayout(counterHead, reads(0x28 to counter28, 0x2C to ByteArray(16))))
        assertNull(NtagIdentity.byLayout(counterHead.copyOf().also { it[14] = 0x06 }, reads()))
        assertNull(NtagIdentity.byLayout(counterHead.copyOf().also { it[12] = 0 }, reads()))
    }

    @Test
    fun theVersionDecidesWhetherItsNxps() {
        val layout = NtagIdentity(NtagChip.Ntag213, genuine = false)
        // NXP's code in the serial number and the version, NXP's page count: genuine.
        assertEquals(NtagIdentity(NtagChip.Ntag213, true), NtagIdentity.combine(nxpHead, layout, ntag213Version))
        // Its own maker code (the Feiju), no answer at all, an extra page: a lookalike.
        assertEquals(layout, NtagIdentity.combine(feijuHead, layout, hex("00 53 04 02 01 00 0F 03")))
        assertEquals(layout, NtagIdentity.combine(feijuHead, layout, null))
        val extra = layout.copy(extraPage = true)
        assertEquals(extra, NtagIdentity.combine(nxpHead, extra, ntag213Version))
        // A version that isn't an NTAG21x's, or another size's: not a chip SeedTag knows.
        assertNull(NtagIdentity.combine(nxpHead, layout, hex("00 04 03 01 01 00 0B 03")))
        assertNull(NtagIdentity.combine(nxpHead, layout, hex("00 04 04 02 01 00 11 03")))
    }

    @Test
    fun aLookalikesSettings() {
        // Unused NTAG-only bytes (00h) at the factory: AUTH0 FFh, ACCESS 00h.
        val extra = NtagIdentity(NtagChip.Ntag213, genuine = false, extraPage = true)
        val c = NtagConfig(extra, hex("00 00 00 FF  00 00 00 00  00 00 00 00  00 00 00 00"))
        assertEquals(Protection.Off, c.protection)
        assertTrue(c.writesOnly)
        assertArrayEquals(hex("00000003"), c.cfg0With(NtagConfig.AUTH0_ON))
        assertArrayEquals(hex("00000000"), c.cfg1WritesOnly())
        // Its PACK reads back once stored (fudan_pwd_pack_stored.txt): not its password, which reads as zero.
        val stored = NtagConfig(extra, hex("00 00 00 FF  00 00 00 00  00 00 00 00  AA A8 00 00"))
        assertFalse(stored.passwordReadable)
        assertTrue(stored.packReadable)
        assertTrue(stored.packIs(hex("AAA8")))
        assertFalse(stored.packIs(hex("AAA9")))
        assertTrue(NtagConfig(extra, hex("00 00 00 FF  00 00 00 00  12 34 56 78  00 00 00 00")).passwordReadable)
        // Read again, it's still the lookalike's: AUTH0 2Dh on.
        assertEquals(Protection.Write, stored.reread(hex("000000 2D  00000000  00000000 00000000")).protection)
        // Its last page is 2Dh: AUTH0 2Dh is still on.
        assertEquals(Protection.Write, NtagConfig(extra, hex("000000 2D  00000000  00000000 00000000")).protection)
        assertEquals(Protection.Off, NtagConfig(extra, hex("000000 2E  00000000  00000000 00000000")).protection)
    }

    @Test
    fun pagesFollowTheDatasheet() {
        assertEquals(listOf(0x28, 0x29, 0x2B, 0x2C), NtagChip.Ntag213.let { listOf(it.dynLockPage, it.cfgPage, it.pwdPage, it.packPage) })
        assertEquals(listOf(0x82, 0x83, 0x85, 0x86), NtagChip.Ntag215.let { listOf(it.dynLockPage, it.cfgPage, it.pwdPage, it.packPage) })
        assertEquals(listOf(0xE2, 0xE3, 0xE5, 0xE6), NtagChip.Ntag216.let { listOf(it.dynLockPage, it.cfgPage, it.pwdPage, it.packPage) })
    }

    @Test
    fun factorySettings() {
        val c = NtagConfig(NtagChip.Ntag213, factory)
        assertEquals(Protection.Off, c.protection)
        assertTrue(c.writesOnly)
        assertFalse(c.settingsLocked)
        assertFalse(c.mirrorOn) // MIRROR 04h is STRG_MOD_EN, not a mirror
        assertFalse(c.passwordReadable)
        assertFalse(c.packReadable)
    }

    @Test
    fun protectionStates() {
        fun with(cfg0: String, cfg1: String) = NtagConfig(NtagChip.Ntag213, hex(cfg0 + cfg1 + "00000000 00000000"))
        assertEquals(Protection.Write, with("040000 03", "00050000").protection)
        assertEquals(Protection.ReadWrite, with("040000 03", "80050000").protection)
        assertEquals(Protection.Write, with("040000 2C", "00050000").protection) // only the PACK page, but on
        assertEquals(Protection.Off, with("040000 2D", "00050000").protection)
        assertFalse(with("040000 FF", "03050000").writesOnly) // AUTHLIM 3: wrong passwords would lock it
        assertTrue(with("040000 FF", "40050000").settingsLocked)
        assertTrue(with("440000 FF", "00050000").mirrorOn) // MIRROR_CONF 01b: the UID mirror
        assertTrue(with("040000 FF", "00050000").let { !it.passwordReadable })
    }

    @Test
    fun settingsSeedTagWrites() {
        // Writes only, no lock-out; the NFC counter bits (10h, 08h) stay as they were.
        val c = NtagConfig(NtagChip.Ntag213, hex("04000 0FF  9F050000  00000000 00000000".replace(" ", "")))
        assertArrayEquals(hex("18050000"), c.cfg1WritesOnly())
        assertArrayEquals(hex("04000003"), c.cfg0With(NtagConfig.AUTH0_ON))
        // A mirror a torn write switched on goes off again; STRG_MOD_EN (04h) and the RFU byte stay.
        val torn = NtagConfig(NtagChip.Ntag213, hex("F4 7E 2A 03  00050000  00000000 00000000".replace(" ", "")))
        assertArrayEquals(hex("047E00FF"), torn.cfg0With(NtagConfig.AUTH0_OFF))
        val f = NtagConfig(NtagChip.Ntag213, factory)
        assertTrue(f.settingsAre(hex("040000FF"), hex("00050000")))
        assertFalse(f.settingsAre(hex("040000FF"), hex("00050001")))
        assertFalse(f.settingsAre(hex("440000FF"), hex("00050000")))
    }
}
