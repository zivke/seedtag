// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import rs.zivke.seedtag.core.NdefText
import rs.zivke.seedtag.core.TagKey
import rs.zivke.seedtag.core.UltralightCAuth
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** What the UI wants done with the next tag that touches the phone. */
sealed interface TagRequest {
    /** Write [text]; refuses to overwrite a non-empty tag unless [allowOverwrite]. */
    data class Write(val text: String, val allowOverwrite: Boolean = false) : TagRequest
    /** Read the tag back and compare with [expected] (second tap after formatting a blank tag). */
    data class CheckWritten(val expected: String) : TagRequest
    /** Read the tag's text; with [withInfo], also its chip and write protection ([TagInfo]). */
    data class Read(val withInfo: Boolean = false) : TagRequest
    /** Make the tag read-only, but only if it holds exactly [expected]. */
    data class Lock(val expected: String) : TagRequest
    /**
     * Turn on the tag's write password ([TagKey]), only if it holds exactly [expected], in two taps:
     * the first ([finish] false) stores the password while protection is still off, the second
     * checks the tag accepts it, in a new session, before switching protection on.
     * [secret] is the view model's array (zeroed there); it's copied for each use.
     */
    class Protect(val expected: String, val secret: ByteArray, val finish: Boolean) : TagRequest
    /** Switch the write password off again, only if the tag holds exactly [expected]. */
    class Unprotect(val expected: String, val secret: ByteArray) : TagRequest
}

/** The chips SeedTag can write-protect. [cfgPage]: first configuration page (AUTH0 lives there). */
enum class Chip(val label: String, val cfgPage: Int) {
    UltralightC("MIFARE Ultralight C", 0x28),
    Ntag213("NTAG213", 0x29),
    Ntag215("NTAG215", 0x83),
    Ntag216("NTAG216", 0xE3),
}

enum class Protection {
    Off,
    /** Writes need the password, reads don't: what Protect sets. */
    Write,
    /** Reads need it too. SeedTag never sets this. */
    ReadWrite,
}

/**
 * What Verify reports about the tag itself. [chip] is null for anything SeedTag can't protect (or
 * couldn't identify); [protection] and [settingsLocked] mean nothing then. [settingsLocked]: the
 * protection settings are frozen, so they can't be changed any more.
 */
data class TagInfo(val chip: Chip?, val readOnly: Boolean, val protection: Protection = Protection.Off, val settingsLocked: Boolean = false)

sealed interface TagResult {
    /** [text] was written and read back. */
    data class Written(val text: String) : TagResult
    /** A blank tag was formatted with [text], but couldn't be read back on the same tap. */
    data class WrittenUnchecked(val text: String) : TagResult
    data class Read(val text: String, val info: TagInfo? = null) : TagResult
    data object Locked : TagResult
    /** First Protect tap done: the password is stored, protection still off. */
    data object ProtectPrepared : TagResult
    data object Protected : TagResult
    data object Unprotected : TagResult
    /** Tag already holds data; [existing] is its text if it was a Text record. */
    data class NotEmpty(val existing: String?) : TagResult
    /** Nothing done. The scan stays armed: with [retry] instead of the same request. */
    data class Failed(val message: String, val retry: TagRequest? = null) : TagResult
}

/** Blocking tag I/O. Call only from the NFC reader-mode callback thread. */
object TagIo {

    private const val NOT_READ = "Couldn't read the tag properly (it may have moved away too soon). Hold it still against the phone and tap again."

    fun perform(tag: Tag, request: TagRequest): TagResult = try {
        when (request) {
            is TagRequest.Write -> write(tag, request)
            is TagRequest.CheckWritten -> checkWritten(tag, request.expected)
            is TagRequest.Read -> read(tag, request.withInfo)
            is TagRequest.Lock -> lock(tag, request.expected)
            is TagRequest.Protect -> protect(tag, request)
            is TagRequest.Unprotect -> unprotect(tag, request)
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
            // Never format a tag that may already hold a backup.
            if (looksNdefFormatted(tag)) return TagResult.Failed(NOT_READ)
            formatable.use { it.connect(); it.format(message) }
            // This Tag object only knows the techs it had when discovered, so Ndef.get()
            // stays null after formatting: the read-back has to wait for the next tap.
            return TagResult.WrittenUnchecked(req.text)
        }
        if (ndef.isWritable && isProtected(tag)) return TagResult.Failed(PROTECTED)
        ndef.use {
            it.connect()
            if (!it.isWritable) return TagResult.Failed("This tag is read-only.")
            if (message.byteArrayLength > it.maxSize) {
                return TagResult.Failed("Tag too small: needs ${message.byteArrayLength} bytes, has ${it.maxSize}.")
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
            if (firstText(back) != req.text) return TagResult.Failed("Read-back mismatch after writing. The tag may be faulty – don't use it.")
            return TagResult.Written(req.text)
        }
    }

    private fun checkWritten(tag: Tag, expected: String): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            val msg = readMessage(it) ?: return TagResult.Failed(NOT_READ)
            if (firstText(msg) != expected) {
                return TagResult.Failed("This tag doesn't hold what was just written. Hold the same tag again; if this keeps failing, the tag may be faulty.")
            }
            return TagResult.Written(expected)
        }
    }

    private fun read(tag: Tag, withInfo: Boolean): TagResult {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        val text = ndef.use {
            it.connect()
            val msg = readMessage(it) ?: return TagResult.Failed(
                "The tag is empty, or it couldn't be read properly. Hold it still against the phone and tap again.",
            )
            firstText(msg) ?: return TagResult.Failed("The tag holds no text record.")
        }
        if (!withInfo) return TagResult.Read(text)
        // The text is what counts: if the chip can't be looked at on this tap, Verify still works.
        val info = try {
            nfcA(tag) { a -> identify(a)?.let { s -> s.info(ndef.isWritable) } } ?: TagInfo(null, !ndef.isWritable)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
        return TagResult.Read(text, info)
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
        }
        if (isProtected(tag)) {
            return TagResult.Failed("This tag is write-protected. Remove the protection first (after Verify), then make it read-only. Nothing was changed.")
        }
        ndef.use {
            it.connect()
            return if (it.makeReadOnly()) TagResult.Locked else TagResult.Failed("Locking failed. The tag is unchanged.")
        }
    }

    // ===================================================== write password ===
    //
    // Datasheets: MF0ICU2 (MIFARE Ultralight C) and NTAG213/215/216. Protection is always
    // "writes only" (Ultralight C AUTH1 = 1, NTAG PROT = 0, AUTHLIM = 0): reading never needs the
    // password and no number of wrong tries locks anyone out. AUTH0 = 03h covers the capability
    // container, the data, the lock bytes and the settings. AUTH0 is only ever written after the
    // tag has proven it accepts the password, in a new session (the Ultralight C only uses a new
    // key after the field is switched off and on).

    private const val PROTECTED = "This tag is write-protected. Verify it with its backup password and remove the protection " +
        "first. Nothing was written."
    private const val UNSUPPORTED = "SeedTag can only write-protect MIFARE Ultralight C and NTAG213/215/216 tags. This tag " +
        "is something else, or couldn't be identified. Nothing was changed."
    private const val NOT_CONFIRMED = "Couldn't confirm the tag's new settings (it may have moved away too soon). Hold it " +
        "still against the phone and tap again."
    private const val AUTH0_ON = 0x03

    private fun protect(tag: Tag, req: TagRequest.Protect): TagResult {
        val secret = req.secret.copyOf()
        try {
            if (secret.all { it == 0.toByte() }) return TagResult.Failed("Verify the tag again first.")
            checkTarget(tag, req.expected, writable = true)?.let { return it }
            return nfcA(tag) { a ->
                val s = identify(a) ?: return TagResult.Failed(UNSUPPORTED)
                if (s.settingsLocked) return TagResult.Failed("This tag's protection settings are locked, so it can't be protected. Nothing was changed.")
                if (req.finish) finishProtect(a, s, secret, req) else prepareProtect(a, s, secret)
            }
        } finally {
            secret.fill(0)
        }
    }

    /** First tap: store the password and make sure protection will cover writes only. AUTH0 stays off. */
    private fun prepareProtect(a: NfcA, s: ChipState, secret: ByteArray): TagResult {
        if (s.protection != Protection.Off) return TagResult.Failed("This tag is already write-protected. Nothing was changed.")
        if (s.chip == Chip.UltralightC) {
            val key = TagKey.ultralightCKey(secret)
            val pages = TagKey.ultralightCKeyPages(key)
            try {
                for (i in 0 until 4) write4(a, 0x2C + i, pages, 4 * i)
            } finally {
                key.fill(0); pages.fill(0)
            }
            if (!s.writesOnly) write4(a, 0x2B, byteArrayOf(0x01, 0, 0, 0)) // AUTH1 = 1: reads stay open
        } else {
            if (!s.writesOnly) {
                // ACCESS: PROT 0 (reads stay open), AUTHLIM 0 (no lock-out); NFC counter bits as they were.
                write4(a, s.chip.cfgPage + 1, byteArrayOf((s.u(4) and 0x18).toByte(), s.cfg[5], s.cfg[6], s.cfg[7]))
            }
            val pwd = TagKey.ntagPwd(secret)
            val pack = TagKey.ntagPack(secret)
            val packPage = byteArrayOf(pack[0], pack[1], 0, 0)
            try {
                write4(a, s.chip.cfgPage + 2, pwd)
                write4(a, s.chip.cfgPage + 3, packPage)
            } finally {
                pwd.fill(0); pack.fill(0); packPage.fill(0)
            }
        }
        val back = state(a, s.chip)
        if (back.protection != Protection.Off || !back.writesOnly) return TagResult.Failed(NOT_CONFIRMED)
        return TagResult.ProtectPrepared
    }

    /** Second tap, new session: the tag must accept the password before AUTH0 switches protection on. */
    private fun finishProtect(a: NfcA, s: ChipState, secret: ByteArray, req: TagRequest.Protect): TagResult {
        val again = TagRequest.Protect(req.expected, req.secret, finish = false)
        if (!s.writesOnly) {
            return TagResult.Failed("The first step didn't stick, so protection is still off. Hold the tag to the phone again to repeat it.", again)
        }
        if (!authenticate(a, s.chip, secret)) {
            return TagResult.Failed(
                "The tag didn't accept its new write password, so protection is still off. Take the tag away and hold it to " +
                    "the phone again to store the password again.",
                again,
            )
        }
        if (s.protection == Protection.Off) setAuth0(a, s, AUTH0_ON)
        return if (state(a, s.chip).protection == Protection.Write) TagResult.Protected else TagResult.Failed(NOT_CONFIRMED)
    }

    private fun unprotect(tag: Tag, req: TagRequest.Unprotect): TagResult {
        val secret = req.secret.copyOf()
        try {
            if (secret.all { it == 0.toByte() }) return TagResult.Failed("Verify the tag again first.")
            checkTarget(tag, req.expected, writable = false)?.let { return it }
            return nfcA(tag) { a ->
                val s = identify(a) ?: return TagResult.Failed(UNSUPPORTED)
                if (s.protection == Protection.Off) return TagResult.Unprotected
                if (s.auth0Locked) return TagResult.Failed("This tag's protection settings are locked, so the protection can't be removed.")
                if (!authenticate(a, s.chip, secret)) {
                    return TagResult.Failed(
                        "This tag's write password doesn't come from this backup's password, so SeedTag can't remove it. Nothing was changed.",
                    )
                }
                setAuth0(a, s, if (s.chip == Chip.UltralightC) 0x30 else 0xFF)
                if (state(a, s.chip).protection == Protection.Off) TagResult.Unprotected else TagResult.Failed(NOT_CONFIRMED)
            }
        } finally {
            secret.fill(0)
        }
    }

    private fun setAuth0(a: NfcA, s: ChipState, value: Int) {
        if (s.chip == Chip.UltralightC) write4(a, 0x2A, byteArrayOf(value.toByte(), 0, 0, 0))
        else write4(a, s.chip.cfgPage, byteArrayOf(s.cfg[0], s.cfg[1], s.cfg[2], value.toByte()))
    }

    /** Ultralight C: mutual 3DES authentication. NTAG: PWD_AUTH, and the tag must answer with our PACK. */
    private fun authenticate(a: NfcA, chip: Chip, secret: ByteArray): Boolean {
        if (chip == Chip.UltralightC) {
            val key = TagKey.ultralightCKey(secret)
            val rndA = ByteArray(8).also(SecureRandom()::nextBytes)
            val auth = UltralightCAuth(key, rndA)
            val cmd = ByteArray(17)
            try {
                val r1 = a.transceive(byteArrayOf(0x1A, 0x00))
                if (r1.size != 9 || r1[0] != 0xAF.toByte()) return false
                cmd[0] = 0xAF.toByte()
                auth.answer(r1.copyOfRange(1, 9)).also { it.copyInto(cmd, 1) }.fill(0)
                // A tag with another key answers with a NAK, which some phones report as an exception.
                val r2 = refusedAsNull { a.transceive(cmd) } ?: return false
                return r2.size == 9 && r2[0] == 0.toByte() && auth.verify(r2.copyOfRange(1, 9))
            } finally {
                auth.wipe(); key.fill(0); rndA.fill(0); cmd.fill(0)
            }
        }
        val cmd = ByteArray(5)
        val pack = TagKey.ntagPack(secret)
        try {
            cmd[0] = 0x1B
            TagKey.ntagPwd(secret).also { it.copyInto(cmd, 1) }.fill(0)
            val r = refusedAsNull { a.transceive(cmd) } ?: return false
            return r.size == 2 && MessageDigest.isEqual(r, pack)
        } finally {
            cmd.fill(0); pack.fill(0)
        }
    }

    /** A refusal (NAK) as null; a tag that has gone away still throws. */
    private inline fun refusedAsNull(block: () -> ByteArray): ByteArray? = try {
        block()
    } catch (e: TagLostException) {
        throw e
    } catch (e: IOException) {
        null
    }

    /** Null if the tag holds exactly [expected] (and can be written, if [writable]); else why nothing is done. */
    private fun checkTarget(tag: Tag, expected: String, writable: Boolean): TagResult? {
        val ndef = Ndef.get(tag) ?: return TagResult.Failed(NOT_READ)
        ndef.use {
            it.connect()
            val msg = readMessage(it) ?: return TagResult.Failed(NOT_READ)
            if (firstText(msg) != expected) return TagResult.Failed("This isn't the tag you just verified. Nothing was changed.")
            if (writable && !it.isWritable) return TagResult.Failed("This tag is read-only, so it can't get a write password.")
        }
        return null
    }

    /** True only for a supported chip whose protection is on. Unknown chips, or no answer: false. */
    private fun isProtected(tag: Tag): Boolean = try {
        nfcA(tag) { identify(it)?.protection }.let { it != null && it != Protection.Off }
    } catch (e: IOException) {
        false
    }

    private inline fun <T> nfcA(tag: Tag, block: (NfcA) -> T): T {
        val a = NfcA.get(tag) ?: throw IOException("not NFC-A")
        return a.use {
            it.connect()
            block(it)
        }
    }

    /**
     * Which supported chip this is, without sending any chip a command it doesn't know (that
     * would end the session). Only READ, plus GET_VERSION where the size already says NTAG.
     * The capability container gives the size; a 144-byte tag is an Ultralight C, an NTAG213
     * or e.g. an NTAG203, told apart by where READ wraps back to page 0: the Ultralight C only
     * reads up to 2Bh, the NTAG213 up to 2Ch, the NTAG203 up to 29h.
     */
    private fun identify(a: NfcA): ChipState? {
        val head = read16(a, 0x00) // pages 0..3: UID, lock bytes, capability container
        if (head[0] != 0x04.toByte() || (head[12].toInt() and 0xFF) != 0xE1) return null // NXP, NDEF
        val chip = when (head[14].toInt() and 0xFF) {
            0x12 -> {
                if (read16(a, 0x28).startsWith(head, at = 8)) return null // 28h 29h 00h 01h: 42 pages
                val r = read16(a, 0x2A)
                when {
                    r.startsWith(head, at = 8) -> Chip.UltralightC // 2Ah 2Bh 00h 01h
                    r.startsWith(head, at = 12) && version(a) == 0x0F -> Chip.Ntag213 // 2Ah 2Bh 2Ch 00h
                    else -> return null
                }
            }
            0x3E -> if (version(a) == 0x11) Chip.Ntag215 else return null
            0x6D -> if (version(a) == 0x13) Chip.Ntag216 else return null
            else -> return null
        }
        return state(a, chip)
    }

    private fun state(a: NfcA, chip: Chip) = ChipState(chip, read16(a, chip.cfgPage))

    /** GET_VERSION storage-size byte of an NTAG213/215/216, or -1 for anything else. */
    private fun version(a: NfcA): Int {
        val v = a.transceive(byteArrayOf(0x60))
        val ntag = v.size == 8 && v[0] == 0.toByte() && v[1] == 4.toByte() && v[2] == 4.toByte() && v[3] == 2.toByte() &&
            v[4] == 1.toByte() && v[5] == 0.toByte() && v[7] == 3.toByte()
        return if (ntag) v[6].toInt() and 0xFF else -1
    }

    private fun read16(a: NfcA, page: Int): ByteArray {
        val r = a.transceive(byteArrayOf(0x30, page.toByte()))
        if (r.size != 16) throw IOException("READ refused")
        return r
    }

    private fun write4(a: NfcA, page: Int, data: ByteArray, offset: Int = 0) {
        val cmd = ByteArray(6)
        try {
            cmd[0] = 0xA2.toByte()
            cmd[1] = page.toByte()
            data.copyInto(cmd, 2, offset, offset + 4)
            val r = a.transceive(cmd)
            // ACK is the 4-bit value Ah: one byte on some phones, nothing at all on others.
            if (r.isNotEmpty() && !(r.size == 1 && (r[0].toInt() and 0x0F) == 0x0A)) throw IOException("WRITE refused")
        } finally {
            cmd.fill(0)
        }
    }

    private fun ByteArray.startsWith(head: ByteArray, at: Int) = (0 until 4).all { this[at + it] == head[it] }

    /**
     * A supported chip and 16 bytes read from [Chip.cfgPage]:
     * - Ultralight C: pages 28h (lock bytes 2 and 3), 29h (counter), 2Ah (AUTH0), 2Bh (AUTH1).
     * - NTAG21x: CFG0 (MIRROR, RFUI, MIRROR_PAGE, AUTH0), CFG1 (ACCESS, RFUI x3), PWD and PACK
     *   (both always read as 00h).
     */
    private class ChipState(val chip: Chip, val cfg: ByteArray) {
        fun u(i: Int) = cfg[i].toInt() and 0xFF
        private val ulc = chip == Chip.UltralightC

        val protection: Protection
            get() = if (ulc) {
                if (u(8) < 0x30) (if ((u(12) and 0x01) == 1) Protection.Write else Protection.ReadWrite) else Protection.Off
            } else {
                if (u(3) <= chip.cfgPage + 3) (if ((u(4) and 0x80) == 0) Protection.Write else Protection.ReadWrite) else Protection.Off
            }

        /** Protection, when on, covers writes only, and wrong passwords never lock it. */
        val writesOnly: Boolean get() = if (ulc) (u(12) and 0x01) == 1 else (u(4) and 0x87) == 0

        /** AUTH0 can't be changed any more: Ultralight C lock byte 3 bit 5, NTAG CFGLCK. */
        val auth0Locked: Boolean get() = if (ulc) (u(1) and 0x20) != 0 else (u(4) and 0x40) != 0

        /** Any of the protection settings is frozen (Ultralight C: also AUTH1 or the key). */
        val settingsLocked: Boolean get() = if (ulc) (u(1) and 0xE0) != 0 else auth0Locked

        fun info(writable: Boolean) = TagInfo(chip, !writable, protection, settingsLocked)
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

    private fun firstText(msg: NdefMessage?): String? =
        msg?.records?.firstOrNull {
            it.tnf == NdefRecord.TNF_WELL_KNOWN && it.type.contentEquals(NdefRecord.RTD_TEXT)
        }?.let { NdefText.decodePayload(it.payload) }
}
