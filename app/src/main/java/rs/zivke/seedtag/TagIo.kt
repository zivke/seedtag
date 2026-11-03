// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import rs.zivke.seedtag.core.NdefText
import java.io.IOException

/** What the UI wants done with the next tag that touches the phone. */
sealed interface TagRequest {
    /** Write [text]; refuses to overwrite a non-empty tag unless [allowOverwrite]. */
    data class Write(val text: String, val allowOverwrite: Boolean = false) : TagRequest
    data object Read : TagRequest
    data object Lock : TagRequest
}

sealed interface TagResult {
    data class Written(val capacity: Int, val used: Int) : TagResult
    data class Read(val text: String) : TagResult
    data object Locked : TagResult
    /** Tag already holds data; [existing] is its text if it was a Text record. */
    data class NotEmpty(val existing: String?) : TagResult
    data class Failed(val message: String) : TagResult
}

/** Blocking tag I/O. Call only from the NFC reader-mode callback thread. */
object TagIo {

    fun perform(tag: Tag, request: TagRequest): TagResult = try {
        when (request) {
            is TagRequest.Write -> write(tag, request)
            TagRequest.Read -> read(tag)
            TagRequest.Lock -> lock(tag)
        }
    } catch (e: TagLostException) {
        TagResult.Failed("Tag moved away too early. Hold it still against the phone and try again.")
    } catch (e: IOException) {
        TagResult.Failed("Communication with the tag failed (${e.javaClass.simpleName}). Try again.")
    } catch (e: FormatException) {
        TagResult.Failed("The tag contains malformed NDEF data.")
    } catch (e: SecurityException) {
        TagResult.Failed("Tag is no longer in range. Try again.")
    } catch (e: rs.zivke.seedtag.core.SeedTagException) {
        TagResult.Failed(e.message ?: "Unreadable tag data.")
    }

    private fun write(tag: Tag, req: TagRequest.Write): TagResult {
        val message = NdefMessage(NdefRecord.createTextRecord("en", req.text))
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val formatable = NdefFormatable.get(tag)
                ?: return TagResult.Failed("This tag doesn't support NDEF.")
            formatable.use { it.connect(); it.format(message) }
            return verifyAfterWrite(tag, req.text)
        }
        ndef.use {
            it.connect()
            if (!it.isWritable) return TagResult.Failed("This tag is read-only.")
            if (message.byteArrayLength > it.maxSize) {
                return TagResult.Failed("Tag too small: needs ${message.byteArrayLength} bytes, has ${it.maxSize}.")
            }
            if (!req.allowOverwrite) {
                val existing = it.ndefMessage
                if (existing != null && existing.records.any { r -> r.tnf != NdefRecord.TNF_EMPTY }) {
                    return TagResult.NotEmpty(firstText(existing))
                }
            }
            it.writeNdefMessage(message)
            // Read back over the same connection; getNdefMessage() re-reads the tag.
            val back = firstText(it.ndefMessage)
            if (back != req.text) return TagResult.Failed("Read-back mismatch after writing. The tag may be faulty – don't use it.")
            return TagResult.Written(capacity = it.maxSize, used = message.byteArrayLength)
        }
    }

    private fun verifyAfterWrite(tag: Tag, expected: String): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed("Formatted, but couldn't re-open the tag to verify. Scan again with Verify.")
        ndef.use {
            it.connect()
            val back = firstText(it.ndefMessage)
            if (back != expected) return TagResult.Failed("Read-back mismatch after writing. The tag may be faulty – don't use it.")
            return TagResult.Written(capacity = it.maxSize, used = NdefMessage(NdefRecord.createTextRecord("en", expected)).byteArrayLength)
        }
    }

    private fun read(tag: Tag): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed("This tag has no NDEF data.")
        ndef.use {
            it.connect()
            val msg = it.ndefMessage ?: return TagResult.Failed("The tag is empty.")
            val text = firstText(msg) ?: return TagResult.Failed("The tag holds no text record.")
            return TagResult.Read(text)
        }
    }

    private fun lock(tag: Tag): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed("This tag has no NDEF data.")
        ndef.use {
            it.connect()
            if (!it.isWritable) return TagResult.Locked
            if (!it.canMakeReadOnly()) return TagResult.Failed("This tag can't be made read-only.")
            return if (it.makeReadOnly()) TagResult.Locked else TagResult.Failed("Locking failed. The tag is unchanged.")
        }
    }

    private fun firstText(msg: NdefMessage?): String? =
        msg?.records?.firstOrNull {
            it.tnf == NdefRecord.TNF_WELL_KNOWN && it.type.contentEquals(NdefRecord.RTD_TEXT)
        }?.let { NdefText.decodePayload(it.payload) }
}
