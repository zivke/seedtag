// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

/**
 * NXP NTAG213/215/216 (datasheet NTAG213/215/216, section 8.5), and NTAG21x lookalikes from other makers
 * with the same layout: the tables and the settings arithmetic for the tag's write password ([TagKey]),
 * without any I/O. [cfgPage] is CFG0; CFG1, PWD and PACK follow it, the dynamic lock bytes precede it.
 * [versionSize]: the size byte of its GET_VERSION reply. [lastPage]: an NXP chip's last page.
 */
enum class NtagChip(val label: String, val ccSize: Int, val versionSize: Int, val cfgPage: Int, val lastPage: Int) {
    Ntag213("NTAG213", 0x12, 0x0F, 0x29, 0x2C),
    Ntag215("NTAG215", 0x3E, 0x11, 0x83, 0x86),
    Ntag216("NTAG216", 0x6D, 0x13, 0xE3, 0xE6);

    val dynLockPage: Int get() = cfgPage - 1
    val pwdPage: Int get() = cfgPage + 2
    val packPage: Int get() = cfgPage + 3

    companion object {
        /**
         * GET_VERSION: an NTAG21x answers 00, its maker's code (04 for NXP), 04 02 01 00, its size byte, 03.
         * Lookalikes put their own maker's code there (Shanghai Feiju's NTAG213: 00 53 04 02 01 00 0F 03).
         */
        val GET_VERSION = byteArrayOf(0x60)

        /** The NTAG21x a capability container's size byte ([cc] byte 2) says it is, if it's one of them. */
        fun forCcSize(cc: Int): NtagChip? = entries.firstOrNull { it.ccSize == cc }

        /** Whether [reply] to GET_VERSION is what an NTAG21x of [chip]'s size answers, from any maker. */
        fun versionMatches(chip: NtagChip, reply: ByteArray): Boolean =
            reply.size == 8 && reply[0] == 0.toByte() && reply.copyOfRange(2, 6).contentEquals(byteArrayOf(4, 2, 1, 0)) &&
                (reply[6].toInt() and 0xFF) == chip.versionSize && reply[7] == 3.toByte()

        /** NXP's manufacturer code, the first byte of every NXP serial number (ISO/IEC 7816-6). */
        const val NXP = 0x04
    }
}

/** Whether the tag's write password is in force, and for what. */
enum class Protection {
    Off,
    /** Writes need the password, reads don't: what SeedTag sets. */
    Write,
    /** Reads need it too. SeedTag never sets this. */
    ReadWrite,
}

/**
 * The 16 bytes READ returns from an NTAG21x's CFG0 page: CFG0 (MIRROR, RFUI, MIRROR_PAGE, AUTH0),
 * CFG1 (ACCESS, RFUI ×3), PWD and PACK (both always read as 00h on an NXP chip). On a lookalike, bytes an
 * NTAG21x uses for its mirror may be unused (00h); SeedTag writes them back as they were, mirror off.
 * [lastPage]: the chip's last page; AUTH0 above it means protection is off.
 */
class NtagConfig(private val lastPage: Int, bytes: ByteArray) {
    constructor(chip: NtagChip, bytes: ByteArray) : this(chip.lastPage, bytes)
    constructor(identity: NtagIdentity, bytes: ByteArray) : this(identity.lastPage, bytes)

    private val b = bytes.copyOf(16).also { require(bytes.size >= 16) }
    private fun u(i: Int) = b[i].toInt() and 0xFF

    val cfg0: ByteArray get() = b.copyOfRange(0, 4)
    val cfg1: ByteArray get() = b.copyOfRange(4, 8)
    val auth0: Int get() = u(3)
    private val access: Int get() = u(4)

    val protection: Protection
        get() = when {
            auth0 > lastPage -> Protection.Off
            (access and PROT) != 0 -> Protection.ReadWrite
            else -> Protection.Write
        }

    /** Protection, when on, covers writes only, and no number of wrong passwords locks anyone out. */
    val writesOnly: Boolean get() = (access and (PROT or AUTHLIM)) == 0

    /** CFG0 and CFG1 can never be changed again (CFGLCK). SeedTag never sets it. */
    val settingsLocked: Boolean get() = (access and CFGLCK) != 0

    /**
     * The UID or NFC counter mirror is on: the chip then shows those in place of the data at
     * MIRROR_PAGE, which corrupts what's read there. SeedTag never turns it on; a torn CFG0 write can.
     */
    val mirrorOn: Boolean get() = (u(0) and MIRROR_CONF) != 0

    /**
     * PWD reads as anything but zero: the chip shows its password to anyone, so a write password on it
     * would protect nothing. NXP's chips and the lookalikes seen so far read it as 00h.
     */
    val passwordReadable: Boolean get() = (8 until 12).any { u(it) != 0 }

    /**
     * PACK, the 2-byte reply to the right password, reads back as stored. NXP's chips return 00h; a
     * lookalike may not (the FM11NT021's datasheet gives PACK plain read access). Harmless: it isn't the
     * password, and knowing it doesn't let anyone write.
     */
    val packReadable: Boolean get() = (12 until 14).any { u(it) != 0 }

    /** Whether the PACK that reads back is [pack]. */
    fun packIs(pack: ByteArray): Boolean = pack.size == 2 && u(12) == (pack[0].toInt() and 0xFF) && u(13) == (pack[1].toInt() and 0xFF)

    /** The same page of the same chip, read again. */
    fun reread(bytes: ByteArray): NtagConfig = NtagConfig(lastPage, bytes)

    /** CFG1 for protection on writes only: PROT and AUTHLIM 0, the NFC counter bits as they were. */
    fun cfg1WritesOnly(): ByteArray = cfg1.also { it[0] = (access and (NFC_CNT_EN or NFC_CNT_PWD_PROT)).toByte() }

    /**
     * CFG0 with [auth0], the mirror off and its page 0, the rest as it is (STRG_MOD_EN, the RFU bits). SeedTag
     * always writes CFG0 this way, so a torn write that switched a mirror on is put right by the next one.
     */
    fun cfg0With(auth0: Int): ByteArray = cfg0.also {
        it[0] = (u(0) and (MIRROR_CONF or MIRROR_BYTE).inv()).toByte()
        it[2] = 0
        it[3] = auth0.toByte()
    }

    /** Whether CFG0 and CFG1 are exactly [cfg0] and [cfg1]: what was written, nothing torn. */
    fun settingsAre(cfg0: ByteArray, cfg1: ByteArray): Boolean = this.cfg0.contentEquals(cfg0) && this.cfg1.contentEquals(cfg1)

    companion object {
        /** AUTH0 SeedTag sets: everything from the capability container on (page 3) needs the password to write. */
        const val AUTH0_ON = 0x03
        /** AUTH0 for protection off: past the last page. */
        const val AUTH0_OFF = 0xFF

        private const val PROT = 0x80
        private const val CFGLCK = 0x40
        private const val NFC_CNT_EN = 0x10
        private const val NFC_CNT_PWD_PROT = 0x08
        private const val AUTHLIM = 0x07
        private const val MIRROR_CONF = 0xC0
        private const val MIRROR_BYTE = 0x30
    }
}

/**
 * Which chip a tag is, as far as SeedTag can tell. [genuine]: NXP's own, which means NXP's code is both
 * where its serial number starts and in its GET_VERSION reply, and it has exactly an NXP chip's pages.
 * Anything else with the NTAG21x layout is a lookalike ([byLayout]). Another maker's chip can claim to be
 * NXP's, so [genuine] false is certain, true only likely (NXP's originality signature isn't checked).
 * [extraPage]: one page after the NTAG21x's last (a lookalike's read counter), [lastPage] then one more.
 */
data class NtagIdentity(val chip: NtagChip, val genuine: Boolean, val extraPage: Boolean = false) {
    val lastPage: Int get() = chip.lastPage + if (extraPage) 1 else 0

    companion object {
        /**
         * From the tag's first READ (pages 0-3: serial number, lock bytes, capability container) and its
         * GET_VERSION [version] reply; null if it isn't an NTAG21x or a chip that answers like one.
         */
        fun of(head: ByteArray, version: ByteArray?): NtagIdentity? {
            if (head.size < 16 || (head[12].toInt() and 0xFF) != 0xE1 || version == null) return null
            val chip = NtagChip.forCcSize(head[14].toInt() and 0xFF) ?: return null
            if (!NtagChip.versionMatches(chip, version)) return null
            return NtagIdentity(
                chip, genuine = (head[0].toInt() and 0xFF) == NtagChip.NXP && (version[1].toInt() and 0xFF) == NtagChip.NXP,
            )
        }

        /**
         * A chip with an NTAG21x's layout, from READs alone, whatever its maker and whether or not it has
         * GET_VERSION: [head] (pages 0-3) has a capability container of an NTAG21x's size; the dynamic lock
         * page's byte 3 is the fixed BDh; and READ from the last page wraps around to page 0 exactly where an
         * NTAG21x of that size ends, or one page later ([extraPage]). [read] is READ (16 bytes from a page),
         * only of pages the capability container says exist, and the last one only once BDh is there.
         * Always [genuine] false: only GET_VERSION can tell NXP's own ([of]).
         */
        fun byLayout(head: ByteArray, read: (Int) -> ByteArray): NtagIdentity? {
            if (head.size < 16 || (head[12].toInt() and 0xFF) != 0xE1) return null
            val chip = NtagChip.forCcSize(head[14].toInt() and 0xFF) ?: return null
            val lock = read(chip.dynLockPage)
            if (lock.size < 4 || (lock[3].toInt() and 0xFF) != 0xBD) return null
            val last = read(chip.lastPage)
            if (last.size < 16) return null
            return when {
                last.copyOfRange(4, 16).contentEquals(head.copyOfRange(0, 12)) -> NtagIdentity(chip, genuine = false)
                last.copyOfRange(8, 16).contentEquals(head.copyOfRange(0, 8)) -> NtagIdentity(chip, genuine = false, extraPage = true)
                else -> null
            }
        }

        /**
         * The chip [layout] found, with what GET_VERSION said: [version] its reply, null if it refused or
         * didn't answer. A reply that isn't an NTAG21x's makes it no chip SeedTag knows (null).
         */
        fun combine(head: ByteArray, layout: NtagIdentity, version: ByteArray?): NtagIdentity? {
            if (version == null) return layout
            val claimed = of(head, version) ?: return null
            if (claimed.chip != layout.chip) return null
            return layout.copy(genuine = claimed.genuine && !layout.extraPage)
        }
    }
}

/**
 * What Verify reports about the chip itself. [identity] is null for a tag SeedTag can't protect (not an
 * NTAG21x, or one it couldn't identify on this tap); the other fields mean nothing then.
 */
data class TagInfo(val identity: NtagIdentity?, val protection: Protection = Protection.Off, val settingsLocked: Boolean = false,
                   val mirrorOn: Boolean = false)
