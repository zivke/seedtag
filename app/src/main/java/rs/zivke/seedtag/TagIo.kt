// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcV
import rs.zivke.seedtag.core.NdefText
import rs.zivke.seedtag.core.SeedBox
import rs.zivke.seedtag.core.Type2Tlv
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What the UI wants done with the next tag that touches the phone. */
sealed interface TagRequest {
    /** First Back up step: find out whether the tag can take a backup, and how big. Writes nothing. */
    data object Check : TagRequest
    /** Write [text]; refuses to overwrite a non-empty tag unless it's the [overwrite] the user confirmed. */
    data class Write(val text: String, val overwrite: OverwriteTarget? = null) : TagRequest
    /** Read the tag back and compare with [expected] (second tap after formatting a blank tag). */
    data class CheckWritten(val expected: String) : TagRequest
    data object Read : TagRequest
    /** Make the tag read-only, but only if it holds exactly [expected]. */
    data class Lock(val expected: String) : TagRequest
    /** The Erase tab, first tap: what the tag holds and whether it can be erased. Writes nothing. */
    data object Inspect : TagRequest
    /** Erase [target]: overwrite all of its NDEF data with zeros, then leave it empty. */
    data class Erase(val target: OverwriteTarget) : TagRequest
    /** Finish [Rest.Interrupted]: clear the old data after the backup, if the tag holds exactly [expected]. */
    data class ClearRest(val expected: String) : TagRequest
}

/**
 * The tag's data area after the backup, where Android's write leaves whatever was there before (the
 * end of a longer backup, or anything another app wrote).
 */
enum class Rest {
    /** Overwritten with zeros and read back, or zero already. */
    Cleared,
    /** Not a tag whose layout SeedTag knows (only NTAG and MIFARE Ultralight): older data may remain. */
    NotPossible,
    /** The tag moved away while it was being cleared: the backup is fine, older data may remain. */
    Interrupted,
}

/**
 * The non-empty tag the user agreed to overwrite: its UID and the NDEF message it held (not secret,
 * it's what anyone can read from the tag). Any other tag, or this one with different content, has
 * to be confirmed again.
 */
class OverwriteTarget(private val uid: ByteArray, private val message: ByteArray) {
    /** [current] null: the tag holds no NDEF message, which matches a target that held none either. */
    fun matches(tag: Tag, current: NdefMessage?): Boolean {
        // Random UIDs (ISO 14443-3 NUIDs start with 08h) change on every tap: only the content can tell.
        val uidStable = uid.isNotEmpty() && !(uid.size == 4 && uid[0] == 0x08.toByte())
        val bytes = current?.toByteArray() ?: ByteArray(0)
        return bytes.contentEquals(message) && (!uidStable || tag.id.contentEquals(uid))
    }
}

enum class TagContent { Empty, Backup, NewerBackup, Other }

/**
 * What the Erase tab's scan found. [ndef]: it's NDEF-formatted (a blank tag has nothing to erase).
 * [text]: the first Text record (for a backup, the Base64 blob: not secret, anyone can read it from
 * the tag), so the password check needs no second scan. [target]: the tag and content an erase is
 * bound to, null if it can't be erased.
 */
data class EraseCheck(
    val supported: Boolean,
    val ndef: Boolean,
    val writable: Boolean,
    val content: TagContent,
    val capacity: Int?,
    val text: String?,
    val target: OverwriteTarget?,
)

/**
 * What the first Back up step found. [supported]: the tag can hold NDEF at all. [ndef]: it's
 * NDEF-formatted; if not, it's blank and gets formatted on the first write. [capacity]: bytes
 * for the NDEF message, null if unknown; for a blank tag it's [estimated] from the chip's memory.
 * [classic]: a MIFARE Classic tag, which many phones can't read at all, so the backup might not be
 * readable when it's needed.
 */
data class TagCheck(
    val supported: Boolean,
    val ndef: Boolean,
    val capacity: Int?,
    val estimated: Boolean = false,
    val writable: Boolean = true,
    val content: TagContent = TagContent.Empty,
    val classic: Boolean = false,
) {
    /** Longest backup that fits, null if none does or the size is unknown. */
    val maxWords: Int? get() = capacity?.let(SeedBox::maxWordsFor)
    val tooSmall: Boolean get() = capacity != null && maxWords == null
    val usable: Boolean get() = supported && writable && !tooSmall
}

/**
 * What the tag I/O is doing right now, for the scan prompt: [step] of [of], or a single read when [of]
 * is 0. [writes]: the operation changes the tag, so it mustn't be interrupted (no Cancel meanwhile).
 */
data class TagStep(val label: String, val step: Int = 0, val of: Int = 0, val writes: Boolean = false)

sealed interface TagResult {
    data class Checked(val check: TagCheck) : TagResult
    /** [text] was written and read back; [rest]: what happened to the old data after it. */
    data class Written(val text: String, val rest: Rest) : TagResult
    /** The result of [TagRequest.ClearRest]. */
    data class RestCleared(val rest: Rest) : TagResult
    /** A blank tag was formatted with [text], but couldn't be read back on the same tap. */
    data class WrittenUnchecked(val text: String) : TagResult
    /** [differences]: how the tag's message differs from what SeedTag writes ([TagIo.differences]); empty if it doesn't. */
    data class Read(val text: String, val differences: List<String> = emptyList()) : TagResult
    data object Locked : TagResult
    /** Lock found the tag already not writable: nothing was changed, and it may not be permanent. */
    data object AlreadyReadOnly : TagResult
    /** Tag already holds data; [existing] is its text if it was a Text record; [target] identifies it. */
    data class NotEmpty(val existing: String?, val target: OverwriteTarget) : TagResult
    data class Inspected(val check: EraseCheck) : TagResult
    /** Every byte of the tag's NDEF data was overwritten with zeros, and the tag is now empty. */
    data object Erased : TagResult
    data class Failed(val message: String) : TagResult
}

/** Blocking tag I/O. Call only from the NFC reader-mode callback thread. */
object TagIo {

    private const val NOT_READ = "Couldn't read the tag properly (it may have moved away too soon). Hold it still against the phone and try again."

    /**
     * Does [request] with [tag]. [report] is called with each [TagStep], the first one before any I/O
     * (so the UI can say at once that the tag was found), on this thread.
     */
    fun perform(tag: Tag, request: TagRequest, report: (TagStep) -> Unit = {}): TagResult = try {
        report(firstStep(tag, request))
        when (request) {
            TagRequest.Check -> check(tag)
            is TagRequest.Write -> write(tag, request, report)
            is TagRequest.CheckWritten -> checkWritten(tag, request.expected)
            TagRequest.Read -> read(tag)
            is TagRequest.Lock -> lock(tag, request.expected, report)
            TagRequest.Inspect -> inspect(tag)
            is TagRequest.Erase -> erase(tag, request.target, report)
            is TagRequest.ClearRest -> clearRestOf(tag, request.expected)
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
    } catch (e: Exception) {
        // Anything else (e.g. IllegalStateException from the NFC stack) must not escape to the
        // binder thread: the scan would stay open and ignore every tap. Only the type is shown.
        TagResult.Failed("Something went wrong talking to the tag (${e.javaClass.simpleName}). Try again.")
    }

    /** No I/O: what [perform] starts with, and how many steps the request takes on this tag. */
    private fun firstStep(tag: Tag, request: TagRequest): TagStep = when (request) {
        TagRequest.Check, TagRequest.Inspect, TagRequest.Read -> TagStep("Reading the tag")
        is TagRequest.CheckWritten -> TagStep("Reading the backup back to check it")
        is TagRequest.Write -> TagStep(CHECKING, 1, if (Ndef.get(tag) == null) WRITE_BLANK_STEPS else WRITE_STEPS, writes = true)
        is TagRequest.Lock -> TagStep(CHECKING, 1, LOCK_STEPS, writes = true)
        is TagRequest.Erase -> TagStep(CHECKING, 1, ERASE_STEPS, writes = true)
        is TagRequest.ClearRest -> TagStep(CLEARING, writes = true)
    }

    private const val CHECKING = "Checking the tag"
    private const val WRITE_BLANK_STEPS = 2
    private const val WRITE_STEPS = 4
    private const val CLEARING = "Clearing old data after the backup"
    private const val LOCK_STEPS = 2
    private const val ERASE_STEPS = 5

    /** Exactly the message SeedTag writes for [text]: one Text record, language "en", nothing else. */
    private fun messageFor(text: String) = NdefMessage(NdefRecord.createTextRecord("en", text))

    private fun write(tag: Tag, req: TagRequest.Write, report: (TagStep) -> Unit): TagResult {
        val message = messageFor(req.text)
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val formatable = NdefFormatable.get(tag)
                ?: return TagResult.Failed("This tag doesn't support NDEF.")
            // Never format a tag that may already hold a backup.
            if (looksNdefFormatted(tag)) return TagResult.Failed(NOT_READ)
            val needed = message.byteArrayLength
            val capacity = blankCapacity(tag)
            if (capacity != null && needed > capacity) return TagResult.Failed(tooSmall(req.text, needed, capacity, estimated = true))
            report(TagStep("Formatting the tag and writing the backup", 2, WRITE_BLANK_STEPS, writes = true))
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
            // Overwriting without asking is only OK if every read says the tag is empty, and with the
            // user's OK only on the very tag, with the very content, that they were asked about.
            val existing = readMessage(it)
            if (existing == null && type2HoldsData(tag, it)) return TagResult.Failed(NOT_READ)
            if (existing != null && existing.records.any { r -> r.tnf != NdefRecord.TNF_EMPTY }) {
                if (req.overwrite?.matches(tag, existing) != true) {
                    val text = runCatching { firstText(existing) }.getOrNull()
                    return TagResult.NotEmpty(text, OverwriteTarget(tag.id ?: ByteArray(0), existing.toByteArray()))
                }
            }
            val overwriting = existing != null && existing.records.any { r -> r.tnf != NdefRecord.TNF_EMPTY }
            report(TagStep("Writing the backup", 2, WRITE_STEPS, writes = true))
            try {
                it.writeNdefMessage(message)
            } catch (e: IOException) {
                if (!overwriting) throw e
                // A Type 2 write goes page by page: the old content may already be partly overwritten.
                return TagResult.Failed(
                    "The tag moved away while it was being overwritten, so what it held before may already be gone. " +
                        "Hold the same tag still and tap again to write the new backup.",
                )
            }
            // Read back over the same connection; getNdefMessage() re-reads the tag. Not the
            // cached message: that's what the tag held before this write.
            report(TagStep("Reading it back to check it", 3, WRITE_STEPS, writes = true))
            val back = it.ndefMessage ?: it.ndefMessage
                ?: return TagResult.WrittenUnchecked(req.text) // couldn't read: check on the next tap
            // The whole message, not only the text: nothing else may have ended up on the tag.
            if (!back.toByteArray().contentEquals(message.toByteArray())) return TagResult.Failed("What was read back doesn't match what was written. The tag may be faulty – don't use it.")
            report(TagStep(CLEARING, 4, WRITE_STEPS, writes = true))
            return TagResult.Written(req.text, clearRest(tag, it))
        }
    }

    /** What [check] and [inspect] read first, the same way for both. */
    private sealed interface Probe {
        /** The read failed, though the tag may hold data: don't call it empty or blank. */
        data object NotRead : Probe
        /** Neither NDEF data nor formattable: SeedTag can't use it. */
        data object Unsupported : Probe
        /** Never formatted. */
        data object Blank : Probe
        class Formatted(val msg: NdefMessage?, val writable: Boolean, val maxSize: Int) : Probe
    }

    private fun probe(tag: Tag): Probe {
        val ndef = Ndef.get(tag) ?: return when {
            NdefFormatable.get(tag) == null -> Probe.Unsupported
            // Android offers no Ndef for a formatted tag read too quickly: not blank, just not read.
            looksNdefFormatted(tag) -> Probe.NotRead
            else -> Probe.Blank
        }
        ndef.use {
            it.connect()
            val msg = readMessage(it)
            // A read that failed looks like an empty tag: don't call a tag empty that may hold a backup.
            if (msg == null && type2HoldsData(tag, it)) return Probe.NotRead
            return Probe.Formatted(msg, it.isWritable, it.maxSize)
        }
    }

    private fun check(tag: Tag): TagResult {
        // Android lists MifareClassic only on phones that support it; no I/O needed.
        val classic = MifareClassic::class.java.name in tag.techList
        return when (val p = probe(tag)) {
            Probe.NotRead -> TagResult.Failed(NOT_READ)
            Probe.Unsupported -> TagResult.Checked(TagCheck(supported = false, ndef = false, capacity = null))
            Probe.Blank -> {
                val capacity = blankCapacity(tag)
                TagResult.Checked(TagCheck(true, ndef = false, capacity = capacity, estimated = capacity != null, classic = classic))
            }
            is Probe.Formatted -> TagResult.Checked(
                TagCheck(true, ndef = true, capacity = p.maxSize, writable = p.writable, content = contentOf(p.msg), classic = classic),
            )
        }
    }

    private fun contentOf(msg: NdefMessage?): TagContent =
        if (msg == null || isEmpty(msg)) {
            TagContent.Empty
        } else {
            when (SeedBox.kindOf(runCatching { firstText(msg) }.getOrNull())) {
                SeedBox.Kind.Current -> TagContent.Backup
                SeedBox.Kind.Newer -> TagContent.NewerBackup
                SeedBox.Kind.NotBackup -> TagContent.Other
            }
        }

    private fun isEmpty(msg: NdefMessage) = msg.records.all { r -> r.tnf == NdefRecord.TNF_EMPTY }

    private fun inspect(tag: Tag): TagResult = when (val p = probe(tag)) {
        Probe.NotRead -> TagResult.Failed(NOT_READ)
        Probe.Unsupported, Probe.Blank -> TagResult.Inspected(
            EraseCheck(p == Probe.Blank, ndef = false, writable = false, TagContent.Empty, null, null, null),
        )
        is Probe.Formatted -> TagResult.Inspected(
            EraseCheck(
                true, ndef = true, writable = p.writable, content = contentOf(p.msg), capacity = p.maxSize,
                text = runCatching { firstText(p.msg) }.getOrNull(),
                target = if (p.writable) OverwriteTarget(tag.id ?: ByteArray(0), p.msg?.toByteArray() ?: ByteArray(0)) else null,
            ),
        )
    }

    /**
     * Writing a short NDEF message only rewrites as many bytes as it needs: on a Type 2 tag the rest
     * of the old message (the encrypted backup) stays in memory, readable by any raw-read app. So
     * the whole NDEF area is first filled by one record of zeros exactly [Ndef.getMaxSize] long
     * (read back to be sure), and only then is the empty message written.
     */
    private fun erase(tag: Tag, target: OverwriteTarget, report: (TagStep) -> Unit): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            val current = readMessage(it)
            if (current == null && type2HoldsData(tag, it)) return TagResult.Failed(NOT_READ)
            // Only the tag that was scanned, with what it held then. A tag already holding the zeros
            // is one whose erase was interrupted: it holds nothing any more, so it may be finished.
            if (!target.matches(tag, current) && !(current != null && isFiller(current))) {
                return TagResult.Failed(
                    "This isn't the tag you scanned, or what it holds has changed since. Nothing was changed. Cancel and scan it again.",
                )
            }
            if (!it.isWritable) return TagResult.Failed("This tag is read-only: it can't be erased.")
            val filler = fillerFor(it.maxSize)
            report(TagStep("Overwriting it with zeros", 2, ERASE_STEPS, writes = true))
            try {
                it.writeNdefMessage(filler)
            } catch (e: IOException) {
                return TagResult.Failed(
                    "The tag moved away while it was being erased, so part of what it held may already be gone. Nothing " +
                        "here can tell how much: Cancel, scan the tag again and erase it again.",
                )
            }
            report(TagStep("Reading the zeros back", 3, ERASE_STEPS, writes = true))
            val back = it.ndefMessage
            if (back == null || !back.toByteArray().contentEquals(filler.toByteArray())) {
                return TagResult.Failed(
                    "Couldn't confirm that the whole tag was overwritten. Hold the same tag still and tap again to finish.",
                )
            }
            report(TagStep("Emptying the tag", 4, ERASE_STEPS, writes = true))
            try {
                it.writeNdefMessage(EMPTY)
            } catch (e: IOException) {
                return TagResult.Failed(
                    "The old backup was already overwritten: the zeros were written and read back. But the tag moved away before it " +
                        "was left empty. Hold the same tag still and tap again to finish. If the app then says it isn't " +
                        "the tag you scanned, Cancel, scan the tag again and erase it again.",
                )
            }
            report(TagStep("Checking that it's empty", 5, ERASE_STEPS, writes = true))
            val after = it.ndefMessage
            if (after != null && !isEmpty(after)) {
                return TagResult.Failed("The tag was overwritten with zeros but isn't empty yet. Hold the same tag still and tap again to finish.")
            }
            return TagResult.Erased
        }
    }

    private val EMPTY = NdefMessage(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null))

    /**
     * One record of zeros (TNF unknown, no type) whose message is as close to [maxSize] bytes as an
     * NDEF record can be: exactly, except for a few sizes just above 255 bytes, where the short
     * record format ends and up to 3 bytes at the end stay as they were.
     */
    private fun fillerFor(maxSize: Int): NdefMessage {
        fun msg(n: Int) = NdefMessage(NdefRecord(NdefRecord.TNF_UNKNOWN, null, null, ByteArray(n)))
        var n = (maxSize - 3).coerceAtLeast(0)
        while (n > 0 && msg(n).byteArrayLength > maxSize) n--
        return msg(n)
    }

    /** A message that is one of [fillerFor]'s records: what an interrupted erase leaves behind. */
    private fun isFiller(msg: NdefMessage): Boolean = msg.records.size == 1 && msg.records[0].let { r ->
        r.tnf == NdefRecord.TNF_UNKNOWN && r.type.isEmpty() && r.payload.all { b -> b == 0.toByte() }
    }

    /** A SeedTag backup, of this format or a newer one ([SeedBox.kindOf]). */
    fun looksLikeBackup(text: String?): Boolean = SeedBox.kindOf(text) != SeedBox.Kind.NotBackup

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
            if (!msg.toByteArray().contentEquals(messageFor(expected).toByteArray())) {
                return TagResult.Failed("This tag doesn't hold what was just written. Hold the same tag to the phone again. If this keeps happening, the tag may be faulty.")
            }
            return TagResult.Written(expected, clearRest(tag, it))
        }
    }

    private fun clearRestOf(tag: Tag, expected: String): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            val msg = readMessage(it) ?: return TagResult.Failed(NOT_READ)
            if (!msg.toByteArray().contentEquals(messageFor(expected).toByteArray())) {
                return TagResult.Failed("This isn't the tag you just wrote. Nothing was changed.")
            }
            return TagResult.RestCleared(clearRest(tag, it))
        }
    }

    /**
     * After a backup was written and checked: overwrite with zeros the data area after its TLVs'
     * terminator, where Android's write leaves the old bytes, and read it back. Only on a Type 2 tag
     * (NTAG, MIFARE Ultralight) whose layout [Type2Tlv.freeAfterTerminator] is sure of. Only whole pages
     * after the terminator's page are written: a write torn on that page could damage the backup, and
     * Android fills the rest of it with zeros anyway. [ndef] is closed for the raw I/O (one technology
     * at a time). Never throws: the backup is already fine.
     */
    private fun clearRest(tag: Tag, ndef: Ndef): Rest {
        val ul = MifareUltralight.get(tag) ?: return Rest.NotPossible
        ndef.close()
        return try {
            ul.use {
                it.connect()
                val data: ByteArray
                val first: Int
                try {
                    val cc = it.readPages(3)
                    if ((cc[0].toInt() and 0xFF) != 0xE1) return Rest.NotPossible
                    data = readDataArea(it, (cc[2].toInt() and 0xFF) * 8)
                    first = Type2Tlv.freeAfterTerminator(data) ?: return Rest.NotPossible
                } catch (e: TagLostException) {
                    throw e
                } catch (e: IOException) {
                    return Rest.NotPossible // a page it won't read: not a layout to write to blindly
                }
                val firstPage = (first + 3) / 4
                val zeros = ByteArray(4)
                for (page in firstPage until data.size / 4) {
                    if (!data.copyOfRange(4 * page, 4 * page + 4).contentEquals(zeros)) it.writePage(4 + page, zeros)
                }
                val back = readDataArea(it, data.size)
                if ((4 * firstPage until back.size).all { i -> back[i] == 0.toByte() }) Rest.Cleared else Rest.Interrupted
            }
        } catch (e: IOException) {
            Rest.Interrupted
        } catch (e: SecurityException) {
            Rest.Interrupted // "tag out of date": it left the field
        }
    }

    /** The data area from page 4, [size] bytes; READ returns four pages at a time. */
    private fun readDataArea(ul: MifareUltralight, size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val pages = ul.readPages(4 + offset / 4)
            if (pages.isEmpty()) throw IOException("empty read")
            pages.copyInto(out, offset, 0, minOf(pages.size, size - offset))
            offset += pages.size
        }
        return out
    }

    private fun read(tag: Tag): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            val msg = readMessage(it) ?: return TagResult.Failed(
                "The tag is empty, or it couldn't be read properly. Hold it still against the phone and try again.",
            )
            val text = firstText(msg) ?: return TagResult.Failed("The tag holds no text record.")
            return TagResult.Read(text, differences(msg, text))
        }
    }

    /**
     * How [msg], whose first Text record holds [text], differs from what SeedTag writes ([messageFor] with
     * the exact Base64), each as the end of "This tag has …". Empty for a tag SeedTag wrote. Only the
     * message: what's on the tag outside it needs a raw read (TODO.md, H3 and H4).
     */
    private fun differences(msg: NdefMessage, text: String): List<String> {
        val out = mutableListOf<String>()
        if (msg.records.size > 1) out += "other records besides the backup"
        val record = msg.records.first { it.tnf == NdefRecord.TNF_WELL_KNOWN && it.type.contentEquals(NdefRecord.RTD_TEXT) }
        if (record.id.isNotEmpty()) out += "an ID on the backup's record"
        out += NdefText.differences(record.payload)
        if (out.isEmpty() && !msg.toByteArray().contentEquals(messageFor(text).toByteArray())) {
            out += "a record laid out differently"
        }
        return out
    }

    private fun lock(tag: Tag, expected: String, report: (TagStep) -> Unit): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            // Locking is permanent: only the tag that was just written or verified.
            val msg = readMessage(it) ?: return TagResult.Failed(NOT_READ)
            if (firstText(msg) != expected) {
                return TagResult.Failed("This isn't the tag you last wrote or verified. Nothing was changed.")
            }
            // Android reports a tag as not writable from its capability container alone, which can be
            // set without the lock bits: SeedTag made no change and can't tell whether it's permanent.
            if (!it.isWritable) return TagResult.AlreadyReadOnly
            if (!it.canMakeReadOnly()) {
                return TagResult.Failed(
                    "This tag can't be made read-only from this phone. Android usually only offers it for NFC Forum Type 1 " +
                        "and 2 tags (NTAG, MIFARE Ultralight). Nothing was changed.",
                )
            }
            report(TagStep("Making it read-only", 2, LOCK_STEPS, writes = true))
            return if (it.makeReadOnly()) TagResult.Locked else TagResult.Failed("Making it read-only failed. The tag is unchanged.")
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
     * For a tag that every NDEF read called empty ([readMessage] null): on a Type 2 tag, read the TLVs
     * from page 4 directly, and say whether an NDEF message is there after all. Then the reads failed
     * rather than found nothing, and writing without asking could destroy a backup. Other tag types,
     * and TLVs too odd to tell, count as empty, as before. [ndef] is closed for the raw read (one
     * technology at a time) and connected again afterwards.
     */
    private fun type2HoldsData(tag: Tag, ndef: Ndef): Boolean {
        val ul = MifareUltralight.get(tag) ?: return false
        ndef.close()
        try {
            val data = ul.use { it.connect(); it.readPages(4) + it.readPages(8) } // pages 4 to 11
            return (Type2Tlv.ndefLength(data) ?: 0) > 0
        } finally {
            ndef.connect()
        }
    }

    /**
     * Android offers only NdefFormatable when its NDEF check at discovery failed, which a too-quick
     * tap can cause on a tag that already holds data. The capability container says whether it's
     * NDEF-formatted: on a Type 2 tag (MIFARE Ultralight / NTAG), byte 0 of page 3 is E1h; on a
     * Type 5 tag (ICODE, ST25TV), byte 0 of block 0 is E1h or E2h. Other tag types can't be checked
     * and are formatted as before (MIFARE Classic, which the README says to avoid).
     */
    private fun looksNdefFormatted(tag: Tag): Boolean {
        MifareUltralight.get(tag)?.use {
            it.connect()
            return (it.readPages(3)[0].toInt() and 0xFF) == 0xE1
        }
        NfcV.get(tag)?.use {
            it.connect()
            // READ SINGLE BLOCK 0, unaddressed, high data rate. Reply: flags, then the block.
            val r = it.transceive(byteArrayOf(0x02, 0x20, 0x00))
            if (r.size < 2 || (r[0].toInt() and 0x01) != 0) throw IOException("block 0 not readable")
            val cc0 = r[1].toInt() and 0xFF
            return cc0 == 0xE1 || cc0 == 0xE2
        }
        return false
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
