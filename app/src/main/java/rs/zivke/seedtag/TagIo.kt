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
import rs.zivke.seedtag.core.NtagChip
import rs.zivke.seedtag.core.NtagConfig
import rs.zivke.seedtag.core.NtagIdentity
import rs.zivke.seedtag.core.NtagLock
import rs.zivke.seedtag.core.OverwriteTarget
import rs.zivke.seedtag.core.ProtectStage
import rs.zivke.seedtag.core.Protection
import rs.zivke.seedtag.core.Rest
import rs.zivke.seedtag.core.SeedBox
import rs.zivke.seedtag.core.TagCheck
import rs.zivke.seedtag.core.TagContent
import rs.zivke.seedtag.core.TagInfo
import rs.zivke.seedtag.core.TagKey
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
            is TagRequest.Read -> read(tag, request.withInfo)
            is TagRequest.Lock -> lock(tag, request, report)
            TagRequest.Inspect -> inspect(tag)
            is TagRequest.Erase -> erase(tag, request.target, report)
            is TagRequest.ClearRest -> clearRestOf(tag, request.expected)
            is TagRequest.Protect -> protect(tag, request, report)
            is TagRequest.Unprotect -> unprotect(tag, request, report)
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
        TagRequest.Check, TagRequest.Inspect, is TagRequest.Read -> TagStep("Reading the tag")
        is TagRequest.CheckWritten -> TagStep("Reading the backup back to check it")
        is TagRequest.Write -> TagStep(CHECKING, 1, if (Ndef.get(tag) == null) WRITE_BLANK_STEPS else WRITE_STEPS, writes = true)
        is TagRequest.Lock -> TagStep(CHECKING, 1, LOCK_STEPS, writes = true)
        is TagRequest.Erase -> TagStep(CHECKING, 1, ERASE_STEPS, writes = true)
        is TagRequest.ClearRest -> TagStep(CLEARING, writes = true)
        is TagRequest.Protect -> TagStep(CHECKING, 1, PROTECT_STEPS, writes = true)
        is TagRequest.Unprotect -> TagStep(CHECKING, 1, PROTECT_STEPS, writes = true)
    }

    private const val PROTECT_STEPS = 2

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
            if (isProtected(tag, it)) return TagResult.Failed(PROTECTED + " Verify it, remove the write password, then write the backup.")
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
        /**
         * [chip]: which chip it is and its write protection ([chipInfo]), null if that couldn't be checked.
         * [protected]: it has a write password in force (an NTAG21x's AUTH0 on).
         */
        class Formatted(
            val msg: NdefMessage?, val writable: Boolean, val maxSize: Int, val canMakeReadOnly: Boolean, val chip: TagInfo?,
        ) : Probe {
            val protected: Boolean get() = chip?.identity != null && chip.protection != Protection.Off
        }
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
            val writable = it.isWritable
            val canMakeReadOnly = it.canMakeReadOnly()
            // Last, as it may end the chip's session (GET_VERSION): everything NDEF is read by now.
            val chip = if (writable) chipInfo(tag, it) else TagInfo(null)
            return Probe.Formatted(msg, writable, it.maxSize, canMakeReadOnly, chip)
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
                TagCheck(
                    true, ndef = true, capacity = p.maxSize, writable = p.writable, content = contentOf(p.msg), classic = classic,
                    canMakeReadOnly = p.canMakeReadOnly, chip = p.chip,
                ),
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
                target = if (p.writable && !p.protected) OverwriteTarget(tag.id ?: ByteArray(0), p.msg?.toByteArray() ?: ByteArray(0)) else null,
                protected = p.protected,
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
            if (isProtected(tag, it)) return TagResult.Failed(PROTECTED + " Verify it, remove the write password, then erase it.")
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
        /** The tag's UID in hex (not secret), to remember what a chip answered. */
        val uid: String = (nfcA.tag.id ?: ByteArray(0)).joinToString("") { "%02x".format(it) }

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

        /**
         * GET_VERSION's 8 bytes, or null if the chip refused it (a MIFARE Ultralight or Ultralight C
         * does). A refused command ends the chip's session: send nothing after a null.
         */
        fun getVersion(): ByteArray? = refusedAsNull { nfcA.transceive(NtagChip.GET_VERSION) }?.takeIf { it.size == 8 }

        /**
         * PWD_AUTH with [pwd]: the chip's PACK if it accepted it, null if it refused (the wrong password,
         * or no password support). As with [getVersion], a refusal ends the session.
         */
        fun pwdAuth(pwd: ByteArray): ByteArray? =
            refusedAsNull { nfcA.transceive(byteArrayOf(0x1B) + pwd) }?.takeIf { it.size == 2 }

        /**
         * Android reports a chip's NAK as an IOException (or, on some phones, a reply of one byte); a tag
         * that left the field is a TagLostException, which is passed on.
         */
        private fun refusedAsNull(command: () -> ByteArray): ByteArray? = try {
            command()
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            null
        }

        override fun close() = nfcA.close()

        companion object {
            fun of(tag: Tag): Type2Raw? = NfcA.get(tag)?.takeIf { it.sak.toInt() == 0 }?.let(::Type2Raw)
        }
    }

    private fun read(tag: Tag, withInfo: Boolean): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            val msg = readMessage(it) ?: return TagResult.Failed(
                "The tag is empty, or it couldn't be read properly. Hold it still against the phone and try again.",
            )
            val text = firstText(msg) ?: return TagResult.Failed("The tag holds no text record.")
            return TagResult.Read(text, differences(msg, text), if (withInfo) tagInfo(tag, it) else null)
        }
    }

    /** An NTAG21x, a chip that answers like one, or the FM11NT021: which it is, and its settings as READ returned them. */
    private class NtagState(val identity: NtagIdentity, val config: NtagConfig) {
        val info: TagInfo get() = TagInfo(identity, config.protection, config.settingsLocked, config.mirrorOn)
    }

    /**
     * Which chip a tag is, by UID (not secret), for as long as the app runs: [identities] once GET_VERSION
     * settled whether it's NXP's own (it answered, or refused), [notNtag] for a chip without the NTAG21x
     * layout or with another chip's version. A chip that didn't answer GET_VERSION at all isn't remembered:
     * a tag that moved away looks the same.
     */
    private val identities = ConcurrentHashMap<String, NtagIdentity>()
    private val notNtag: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * On a connected [raw]: which chip it is, and its settings pages. Null if it isn't an NTAG21x or a
     * lookalike with its layout. The layout and the settings are plain READs; GET_VERSION, which tells
     * NXP's own from a lookalike, comes last, and only with [askVersion]: some chips refuse it (MIFARE
     * Ultralight C) or don't answer (some lookalikes), which ends the session until the next tap. So it's
     * asked only when nothing else follows (Back up's and Erase's first scan, Verify). Without it, a chip
     * not identified before counts as a lookalike.
     */
    private fun ntagState(raw: Type2Raw, askVersion: Boolean): NtagState? {
        val uid = raw.uid
        if (uid in notNtag) return null
        val head = raw.read(0)
        if ((head[12].toInt() and 0xFF) != 0xE1) return null // not formatted (yet): nothing to remember
        identities[uid]?.takeIf { (head[14].toInt() and 0xFF) == it.chip.ccSize }?.let { known ->
            return NtagState(known, NtagConfig(known, raw.read(known.chip.cfgPage)))
        }
        val layout = NtagIdentity.byLayout(head, raw::read) ?: run {
            if (NtagChip.forCcSize(head[14].toInt() and 0xFF) != null) notNtag += uid
            return null
        }
        val settings = raw.read(layout.chip.cfgPage)
        val identity = if (!askVersion) layout else {
            val answer = try {
                raw.getVersion() ?: REFUSED
            } catch (e: TagLostException) {
                null // no answer: a lookalike for this tap, not remembered
            }
            val version = answer?.takeIf { it !== REFUSED }
            val combined = NtagIdentity.combine(head, layout, version) ?: run { notNtag += uid; return null }
            if (answer != null) identities[uid] = combined
            combined
        }
        return NtagState(identity, NtagConfig(identity, settings))
    }

    /** GET_VERSION refused (a NAK), as opposed to not answered at all. */
    private val REFUSED = ByteArray(0)

    /**
     * For Verify, after the backup was read: the chip and its write protection. [ndef] is closed for the
     * raw I/O. Never fails the read: a tag that moved away by now just has no info ([TagInfo] of null).
     */
    private fun tagInfo(tag: Tag, ndef: Ndef): TagInfo {
        val raw = Type2Raw.of(tag) ?: return TagInfo(null)
        ndef.close()
        return try {
            raw.use {
                it.connect()
                val state = ntagState(it, askVersion = true) ?: return TagInfo(null)
                state.info
            }
        } catch (e: Exception) {
            TagInfo(null)
        }
    }

    /**
     * The chip and its write protection, for the end of Back up's and Erase's first scan: [TagInfo.identity]
     * null if it isn't a chip SeedTag can protect, the whole result null if that couldn't be checked (the
     * scan's NDEF result stands either way). [ndef] is closed for the raw I/O and stays closed.
     */
    private fun chipInfo(tag: Tag, ndef: Ndef): TagInfo? {
        val raw = Type2Raw.of(tag) ?: return TagInfo(null)
        ndef.close()
        return try {
            raw.use {
                it.connect()
                ntagState(it, askVersion = true)?.info ?: TagInfo(null)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether the tag has a write password in force, just before writing to it: plain READs only, never
     * GET_VERSION ([ntagState]). False if unknown. [ndef] is closed
     * for the raw reads and connected again afterwards.
     */
    private fun isProtected(tag: Tag, ndef: Ndef): Boolean {
        val raw = Type2Raw.of(tag) ?: return false
        ndef.close()
        return try {
            raw.use { it.connect(); ntagState(it, askVersion = false)?.let { st -> st.config.protection != Protection.Off } ?: false }
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            false
        } finally {
            ndef.connect()
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

    private fun lock(tag: Tag, req: TagRequest.Lock, report: (TagStep) -> Unit): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            // Locking is permanent: only the tag that was just written or verified.
            val msg = readMessage(it) ?: return TagResult.Failed(NOT_READ)
            if (firstText(msg) != req.expected) {
                return TagResult.Failed("This isn't the tag you last wrote or verified. Nothing was changed.")
            }
            // An NTAG21x, or a lookalike with its layout: SeedTag writes the lock itself and reads it back.
            Type2Raw.of(tag)?.let { raw ->
                it.close()
                lockNtag(raw, req, report)?.let { result -> return result }
                it.connect() // not one: Android's lock, below
            }
            // Android reports a tag as not writable from its capability container alone, which can be
            // set without the lock bits: SeedTag made no change and can't tell whether it's permanent.
            if (!it.isWritable) return TagResult.AlreadyReadOnly(checked = false)
            if (!it.canMakeReadOnly()) return TagResult.CantMakeReadOnly
            report(TagStep("Making it read-only", 2, LOCK_STEPS, writes = true))
            return try {
                if (it.makeReadOnly()) {
                    TagResult.Locked(checked = false)
                } else {
                    TagResult.Failed(
                        "Android couldn't make it read-only. If an earlier try was cut short, part of it may already be " +
                            "read-only for good. Reading it works either way.",
                    )
                }
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
     * Make tag read-only on an NTAG21x layout (board B2, B3, #11): the settings frozen (CFGLCK, AUTH0 FFh),
     * the dynamic lock bytes on the chip's own page (Android's lock puts them after the capability container's
     * data area, which misses them on an NTAG215/216 without a Lock Control TLV), the capability container's
     * read-only byte, then the static lock bytes, which also lock page 3. Only what [NtagLock.missing] says is
     * written: lock bits can only be set, so a lock cut short is finished by the next tap. Everything is read
     * back after a new session. Null if [raw] isn't an NTAG21x layout: Android's lock then.
     */
    private fun lockNtag(raw: Type2Raw, req: TagRequest.Lock, report: (TagStep) -> Unit): TagResult? {
        var wrote = false
        try {
            raw.use { r ->
                r.connect()
                // Writes follow: no GET_VERSION here. Verify identified the chip just before.
                val state = ntagState(r, askVersion = false) ?: return null
                val chip = state.identity.chip
                if (state.config.protection != Protection.Off) {
                    return TagResult.Failed(
                        "This tag has a write password. Remove it first (Verify the tag, then Remove write password), " +
                            "then make it read-only. Nothing was changed.",
                    )
                }
                if (!state.identity.genuine && !req.lookalike) {
                    // Verify didn't show it as a lookalike, but only GET_VERSION there tells NXP's chips apart.
                    return TagResult.Failed(
                        "SeedTag couldn't confirm that this is an NXP chip, so it may be an NTAG21x lookalike. Nothing was " +
                            "changed. Verify the tag again, holding it still, then decide.",
                    )
                }
                fun read() = NtagLock(chip, r.read(0), r.read(chip.dynLockPage), state.config.reread(r.read(chip.cfgPage)))
                val before = read()
                if (before.complete) return TagResult.AlreadyReadOnly(checked = true)
                report(TagStep("Making it read-only", 2, LOCK_STEPS, writes = true))
                try {
                    for (part in before.missing) {
                        val (page, data) = before.write(part)
                        wrote = true
                        r.write(page, data)
                    }
                } catch (e: TagLostException) {
                    throw e
                } catch (e: IOException) {
                    // Refused: the read-back says what's still missing.
                }
                // A fresh session: after a refused write the chip has ended this one.
                r.close()
                r.connect()
                val after = read()
                if (after.complete) return TagResult.Locked(checked = true)
                return TagResult.Failed(
                    "Part of the lock didn't stay on the tag (" + after.missing.joinToString { LOCK_PARTS.getValue(it) } +
                        "). What's done stays done: hold the same tag still to finish it.",
                    changedTag = true,
                )
            }
        } catch (e: Exception) {
            // After a write, whatever broke the session: part of the lock may be on the tag for good.
            if (!wrote) throw e
            return TagResult.Failed(
                "The tag moved away before it was fully read-only. What's done stays done, and the rest can be finished: " +
                    "hold the same tag still.",
                changedTag = true,
            )
        }
    }

    /** What each part of [NtagLock] is, for a message. */
    private val LOCK_PARTS = mapOf(
        NtagLock.Part.Settings to "its settings",
        NtagLock.Part.Data to "the data from page 16 on",
        NtagLock.Part.Marked to "the read-only mark",
        NtagLock.Part.Static to "the data up to page 15",
    )

    private const val CANT_PROTECT =
        "SeedTag can give a write password only to an NTAG213, NTAG215 or NTAG216, or an NTAG21x lookalike that " +
            "works like one, and this tag isn't one. Nothing was changed."
    private const val PROTECTED = "This tag has a write password, so nothing can be written to it. Nothing was changed."
    /** The view model zeroed the secret (a wipe) after this scan was armed. */
    private const val CLEARED = "SeedTag cleared what it had decrypted in the meantime. Nothing was changed. Verify the tag again."
    private const val NOT_THIS_TAG = "This isn't the tag you just verified. Nothing was changed."

    /**
     * The first part of every Protect and Unprotect tap: the tag holds exactly [expected] (an NDEF read,
     * which a write password never blocks). Null if it does; the NDEF connection is closed either way.
     */
    private fun notHolding(tag: Tag, expected: String): TagResult? {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            val msg = readMessage(it) ?: return TagResult.Failed(NOT_READ)
            return if (firstText(msg) != expected) TagResult.Failed(NOT_THIS_TAG) else null
        }
    }

    /**
     * One tap of Protect ([ProtectStage]). Protection covers writes only (PROT 0) and never locks anyone
     * out (AUTHLIM 0), from page 3 on ([NtagConfig.AUTH0_ON]): reading stays open to every phone.
     * Every settings write is read back. The secret is copied, and the copies zeroed.
     */
    private fun protect(tag: Tag, req: TagRequest.Protect, report: (TagStep) -> Unit): TagResult {
        val raw = Type2Raw.of(tag) ?: return TagResult.Failed(CANT_PROTECT)
        val pwd = TagKey.ntagPwd(req.secret)
        val pack = TagKey.ntagPack(req.secret)
        try {
            if (pwd.all { it == 0.toByte() } && pack.all { it == 0.toByte() }) return TagResult.Failed(CLEARED)
            notHolding(tag, req.expected)?.let { return it }
            raw.use { r ->
                r.connect()
                // Writes follow: no GET_VERSION here. Verify identified the chip just before.
                val state = ntagState(r, askVersion = false) ?: return TagResult.Failed(CANT_PROTECT)
                val chip = state.identity.chip
                val config = state.config
                if (config.settingsLocked) {
                    return TagResult.Failed("This tag's settings are locked for good, so it can't be given a write password. Nothing was changed.")
                }
                return when (req.stage) {
                    ProtectStage.Store -> store(r, state, req, pwd, pack, report)
                    ProtectStage.SwitchOn -> switchOn(r, chip, config, req, pwd, pack, report)
                    ProtectStage.Confirm -> confirm(r, chip, config, req, pwd, pack, report)
                }
            }
        } finally {
            pwd.fill(0)
            pack.fill(0)
        }
    }

    /** Tap 1: PWD, PACK and protection to "writes only", while protection is still off. */
    private fun store(r: Type2Raw, state: NtagState, req: TagRequest.Protect, pwd: ByteArray, pack: ByteArray, report: (TagStep) -> Unit): TagResult {
        val chip = state.identity.chip
        val config = state.config
        if (config.protection != Protection.Off) {
            // Already on: with this backup's password (an earlier Protect), go on to the check; with another, stop.
            val answer = r.pwdAuth(pwd)
            return if (answer != null && answer.contentEquals(pack)) TagResult.ProtectSwitchedOn
            else TagResult.Failed("This tag already has a write password, and it isn't this backup's. SeedTag can't change it. Nothing was changed.")
        }
        // A chip that shows its password to anyone (an NXP chip reads PWD as zeros): it would protect nothing.
        if (config.passwordReadable) return TagResult.NotEnforced(switchedOff = true)
        if (!state.identity.genuine && !req.otherMaker) return TagResult.OtherMaker(chip)
        report(TagStep("Storing the write password", 2, PROTECT_STEPS, writes = true))
        val cfg1 = config.cfg1WritesOnly()
        r.write(chip.pwdPage, pwd)
        r.write(chip.packPage, pack + byteArrayOf(0, 0))
        r.write(chip.cfgPage + 1, cfg1)
        val after = config.reread(r.read(chip.cfgPage))
        if (!after.settingsAre(config.cfg0, cfg1)) {
            return TagResult.Failed("Storing the password didn't go through. Hold the tag still and try again.", changedTag = true)
        }
        return TagResult.ProtectStored
    }

    /** Tap 2, a new session: the chip must accept the stored password before AUTH0 switches protection on. */
    private fun switchOn(
        r: Type2Raw, chip: NtagChip, config: NtagConfig, req: TagRequest.Protect, pwd: ByteArray, pack: ByteArray,
        report: (TagStep) -> Unit,
    ): TagResult {
        val again = req.next(ProtectStage.Store)
        if (config.protection == Protection.Off && !config.writesOnly) {
            return TagResult.Failed("The first step didn't stay on the tag. Hold it still to do it again.", retry = again)
        }
        if (config.passwordReadable) {
            // A chip that shows its password to anyone: switching it on would protect nothing. (A PACK that
            // reads back is fine: it isn't the password.)
            return TagResult.NotEnforced(switchedOff = config.protection == Protection.Off)
        }
        val answer = r.pwdAuth(pwd)
        if (answer == null || !answer.contentEquals(pack)) {
            return if (config.protection == Protection.Off) {
                TagResult.Failed("The tag didn't accept the password just stored. Hold it still to store it again.", retry = again)
            } else {
                TagResult.Failed("This tag already has a write password, and it isn't this backup's. Nothing was changed.")
            }
        }
        report(TagStep("Switching the write password on", 2, PROTECT_STEPS, writes = true))
        val cfg0 = config.cfg0With(NtagConfig.AUTH0_ON)
        r.write(chip.cfgPage, cfg0)
        val after = config.reread(r.read(chip.cfgPage))
        if (!after.settingsAre(cfg0, config.cfg1)) {
            return TagResult.Failed("Switching the password on didn't go through. Hold the tag still and try again.", changedTag = true)
        }
        return TagResult.ProtectSwitchedOn
    }

    /** Spare-page test pattern ("STAG"): a write the chip must refuse without the password. */
    private val PROBE = byteArrayOf(0x53, 0x54, 0x41, 0x47)

    /**
     * Tap 3, a new session, no password sent: reading works (the backup was just read), a write of [PROBE]
     * to the last spare page after the backup is refused, and so is switching the password off (CFG0 with
     * AUTH0 off). Each checked by reading again after reconnecting. If the chip took either write,
     * protection is switched off again and the page zeroed.
     */
    private fun confirm(
        r: Type2Raw, chip: NtagChip, config: NtagConfig, req: TagRequest.Protect, pwd: ByteArray, pack: ByteArray,
        report: (TagStep) -> Unit,
    ): TagResult {
        if (config.protection != Protection.Write || config.auth0 != NtagConfig.AUTH0_ON) {
            return TagResult.Failed(
                "The password isn't switched on on this tag any more. Hold it still to switch it on again.",
                retry = req.next(ProtectStage.SwitchOn),
            )
        }
        val cc = r.read(3)
        val data = readDataArea(r, (cc[2].toInt() and 0xFF) * 8)
        val page = Type2Memory.freePages(data)?.last
            ?: return TagResult.Failed("SeedTag couldn't find a spare page on this tag to test the password on. It is switched on, but untested.")
        val before = Type2Memory.page(data, page)
        if (!before.all { it == 0.toByte() }) {
            return TagResult.Failed("This tag has data after the backup that SeedTag didn't expect, so it can't run its test write. Nothing was changed.")
        }
        report(TagStep("Checking that the tag refuses writes without it", 2, PROTECT_STEPS, writes = true))
        val zeros = ByteArray(4)
        tryWithoutPassword(r) { r.write(page, PROBE) }
        if (r.read(page).copyOf(4).contentEquals(zeros)) {
            // The data was safe; now the settings: without the password, try to switch it off. A chip that
            // allowed that would let anyone remove the protection.
            tryWithoutPassword(r) { r.write(chip.cfgPage, config.cfg0With(NtagConfig.AUTH0_OFF)) }
            if (config.reread(r.read(chip.cfgPage)).settingsAre(config.cfg0, config.cfg1)) return TagResult.Protected
        } else {
            r.write(page, zeros)
        }
        // It took a write without the password: it doesn't enforce one. Leave it switched off.
        val answer = r.pwdAuth(pwd)
        if (answer == null || !answer.contentEquals(pack)) {
            r.close() // the refusal ended the session
            r.connect()
        }
        r.write(chip.cfgPage, config.cfg0With(NtagConfig.AUTH0_OFF))
        val after = config.reread(r.read(chip.cfgPage))
        return TagResult.NotEnforced(switchedOff = after.protection == Protection.Off && r.read(page).copyOf(4).contentEquals(zeros))
    }

    /**
     * A write the chip should refuse, as there's no password in this session. A refusal (a NAK) ends the
     * chip's session, so afterwards [r] is closed and connected again (Android selects the tag anew, which
     * also drops any authentication); a read then shows whether the write went through.
     */
    private fun tryWithoutPassword(r: Type2Raw, write: () -> Unit) {
        try {
            write()
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            // Refused, as it should be; the read afterwards decides.
        }
        r.close()
        r.connect()
    }

    /** Switch the write password off (AUTH0 past the last page). PWD and PACK stay on the chip, unused. */
    private fun unprotect(tag: Tag, req: TagRequest.Unprotect, report: (TagStep) -> Unit): TagResult {
        val raw = Type2Raw.of(tag) ?: return TagResult.Failed(CANT_PROTECT)
        val pwd = TagKey.ntagPwd(req.secret)
        val pack = TagKey.ntagPack(req.secret)
        try {
            if (pwd.all { it == 0.toByte() } && pack.all { it == 0.toByte() }) return TagResult.Failed(CLEARED)
            notHolding(tag, req.expected)?.let { return it }
            raw.use { r ->
                r.connect()
                // Writes follow: no GET_VERSION here. Verify identified the chip just before.
                val state = ntagState(r, askVersion = false) ?: return TagResult.Failed(CANT_PROTECT)
                val chip = state.identity.chip
                val config = state.config
                if (config.protection == Protection.Off) {
                    clearOurPack(r, chip, config, pack) // a retry after the tag moved away
                    return TagResult.Unprotected
                }
                if (config.settingsLocked) {
                    return TagResult.Failed("This tag's settings are locked for good, so its write password can't be removed.")
                }
                val answer = r.pwdAuth(pwd)
                if (answer == null || !answer.contentEquals(pack)) {
                    return TagResult.Failed("This tag's write password isn't this backup's, so SeedTag can't remove it. Nothing was changed.")
                }
                report(TagStep("Removing the write password", 2, PROTECT_STEPS, writes = true))
                val cfg0 = config.cfg0With(NtagConfig.AUTH0_OFF)
                r.write(chip.cfgPage, cfg0)
                val after = config.reread(r.read(chip.cfgPage))
                if (!after.settingsAre(cfg0, config.cfg1)) {
                    return TagResult.Failed("Removing the password didn't go through. Hold the tag still and try again.", changedTag = true)
                }
                clearOurPack(r, chip, after, pack)
                return TagResult.Unprotected
            }
        } finally {
            pwd.fill(0)
            pack.fill(0)
        }
    }

    /**
     * On a lookalike that lets anyone read the PACK back: zero it once the password is off, if it's this
     * backup's, so the tag reads as it did before Protect. (The password itself stays stored, unreadable,
     * as on an NXP chip.) Best effort: the password is already off, so a refused write changes nothing.
     */
    private fun clearOurPack(r: Type2Raw, chip: NtagChip, config: NtagConfig, pack: ByteArray) {
        if (config.protection != Protection.Off || !config.packReadable || !config.packIs(pack)) return
        try {
            r.write(chip.packPage, ByteArray(4))
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            // Refused: left as it is.
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
