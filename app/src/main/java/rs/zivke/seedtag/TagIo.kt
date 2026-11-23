// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcV
import rs.zivke.seedtag.core.EraseCheck
import rs.zivke.seedtag.core.EraseFiller
import rs.zivke.seedtag.core.NdefText
import rs.zivke.seedtag.core.OverwriteTarget
import rs.zivke.seedtag.core.Rest
import rs.zivke.seedtag.core.SeedBox
import rs.zivke.seedtag.core.TagCheck
import rs.zivke.seedtag.core.TagContent
import rs.zivke.seedtag.core.TagRequest
import rs.zivke.seedtag.core.TagResult
import rs.zivke.seedtag.core.TagStep
import rs.zivke.seedtag.core.Type2Memory
import rs.zivke.seedtag.core.Type2Tlv
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
    private const val ERASE_STEPS = 6

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
                    changedTag = true,
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
                if (req.overwrite?.matches(tag.id, existing.toByteArray()) != true) {
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
                    changedTag = true,
                )
            }
            // Read back over the same connection; getNdefMessage() re-reads the tag. Not the
            // cached message: that's what the tag held before this write.
            report(TagStep("Reading it back to check it", 3, WRITE_STEPS, writes = true))
            val back = it.ndefMessage ?: it.ndefMessage
                ?: return TagResult.WrittenUnchecked(req.text) // couldn't read: check on the next tap
            // The whole message, not only the text: nothing else may have ended up on the tag.
            if (!back.toByteArray().contentEquals(message.toByteArray())) return TagResult.Failed("What was read back doesn't match what was written. The tag may be faulty – don't use it.", changedTag = true)
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
        TagContent.of(msg == null || isEmpty(msg), msg?.let { runCatching { firstText(it) }.getOrNull() })

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
            if (!target.matches(tag.id, current?.toByteArray()) && !(current != null && isFiller(current))) {
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
                    changedTag = true,
                )
            }
            report(TagStep("Reading the zeros back", 3, ERASE_STEPS, writes = true))
            val back = it.ndefMessage
            if (back == null || !back.toByteArray().contentEquals(filler.toByteArray())) {
                return TagResult.Failed(
                    "Couldn't confirm that the whole tag was overwritten. Hold the same tag still and tap again to finish.",
                    changedTag = true,
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
                    changedTag = true,
                )
            }
            report(TagStep("Checking that it's empty", 5, ERASE_STEPS, writes = true))
            val after = it.ndefMessage
            if (after != null && !isEmpty(after)) {
                return TagResult.Failed(
                    "The tag was overwritten with zeros but isn't empty yet. Hold the same tag still and tap again to finish.",
                    changedTag = true,
                )
            }
            // On a Type 2 tag, the same as after a backup: zero what's left after the empty message
            // (the filler can leave a few bytes, see EraseFiller) and read the data area back raw.
            report(TagStep("Checking its memory page by page", 6, ERASE_STEPS, writes = true))
            return TagResult.Erased(clearRest(tag, it))
        }
    }

    private val EMPTY = NdefMessage(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null))

    /** [EraseFiller]'s record for an NDEF area of [maxSize] bytes. */
    private fun fillerFor(maxSize: Int): NdefMessage =
        NdefMessage(NdefRecord(NdefRecord.TNF_UNKNOWN, null, null, ByteArray(EraseFiller.payloadFor(maxSize))))

    /** What an interrupted erase leaves behind ([EraseFiller.isFiller]). */
    private fun isFiller(msg: NdefMessage): Boolean = msg.records.firstOrNull()
        ?.let { r -> EraseFiller.isFiller(msg.records.size, r.tnf.toInt(), r.type, r.payload) } ?: false

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
     * After a backup was written and checked, or a tag erased: overwrite with zeros the data area
     * after its TLVs' terminator, where Android's write leaves the old bytes, and read it back. Only
     * on a Type 2 tag ([Type2Raw]: NTAG, MIFARE Ultralight and compatible chips) whose layout
     * [Type2Memory.freePages] is sure of, and only those pages: never the lock or configuration
     * pages after the data area. [ndef] is closed for the raw I/O (one technology at a time). Never
     * throws: the backup, or the empty tag, is already fine.
     */
    private fun clearRest(tag: Tag, ndef: Ndef): Rest {
        val raw = Type2Raw.of(tag) ?: return Rest.NotPossible
        ndef.close()
        return try {
            raw.use {
                it.connect()
                val data: ByteArray
                val pages: IntRange
                try {
                    val cc = it.read(3)
                    if ((cc[0].toInt() and 0xFF) != 0xE1) return Rest.NotPossible
                    data = readDataArea(it, (cc[2].toInt() and 0xFF) * 8)
                    pages = Type2Memory.freePages(data) ?: return Rest.NotPossible
                } catch (e: TagLostException) {
                    throw e
                } catch (e: IOException) {
                    return Rest.NotPossible // a page it won't read: not a layout to write to blindly
                }
                val zeros = ByteArray(4)
                for (page in pages) if (!Type2Memory.page(data, page).contentEquals(zeros)) it.write(page, zeros)
                if (Type2Memory.allZero(readDataArea(it, data.size), pages)) Rest.Cleared else Rest.Interrupted
            }
        } catch (e: IOException) {
            Rest.Interrupted
        } catch (e: SecurityException) {
            Rest.Interrupted // "tag out of date": it left the field
        }
    }

    /** The data area from page 4, [size] bytes; READ returns four pages at a time. */
    private fun readDataArea(raw: Type2Raw, size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val pages = raw.read(Type2Memory.FIRST_DATA_PAGE + offset / 4)
            pages.copyInto(out, offset, 0, minOf(pages.size, size - offset))
            offset += pages.size
        }
        return out
    }

    /**
     * A Type 2 tag's raw READ and WRITE ([Type2Memory]'s commands) through NfcA, so they work on any
     * Type 2 chip. Android offers MifareUltralight only when the UID starts with 04h (NXP), so
     * NTAG213-compatible chips from other makers would be left out (board finding B12). Type 2
     * chips answer with SAK 00h; [of] is null for any other tag.
     */
    private class Type2Raw(private val nfcA: NfcA) : Closeable {
        fun connect() = nfcA.connect()

        /** 16 bytes (4 pages) from [page]. Anything shorter is a NAK, or not a Type 2 chip. */
        fun read(page: Int): ByteArray {
            val r = nfcA.transceive(Type2Memory.readCommand(page))
            if (r.size < 16) throw IOException("READ $page: ${r.size} bytes")
            return r.copyOf(16)
        }

        /** The reply (ACK or NAK) isn't relied on: whatever is written is read back afterwards. */
        fun write(page: Int, data: ByteArray) {
            nfcA.transceive(Type2Memory.writeCommand(page, data))
        }

        override fun close() = nfcA.close()

        companion object {
            fun of(tag: Tag): Type2Raw? = NfcA.get(tag)?.takeIf { it.sak.toInt() == 0 }?.let(::Type2Raw)
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
            if (!it.canMakeReadOnly()) return TagResult.CantMakeReadOnly
            report(TagStep("Making it read-only", 2, LOCK_STEPS, writes = true))
            return try {
                if (it.makeReadOnly()) TagResult.Locked else TagResult.Failed("Making it read-only failed. The tag is unchanged.")
            } catch (e: IOException) {
                // The lock bits and the capability container are separate writes: some may be done, for good.
                TagResult.Failed(
                    "The tag moved away while it was being made read-only, so part of it may already be read-only for good. " +
                        "Reading it still works either way: Verify it, then hold the same tag still and make it read-only again.",
                    changedTag = true,
                )
            }
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
     * For a tag that every NDEF read called empty ([readMessage] null): on a Type 2 tag ([Type2Raw]),
     * read the TLVs from page 4 directly, and say whether an NDEF message is there after all. Then the reads failed
     * rather than found nothing, and writing without asking could destroy a backup. Other tag types,
     * and TLVs too odd to tell, count as empty, as before. [ndef] is closed for the raw read (one
     * technology at a time) and connected again afterwards.
     */
    private fun type2HoldsData(tag: Tag, ndef: Ndef): Boolean {
        val raw = Type2Raw.of(tag) ?: return false
        ndef.close()
        try {
            val data = raw.use { it.connect(); it.read(4) + it.read(8) } // pages 4 to 11
            return (Type2Tlv.ndefLength(data) ?: 0) > 0
        } finally {
            ndef.connect()
        }
    }

    /**
     * Android offers only NdefFormatable when its NDEF check at discovery failed, which a too-quick
     * tap can cause on a tag that already holds data. The capability container says whether it's
     * NDEF-formatted: on a Type 2 tag (MIFARE Ultralight, NTAG and compatible chips), byte 0 of page 3 is E1h; on a
     * Type 5 tag (ICODE, ST25TV), byte 0 of block 0 is E1h or E2h. Other tag types can't be checked
     * and are formatted as before (MIFARE Classic, which the README says to avoid).
     */
    private fun looksNdefFormatted(tag: Tag): Boolean {
        Type2Raw.of(tag)?.use {
            it.connect()
            return (it.read(3)[0].toInt() and 0xFF) == 0xE1
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
        val raw = Type2Raw.of(tag) ?: return null
        val uid = tag.id.joinToString("") { "%02x".format(it) }
        if (uid in unprobeable) return null
        return try {
            raw.use {
                it.connect()
                val pages = Type2Memory.wrapPage(it::read)
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

    private fun firstText(msg: NdefMessage?): String? =
        msg?.records?.firstOrNull {
            it.tnf == NdefRecord.TNF_WELL_KNOWN && it.type.contentEquals(NdefRecord.RTD_TEXT)
        }?.let { NdefText.decodePayload(it.payload) }
}
