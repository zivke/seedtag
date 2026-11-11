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
    private const val NDEF = 0x03
    private const val TERMINATOR = 0xFE

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
}
