// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

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

    /**
     * How a Text record [payload] differs from the one SeedTag writes (UTF-8, language "en", the exact
     * Base64 of the blob), each as the end of "This tag has …". Empty if it doesn't. The language code
     * isn't quoted: it could be anything, up to 63 bytes.
     */
    fun differences(payload: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val status = payload[0].toInt() and 0xFF
        if (status and 0x80 != 0) out += "the text stored as UTF-16"
        if (status and 0x40 != 0) out += "a reserved bit set in the record"
        val lang = payload.copyOfRange(1, minOf(payload.size, 1 + (status and 0x3F)))
        if (!lang.contentEquals("en".toByteArray(Charsets.US_ASCII))) out += "a language code other than en"
        if (!SeedBox.isExactText(decodePayload(payload))) {
            out += "text that isn't exactly what SeedTag writes (spaces or line breaks in it, or a different ending)"
        }
        return out
    }

    /** Same layout as NdefRecord.createTextRecord("en", text) – used for size estimates and tests. */
    fun encodePayload(text: String, lang: String = "en"): ByteArray {
        val l = lang.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(l.size.toByte()) + l + text.toByteArray(Charsets.UTF_8)
    }

    /** Bytes the whole NDEF message needs for a short Text record (header 3 + type 1 + payload). */
    fun messageSize(text: String): Int = 3 + 1 + encodePayload(text).size
}
