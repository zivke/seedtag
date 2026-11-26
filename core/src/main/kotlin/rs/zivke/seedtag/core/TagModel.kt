// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

/** What the UI wants done with the next tag that touches the phone. */
sealed interface TagRequest {
    /** First Back up step: find out whether the tag can take a backup, and how big. Writes nothing. */
    data object Check : TagRequest
    /** Write [text]; refuses to overwrite a non-empty tag unless it's the [overwrite] the user confirmed. */
    data class Write(val text: String, val overwrite: OverwriteTarget? = null) : TagRequest
    /** Read the tag back and compare with [expected] (second tap after formatting a blank tag). */
    data class CheckWritten(val expected: String) : TagRequest
    /** Read the tag's text; with [withInfo], also its chip and write protection ([TagInfo]), for Verify. */
    data class Read(val withInfo: Boolean = false) : TagRequest
    /** Make the tag read-only, but only if it holds exactly [expected]. */
    /**
     * Make the tag that holds [expected] read-only. On an NTAG21x layout SeedTag writes the lock itself
     * ([NtagLock]); [lookalike]: the user accepted the warning for a chip that isn't NXP's.
     */
    data class Lock(val expected: String, val lookalike: Boolean = false) : TagRequest
    /** The Erase tab, first tap: what the tag holds and whether it can be erased. Writes nothing. */
    data object Inspect : TagRequest
    /**
     * Erase [target]: overwrite all of its NDEF data with zeros, then leave it empty. [text]: its first
     * Text record when it was scanned (for a backup, the Base64 blob), so whatever describes that
     * backup can be cleared once it's gone; tag I/O doesn't use it.
     */
    data class Erase(val target: OverwriteTarget, val text: String? = null) : TagRequest
    /** Finish [Rest.Interrupted]: clear the old data after the backup, if the tag holds exactly [expected]. */
    data class ClearRest(val expected: String) : TagRequest
    /**
     * Give the tag its write password ([TagKey]), only if it holds exactly [expected], one [stage] per tap.
     * [secret] is the view model's array (it zeroes it); tag I/O copies it for each use. [otherMaker]:
     * the user was warned that the chip isn't NXP's and went on; without it, such a chip only gets
     * [TagResult.OtherMaker] back.
     */
    class Protect(val expected: String, val secret: ByteArray, val stage: ProtectStage, val otherMaker: Boolean = false) : TagRequest {
        fun next(stage: ProtectStage) = Protect(expected, secret, stage, otherMaker)
    }
    /** Switch the write password off again, only if the tag holds exactly [expected]. */
    class Unprotect(val expected: String, val secret: ByteArray) : TagRequest
}

/**
 * Protect takes three taps, each in a new session with the chip, so that every step is on the tag for
 * good before the next relies on it.
 */
enum class ProtectStage {
    /** Write PWD and PACK, and protection to "writes only"; protection stays off. */
    Store,
    /** The chip must accept the new password (PWD_AUTH) before AUTH0 switches protection on. */
    SwitchOn,
    /** Without the password: reading works, and a harmless write is refused. */
    Confirm,
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
    /**
     * Whether the tag with [uid] that holds [current] (its NDEF message's bytes; null if it holds
     * none, which matches a target that held none either) is this one.
     */
    fun matches(uid: ByteArray?, current: ByteArray?): Boolean {
        // Random UIDs (ISO 14443-3 NUIDs start with 08h) change on every tap: only the content can tell.
        val uidStable = this.uid.isNotEmpty() && !(this.uid.size == 4 && this.uid[0] == 0x08.toByte())
        return (current ?: ByteArray(0)).contentEquals(message) && (!uidStable || (uid ?: ByteArray(0)).contentEquals(this.uid))
    }
}

enum class TagContent {
    Empty, Backup, NewerBackup, Other;

    companion object {
        /**
         * What a tag holds: [empty] if it has no NDEF message or only empty records, else by its first
         * Text record's [text] ([SeedBox.kindOf]; null if there's none).
         */
        fun of(empty: Boolean, text: String?): TagContent = if (empty) Empty else when (SeedBox.kindOf(text)) {
            SeedBox.Kind.Current -> Backup
            SeedBox.Kind.Newer -> NewerBackup
            SeedBox.Kind.NotBackup -> Other
        }
    }
}

/**
 * What the Erase tab's scan found. [ndef]: it's NDEF-formatted (a blank tag has nothing to erase).
 * [text]: the first Text record (for a backup, the Base64 blob: not secret, anyone can read it from
 * the tag), so the password check needs no second scan. [target]: the tag and content an erase is
 * bound to, null if it can't be erased. [protected]: it has a write password SeedTag set, so it can't
 * be erased until that's removed ([target] is null then).
 */
data class EraseCheck(
    val supported: Boolean,
    val ndef: Boolean,
    val writable: Boolean,
    val content: TagContent,
    val capacity: Int?,
    val text: String?,
    val target: OverwriteTarget?,
    val protected: Boolean = false,
)

/**
 * What the first Back up step found. [supported]: the tag can hold NDEF at all. [ndef]: it's
 * NDEF-formatted; if not, it's blank and gets formatted on the first write. [capacity]: bytes
 * for the NDEF message, null if unknown; for a blank tag it's [estimated] from the chip's memory.
 * [classic]: a MIFARE Classic tag, which many phones can't read at all, so the backup might not be
 * readable when it's needed. [canMakeReadOnly]: whether this phone can make it read-only for good, null
 * for a blank tag (not known before it's formatted). [chip]: which chip it is, for a write password
 * ([TagInfo.identity] null: not one SeedTag can protect), null if that couldn't be checked.
 */
data class TagCheck(
    val supported: Boolean,
    val ndef: Boolean,
    val capacity: Int?,
    val estimated: Boolean = false,
    val writable: Boolean = true,
    val content: TagContent = TagContent.Empty,
    val classic: Boolean = false,
    val canMakeReadOnly: Boolean? = null,
    val chip: TagInfo? = null,
) {
    /** It has a write password in force: nothing can be written until it's removed. */
    val protected: Boolean get() = chip?.identity != null && chip.protection != Protection.Off
    /** Longest backup that fits, null if none does or the size is unknown. */
    val maxWords: Int? get() = capacity?.let(SeedBox::maxWordsFor)
    val tooSmall: Boolean get() = capacity != null && maxWords == null
    val usable: Boolean get() = supported && writable && !tooSmall && !protected
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
    /** [differences]: how the tag's message differs from what SeedTag writes; empty if it doesn't. */
    data class Read(val text: String, val differences: List<String> = emptyList(), val info: TagInfo? = null) : TagResult
    /**
     * Made read-only. [checked]: SeedTag wrote the lock itself and read every part back ([NtagLock]): data and
     * settings locked for good. Otherwise Android did it, and SeedTag can't check this chip's lock bits.
     */
    data class Locked(val checked: Boolean) : TagResult
    /**
     * Lock found the tag already not writable: nothing was changed. [checked]: SeedTag read its lock bits and
     * it's read-only for good; otherwise it can't tell whether that's permanent.
     */
    data class AlreadyReadOnly(val checked: Boolean) : TagResult
    /**
     * Lock found the right tag, but this phone can't make it read-only: nothing was changed, and holding
     * it again wouldn't help, so the scan ends.
     */
    data object CantMakeReadOnly : TagResult
    /** Tag already holds data; [existing] is its text if it was a Text record; [target] identifies it. */
    data class NotEmpty(val existing: String?, val target: OverwriteTarget) : TagResult
    data class Inspected(val check: EraseCheck) : TagResult
    /**
     * Every byte of the tag's NDEF data was overwritten with zeros, and the tag is now empty. [rest]:
     * whether its data area was then checked page by page ([Rest.Cleared]), couldn't be on this kind
     * of tag, or the tag moved away first.
     */
    data class Erased(val rest: Rest) : TagResult
    /**
     * [changedTag]: the tag may already have been changed (a write or an erase was interrupted), so the
     * message matters even when it arrives for a canceled scan. [retry]: the scan stays armed with this
     * request instead of the same one (Protect going back a step).
     */
    data class Failed(val message: String, val changedTag: Boolean = false, val retry: TagRequest? = null) : TagResult

    /** Protect's first tap: the chip isn't NXP's ([NtagIdentity.genuine]); nothing was written, the user decides. */
    data class OtherMaker(val chip: NtagChip) : TagResult
    /** Protect, [ProtectStage.Store] done: the password is on the chip, protection still off. */
    data object ProtectStored : TagResult
    /** Protect, [ProtectStage.SwitchOn] done: protection is on, not yet checked on a fresh tap. */
    data object ProtectSwitchedOn : TagResult
    /** Protect, [ProtectStage.Confirm]: reads work without the password and a write was refused. */
    data object Protected : TagResult
    /**
     * Protect, [ProtectStage.Confirm]: the chip accepted a write without the password, so it doesn't enforce
     * one. [switchedOff]: SeedTag switched it off again, so the chip is as it was.
     */
    data class NotEnforced(val switchedOff: Boolean) : TagResult
    data object Unprotected : TagResult
}

/** The record Erase fills a tag's NDEF area with: TNF unknown, no type, no ID, a payload of zeros. */
object EraseFiller {
    const val TNF_UNKNOWN = 5

    /** Bytes of a message holding the one filler record with [payload] bytes: short record up to 255. */
    fun messageSize(payload: Int): Int = payload + if (payload <= 255) 3 else 6

    /**
     * The payload size whose message is as close to [maxSize] bytes as an NDEF record can be: exactly,
     * except for a few sizes just above 255 bytes, where the short record format ends and up to 3
     * bytes at the end stay as they were.
     */
    fun payloadFor(maxSize: Int): Int {
        var n = (maxSize - 3).coerceAtLeast(0)
        while (n > 0 && messageSize(n) > maxSize) n--
        return n
    }

    /** A message of [records] records whose only one is a filler: what an interrupted erase leaves behind. */
    fun isFiller(records: Int, tnf: Int, type: ByteArray, payload: ByteArray): Boolean =
        records == 1 && tnf == TNF_UNKNOWN && type.isEmpty() && payload.all { it == 0.toByte() }
}

/**
 * Type 2 tag memory (NTAG, MIFARE Ultralight and compatible chips): 4-byte pages, the data area from
 * page 4, read with READ (16 bytes, 4 pages, from a page on) and written with WRITE (one page). Raw
 * commands, so they work on any Type 2 chip, also those Android doesn't offer as MifareUltralight.
 */
object Type2Memory {
    /** The data area starts here. */
    const val FIRST_DATA_PAGE = 4

    /** READ (30h): 16 bytes from [page]. The reader adds the CRC. */
    fun readCommand(page: Int): ByteArray {
        require(page in 0..255) { "page $page" }
        return byteArrayOf(0x30, page.toByte())
    }

    /** WRITE (A2h): [data] (4 bytes) to [page]. */
    fun writeCommand(page: Int, data: ByteArray): ByteArray {
        require(page in 0..255 && data.size == 4) { "page $page, ${data.size} bytes" }
        return byteArrayOf(0xA2.toByte(), page.toByte()) + data
    }

    /**
     * The tag pages Back up and Erase zero: the whole pages after the one that holds the TLVs'
     * terminator, to the end of the data area [data] (from page 4). Never the terminator's own page:
     * a write torn there could damage the message before it, and Android fills the rest of it with
     * zeros. Empty if the message runs to the end; null if [Type2Tlv.freeAfterTerminator] isn't sure
     * of the layout, so nothing may be written.
     */
    fun freePages(data: ByteArray): IntRange? {
        val free = Type2Tlv.freeAfterTerminator(data) ?: return null
        return FIRST_DATA_PAGE + (free + 3) / 4 until FIRST_DATA_PAGE + data.size / 4
    }

    /** The 4 bytes of tag page [page] in the data area [data] (from page 4). */
    fun page(data: ByteArray, page: Int): ByteArray =
        data.copyOfRange(4 * (page - FIRST_DATA_PAGE), 4 * (page - FIRST_DATA_PAGE) + 4)

    /** Whether every page of [pages] in the data area [data] is zero. */
    fun allZero(data: ByteArray, pages: IntRange): Boolean = pages.all { p -> page(data, p).all { it == 0.toByte() } }

    /** Pages probed for the wrap, from small chips to large, each a few pages before a known chip's end. */
    private val PROBES = intArrayOf(0x0D, 0x11, 0x27, 0x2A, 0x39, 0x84, 0xE4)

    /**
     * The page count a blank Type 2 chip has, from where READ wraps back to page 0: [read] returns the
     * 16 bytes from a page. Null if a read doesn't give 16 bytes or there's no wrap in the probes. Its
     * exceptions (a lost tag, a refused page) go to the caller.
     */
    fun wrapPage(read: (Int) -> ByteArray): Int? {
        val head = read(0)
        if (head.size != 16) return null
        for (p in PROBES) {
            val r = read(p)
            if (r.size != 16) return null
            for (k in 1..3) if ((0 until 4).all { r[4 * k + it] == head[it] }) return p + k
        }
        return null
    }
}

/**
 * What a write that threw left on the tag, from a read over a new connection (board B32): a tag that refuses
 * a write and one pulled away both make it throw, but only one of them changes anything.
 */
enum class AfterWrite {
    /** It holds what was being written: the write went through, only its reply was lost. */
    Written,
    /** It reads as before: the tag refused the write, or was pulled away before any of it. */
    Unchanged,
    /** Something else, or a malformed message: part of the write is on it. */
    Changed,
    /** It couldn't be read again: gone, most likely. */
    Unknown;

    companion object {
        /**
         * [before] and [now]: the message's bytes before the write and after it (null: no message); [written]:
         * [now] is what was being written.
         */
        fun of(before: ByteArray?, now: ByteArray?, written: Boolean): AfterWrite = when {
            written -> Written
            now == null || before == null -> if (now == null && before == null) Unchanged else Changed
            now.contentEquals(before) -> Unchanged
            else -> Changed
        }
    }
}
