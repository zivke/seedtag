// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

/**
 * The TLV blocks in an NFC Forum Type 2 tag's data area (MIFARE Ultralight, NTAG), which starts at
 * page 4. Used to double-check a tag that every NDEF read called empty before writing to it without
 * asking: a read that fails while the tag stays in range looks just like an empty tag.
 */
object Type2Tlv {
    private const val NULL = 0x00
    private const val LOCK_CONTROL = 0x01
    private const val NDEF = 0x03
    private const val TERMINATOR = 0xFE

    /** Where the data area starts in the tag's memory: page 4. */
    private const val DATA_START = 16

    /**
     * Length of the NDEF message in [data] (the bytes from page 4 on), skipping NULL, Lock Control,
     * Memory Control and proprietary TLVs. 0 if the TLVs end before an NDEF TLV. Null if [data] ends
     * first or a length runs past it: the caller can't tell.
     */
    fun ndefLength(data: ByteArray): Int? {
        var i = 0
        while (i < data.size) {
            val type = data[i].toInt() and 0xFF
            if (type == NULL) { i++; continue }
            if (type == TERMINATOR) return 0
            if (i + 1 >= data.size) return null
            var length = data[i + 1].toInt() and 0xFF
            var header = 2
            if (length == 0xFF) { // three-byte length: FF, then 16 bits big-endian
                if (i + 3 >= data.size) return null
                length = (data[i + 2].toInt() and 0xFF shl 8) or (data[i + 3].toInt() and 0xFF)
                header = 4
            }
            if (type == NDEF) return length
            i += header + length
        }
        return null
    }

    /**
     * Where the free bytes start in [data] (the whole data area from page 4, as long as the capability
     * container says): just after the TLVs' terminator, or [data]'s size if the NDEF message runs to
     * the end. Android's write leaves whatever was there before, and Back up clears it. Null if
     * SeedTag can't be sure those bytes are free: no NDEF TLV, a length running past the end, a TLV
     * other than NULL, Lock Control and NDEF (a Memory Control TLV reserves bytes in the data area),
     * or a Lock Control TLV whose lock bytes lie inside the data area.
     */
    fun freeAfterTerminator(data: ByteArray): Int? {
        var i = 0
        var ndef = false
        while (i < data.size) {
            val type = data[i].toInt() and 0xFF
            if (type == NULL) { i++; continue }
            if (type == TERMINATOR) {
                return if (ndef) i + 1 else null
            }
            if (type != NDEF && type != LOCK_CONTROL) return null
            if (i + 1 >= data.size) return null
            var length = data[i + 1].toInt() and 0xFF
            var header = 2
            if (length == 0xFF) {
                if (i + 3 >= data.size) return null
                length = (data[i + 2].toInt() and 0xFF shl 8) or (data[i + 3].toInt() and 0xFF)
                header = 4
            }
            if (i + header + length > data.size) return null
            if (type == LOCK_CONTROL) {
                if (length != 3) return null
                val v = IntArray(3) { data[i + header + it].toInt() and 0xFF }
                // Position = page address << major offset size + byte offset; size in bits (0 = 256).
                val position = (v[0] shr 4 shl (v[2] and 0x0F)) + (v[0] and 0x0F)
                val bytes = ((if (v[1] == 0) 256 else v[1]) + 7) / 8
                if (position < DATA_START + data.size && position + bytes > DATA_START) return null
            } else {
                ndef = true
            }
            i += header + length
        }
        return if (ndef) data.size else null // the message runs to the end: nothing after it
    }
}
