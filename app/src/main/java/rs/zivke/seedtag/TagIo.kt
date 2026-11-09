// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import rs.zivke.seedtag.core.NdefText
import rs.zivke.seedtag.core.SeedBox
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What the UI wants done with the next tag that touches the phone. */
sealed interface TagRequest {
    /** First Back up step: find out whether the tag can take a backup, and how big. Writes nothing. */
    data object Check : TagRequest
    /** Write [text]; refuses to overwrite a non-empty tag unless [allowOverwrite]. */
    data class Write(val text: String, val allowOverwrite: Boolean = false) : TagRequest
    /** Read the tag back and compare with [expected] (second tap after formatting a blank tag). */
    data class CheckWritten(val expected: String) : TagRequest
    data object Read : TagRequest
    /** Make the tag read-only, but only if it holds exactly [expected]. */
    data class Lock(val expected: String) : TagRequest
}

enum class TagContent { Empty, Backup, Other }

/**
 * What the first Back up step found. [supported]: the tag can hold NDEF at all. [ndef]: it's
 * NDEF-formatted; if not, it's blank and gets formatted on the first write. [capacity]: bytes
 * for the NDEF message, null if unknown; for a blank tag it's [estimated] from the chip's memory.
 */
data class TagCheck(
    val supported: Boolean,
    val ndef: Boolean,
    val capacity: Int?,
    val estimated: Boolean = false,
    val writable: Boolean = true,
    val content: TagContent = TagContent.Empty,
) {
    /** Longest backup that fits, null if none does or the size is unknown. */
    val maxWords: Int? get() = capacity?.let(SeedBox::maxWordsFor)
    val tooSmall: Boolean get() = capacity != null && maxWords == null
    val usable: Boolean get() = supported && writable && !tooSmall
}

sealed interface TagResult {
    data class Checked(val check: TagCheck) : TagResult
    /** [text] was written and read back. */
    data class Written(val text: String) : TagResult
    /** A blank tag was formatted with [text], but couldn't be read back on the same tap. */
    data class WrittenUnchecked(val text: String) : TagResult
    data class Read(val text: String) : TagResult
    data object Locked : TagResult
    /** Tag already holds data; [existing] is its text if it was a Text record. */
    data class NotEmpty(val existing: String?) : TagResult
    data class Failed(val message: String) : TagResult
}

/** Blocking tag I/O. Call only from the NFC reader-mode callback thread. */
object TagIo {

    private const val NOT_READ = "Couldn't read the tag properly (it may have moved away too soon). Hold it still against the phone and try again."

    fun perform(tag: Tag, request: TagRequest): TagResult = try {
        when (request) {
            TagRequest.Check -> check(tag)
            is TagRequest.Write -> write(tag, request)
            is TagRequest.CheckWritten -> checkWritten(tag, request.expected)
            TagRequest.Read -> read(tag)
            is TagRequest.Lock -> lock(tag, request.expected)
        }
    } catch (e: TagLostException) {
        TagResult.Failed("The tag moved away too early. Hold it still against the phone and try again.")
    } catch (e: IOException) {
        TagResult.Failed("Communication with the tag failed (${e.javaClass.simpleName}). Try again.")
    } catch (e: FormatException) {
        TagResult.Failed("The tag contains malformed NDEF data.")
    } catch (e: SecurityException) {
        TagResult.Failed("The tag is no longer in range. Try again.")
    } catch (e: rs.zivke.seedtag.core.SeedTagException) {
        TagResult.Failed(e.message ?: "Unreadable tag data.")
    }

    private fun write(tag: Tag, req: TagRequest.Write): TagResult {
        val message = NdefMessage(NdefRecord.createTextRecord("en", req.text))
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val formatable = NdefFormatable.get(tag)
                ?: return TagResult.Failed("This tag doesn't support NDEF.")
            // Never format a tag that may already hold a backup.
            if (looksNdefFormatted(tag)) return TagResult.Failed(NOT_READ)
            val needed = message.byteArrayLength
            val capacity = blankCapacity(tag)
            if (capacity != null && needed > capacity) return TagResult.Failed(tooSmall(req.text, needed, capacity, estimated = true))
            try {
                formatable.use { it.connect(); it.format(message) }
            } catch (e: TagLostException) {
                throw e
            } catch (e: IOException) {
                return TagResult.Failed(
                    "Couldn't format this blank tag. Hold it still and try again. If it keeps failing, the tag may be too " +
                        "small (this backup needs $needed bytes) or not writable.",
                )
            }
            // This Tag object only knows the techs it had when discovered, so Ndef.get()
            // stays null after formatting: the read-back has to wait for the next tap.
            return TagResult.WrittenUnchecked(req.text)
        }
        ndef.use {
            it.connect()
            if (!it.isWritable) return TagResult.Failed("This tag is read-only.")
            if (message.byteArrayLength > it.maxSize) {
                return TagResult.Failed(tooSmall(req.text, message.byteArrayLength, it.maxSize, estimated = false))
            }
            if (!req.allowOverwrite) {
                // Overwriting without asking is only OK if every read says the tag is empty.
                val existing = readMessage(it)
                if (existing != null && existing.records.any { r -> r.tnf != NdefRecord.TNF_EMPTY }) {
                    return TagResult.NotEmpty(firstText(existing))
                }
            }
            it.writeNdefMessage(message)
            // Read back over the same connection; getNdefMessage() re-reads the tag. Not the
            // cached message: that's what the tag held before this write.
            val back = it.ndefMessage ?: it.ndefMessage
                ?: return TagResult.WrittenUnchecked(req.text) // couldn't read: check on the next tap
            if (firstText(back) != req.text) return TagResult.Failed("What was read back doesn't match what was written. The tag may be faulty – don't use it.")
            return TagResult.Written(req.text)
        }
    }

    private fun check(tag: Tag): TagResult {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.use {
                it.connect()
                val msg = readMessage(it)
                val content = when {
                    msg == null || msg.records.all { r -> r.tnf == NdefRecord.TNF_EMPTY } -> TagContent.Empty
                    looksLikeBackup(runCatching { firstText(msg) }.getOrNull()) -> TagContent.Backup
                    else -> TagContent.Other
                }
                return TagResult.Checked(TagCheck(true, ndef = true, capacity = it.maxSize, writable = it.isWritable, content = content))
            }
        }
        if (NdefFormatable.get(tag) == null) return TagResult.Checked(TagCheck(supported = false, ndef = false, capacity = null))
        if (looksNdefFormatted(tag)) return TagResult.Failed(NOT_READ)
        val capacity = blankCapacity(tag)
        return TagResult.Checked(TagCheck(true, ndef = false, capacity = capacity, estimated = capacity != null))
    }

    /** A SeedTag blob: Base64 that starts with a known version byte. */
    fun looksLikeBackup(text: String?): Boolean =
        text != null && runCatching { SeedBox.fromBase64(text) }.getOrNull()?.let { it.isNotEmpty() && it[0].toInt() == SeedBox.VERSION } == true

    private fun tooSmall(text: String, needed: Int, has: Int, estimated: Boolean): String {
        val words = (runCatching { SeedBox.fromBase64(text).size }.getOrDefault(0) - SeedBox.OVERHEAD) * 3 / 4
        val fits = SeedBox.maxWordsFor(has)?.let { "It can hold a backup of up to $it words." } ?: "It's too small for any backup."
        return "This tag is too small – the $words-word backup needs $needed bytes, but the tag only has ${if (estimated) "about " else ""}" +
            "$has. $fits Nothing was written. Cancel and use a bigger tag (an NTAG215 or NTAG216, for example), or hold another tag to the phone."
    }

    private fun checkWritten(tag: Tag, expected: String): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            val msg = readMessage(it) ?: return TagResult.Failed(NOT_READ)
            if (firstText(msg) != expected) {
                return TagResult.Failed("This tag doesn't hold what was just written. Hold the same tag to the phone again. If this keeps happening, the tag may be faulty.")
            }
            return TagResult.Written(expected)
        }
    }

    private fun read(tag: Tag): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            val msg = readMessage(it) ?: return TagResult.Failed(
                "The tag is empty, or it couldn't be read properly. Hold it still against the phone and try again.",
            )
            val text = firstText(msg) ?: return TagResult.Failed("The tag holds no text record.")
            return TagResult.Read(text)
        }
    }

    private fun lock(tag: Tag, expected: String): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            // Locking is permanent: only the tag that was just written or verified.
            val msg = readMessage(it) ?: return TagResult.Failed(NOT_READ)
            if (firstText(msg) != expected) {
                return TagResult.Failed("This isn't the tag you last wrote or verified. Nothing was changed.")
            }
            if (!it.isWritable) return TagResult.Locked
            if (!it.canMakeReadOnly()) return TagResult.Failed("This tag can't be made read-only.")
            return if (it.makeReadOnly()) TagResult.Locked else TagResult.Failed("Locking failed. The tag is unchanged.")
        }
    }

    /**
     * getNdefMessage() returns null both for an empty tag and for a failed read when the tag is
     * still in range afterwards. So: read twice, then fall back to the message Android read at
     * discovery (same tap). Null only if all three say the tag is empty.
     */
    private fun readMessage(ndef: Ndef): NdefMessage? =
        ndef.ndefMessage ?: ndef.ndefMessage ?: ndef.cachedNdefMessage

    /**
     * Android offers only NdefFormatable when its NDEF check at discovery failed, which a too-quick
     * tap can cause on a tag that already holds data. On a Type 2 tag (MIFARE Ultralight / NTAG),
     * byte 0 of page 3, the capability container, is 0xE1 once the tag is NDEF-formatted.
     */
    private fun looksNdefFormatted(tag: Tag): Boolean {
        val ul = MifareUltralight.get(tag) ?: return false
        ul.use {
            it.connect()
            return (it.readPages(3)[0].toInt() and 0xFF) == 0xE1
        }
    }

    /** UIDs (not secret) of blank tags that didn't answer the size probe: they're formatted without one. */
    private val unprobeable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Memory a Type 2 chip has for data, by the page count that READ wraps around at. */
    private val DATA_BYTES = mapOf(
        16 to 48, // MIFARE Ultralight
        20 to 48, // NTAG210, Ultralight EV1 MF0UL11
        41 to 128, // NTAG212, Ultralight EV1 MF0UL21
        42 to 144, // NTAG203
        44 to 144, // Ultralight C (READ goes up to 2Bh)
        45 to 144, // NTAG213
        60 to 144, // Ultralight AES
        135 to 504, // NTAG215
        231 to 888, // NTAG216
    )

    /**
     * NDEF bytes a blank Type 2 tag will have once formatted, or null if unknown. A blank tag has no
     * capability container to say, so the size comes from where READ wraps back to page 0, probed
     * from small to large so that a known chip is never asked for a page it doesn't have. 7 bytes
     * are kept for the TLVs, as on a formatted tag (137 of the Ultralight C's 144). An unknown chip
     * may refuse a probe, which ends the session: it's remembered and not probed again.
     */
    private fun blankCapacity(tag: Tag): Int? {
        val ul = MifareUltralight.get(tag) ?: return null
        val uid = tag.id.joinToString("") { "%02x".format(it) }
        if (uid in unprobeable) return null
        return try {
            ul.use {
                it.connect()
                val pages = wrapPage(it)
                if (pages == null) unprobeable += uid
                pages?.let(DATA_BYTES::get)?.minus(7)
            }
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            unprobeable += uid
            null
        }
    }

    private fun wrapPage(ul: MifareUltralight): Int? {
        val head = ul.readPages(0)
        if (head.size != 16) return null
        for (p in intArrayOf(0x0D, 0x11, 0x27, 0x2A, 0x39, 0x84, 0xE4)) {
            val r = ul.readPages(p)
            if (r.size != 16) return null
            for (k in 1..3) if ((0 until 4).all { r[4 * k + it] == head[it] }) return p + k
        }
        return null
    }

    private fun firstText(msg: NdefMessage?): String? =
        msg?.records?.firstOrNull {
            it.tnf == NdefRecord.TNF_WELL_KNOWN && it.type.contentEquals(NdefRecord.RTD_TEXT)
        }?.let { NdefText.decodePayload(it.payload) }
}
