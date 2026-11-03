// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

/** NFC Forum Text RTD payload parsing (encoding is done by android.nfc.NdefRecord.createTextRecord). */
object NdefText {
    /** Returns the text of a Text record payload: status byte | lang code | text. */
    fun decodePayload(payload: ByteArray): String {
        if (payload.isEmpty()) throw SeedTagException.Malformed("empty text record")
        val status = payload[0].toInt() and 0xFF
        val utf16 = status and 0x80 != 0
        val langLen = status and 0x3F
        if (1 + langLen > payload.size) throw SeedTagException.Malformed("bad text record")
        val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
        return String(payload, 1 + langLen, payload.size - 1 - langLen, charset)
    }

    /** Same layout as NdefRecord.createTextRecord("en", text) – used for size estimates and tests. */
    fun encodePayload(text: String, lang: String = "en"): ByteArray {
        val l = lang.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(l.size.toByte()) + l + text.toByteArray(Charsets.UTF_8)
    }

    /** Bytes the whole NDEF message needs for a short Text record (header 3 + type 1 + payload). */
    fun messageSize(text: String): Int = 3 + 1 + encodePayload(text).size
}
