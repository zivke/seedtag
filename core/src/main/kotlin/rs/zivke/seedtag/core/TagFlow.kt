// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

/**
 * Vibration for things that happen without a tap: a tag was handled, something succeeded or failed.
 * [Found]: the NFC-spot finder saw a tag; a longer buzz, so it's felt through a hand on the phone.
 * [Detected]: a scan's tag was found and the app is working with it; one short, firm pulse.
 */
enum class Haptic { Tick, Success, Failure, Found, Detected }

/** What a scan is for: decides how its result is handled. */
enum class Purpose {
    Check, BackupWrite, ClearRest, Verify, Restore, Lock, Inspect, Erase, Protect, Unprotect,
    /** The same backup, already on a tag, written to another one ([TagFlow.copyTo]). */
    Copy,
    /** [ClearRest] on a copy: Done's old-data state is about the first tag. */
    CopyClearRest,
}

/**
 * A scan waiting for a tag. [id] tells a result for the current scan from one that arrives after Cancel
 * or a new scan. [fingerprint]: the backup a write scan writes, carried with it, so a write that ends
 * after the app wiped what was waiting still shows it.
 */
data class Pending(val request: TagRequest, val purpose: Purpose, val id: Long, val fingerprint: String? = null)

/** [working]: a tag was found and is being read or written, null while waiting for one. */
data class ScanPrompt(val title: String, val error: String? = null, val working: TagStep? = null)

/** A dialog the tag flow asks for. The app words it. */
sealed interface TagNotice {
    /** The tag holds data: overwrite it? [looksLikeSeedTag] if that's a SeedTag backup, of any format. */
    data class ConfirmOverwrite(val looksLikeSeedTag: Boolean) : TagNotice
    /** A blank tag was written, but the scan was canceled before it was read back. */
    data object WrittenUnchecked : TagNotice
    /** [checked]: as [TagResult.Locked]. */
    data class Locked(val checked: Boolean) : TagNotice
    /** A scan that was canceled, or the app left, ended in a failure that may have changed the tag. */
    data class LateFailure(val message: String) : TagNotice
    /** [checked]: as [TagResult.AlreadyReadOnly]. */
    data class AlreadyReadOnly(val checked: Boolean) : TagNotice
    data object CantMakeReadOnly : TagNotice
    /** [rest]: whether the data area was then checked page by page. */
    data class Erased(val rest: Rest) : TagNotice
    /** Protect found a chip that isn't NXP's: warn, and go on only if the user says so ([TagFlow.protect]). */
    data class ConfirmOtherMaker(val chip: NtagChip) : TagNotice
    /** The write password is on, and a fresh tap showed reads work and writes are refused. */
    data object Protected : TagNotice
    /** A copy of the backup [text] was written and read back; [rest] as for a backup ([TagResult.Written]). */
    data class CopyWritten(val text: String, val rest: Rest) : TagNotice
    /** The chip accepted a write without the password. [switchedOff]: SeedTag switched it off again. */
    data class NotEnforced(val switchedOff: Boolean) : TagNotice
    /** Protect was canceled, or the app left, after the password was switched on but before it was checked. */
    data object ProtectUnchecked : TagNotice
    data object Unprotected : TagNotice
}

/** What the app does besides taking the new [TagFlow]: the parts that live outside it. */
sealed interface TagEffect {
    data class Buzz(val haptic: Haptic) : TagEffect
    data class Notify(val notice: TagNotice) : TagEffect
    /**
     * Decrypt [text] with the password typed (Argon2id, off the tag thread), then call [TagFlow.opened].
     * [info]: the chip and its write protection, for Verify (null if not asked for or not known).
     */
    data class Decrypt(val text: String, val reveal: Boolean, val differences: List<String>, val info: TagInfo? = null) : TagEffect
    /** The tag that holds [text] now has write protection [protection]: what Verify shows about it. */
    data class ProtectionChanged(val text: String, val protection: Protection) : TagEffect
    /** The backup is on the tag: wipe the words and passwords, clear Verify and Restore, show Done. */
    data object BackupWritten : TagEffect
    /** The tag the Erase tab scanned was erased: it starts afresh (its password and decrypted check too). */
    data object EraseDone : TagEffect
    /**
     * The tag that held [text] was erased: a Verify or Restore result about it is out of date. If
     * [wasBackup], it held the backup written in this session, so Back up starts afresh too.
     */
    data class Emptied(val text: String, val wasBackup: Boolean) : TagEffect
}

/** A backup sealed and checked, waiting for its tag: [text] to write, [fingerprint] to show once written. */
data class Sealed(val text: String, val fingerprint: String)

/**
 * The tag flow: which scan waits for a tag, what earlier scans found, and what a tag's result does.
 * Immutable and free of Android, so every path can be tested on the JVM. Each call returns the
 * [Next] state and the [TagEffect]s for the app to carry out; nothing here holds a secret (tag texts
 * are Base64 blobs anyone can read from the tag, fingerprints aren't secret), except that a Protect or
 * Unprotect scan refers to the tag's write-password secret, which the view model owns and zeroes.
 *
 * Threading, in the app: [pending] mirrors the atomic slot the NFC thread takes a scan from. That
 * thread posts [claimed] before any I/O, then [working] for each step and [handle] last, in order,
 * to the main thread, where every call is made.
 */
data class TagFlow(
    /** The scan waiting for a tag, null if none (or the NFC thread has taken it). */
    val pending: Pending? = null,
    /** Bumped by every arm and cancel: a result from an older scan is stale. */
    val scanId: Long = 0,
    val prompt: ScanPrompt? = null,
    /** What Back up's first step found out about the tag. */
    val tagCheck: TagCheck? = null,
    /** The backup waiting to be written. */
    val toWrite: Sealed? = null,
    /** [toWrite] is a copy of a backup already on a tag ([copyTo]), not a new one. */
    val copying: Boolean = false,
    /** The non-empty tag the overwrite question was about: Overwrite applies to it alone. */
    val overwriteTarget: OverwriteTarget? = null,
    /** Fingerprint of the backup last written and read back. */
    val backupFingerprint: String? = null,
    /** The text of that backup, for Done's "Clear old data". */
    val writtenText: String? = null,
    /** What happened to the old data after it (shown on Done). */
    val backupRest: Rest? = null,
    /** Tag text last written or decrypted: the only tag Lock will touch. */
    val lockTarget: String? = null,
    /** What the Erase scan found. */
    val eraseCheck: EraseCheck? = null,
) {
    data class Next(val flow: TagFlow, val effects: List<TagEffect> = emptyList())

    private fun next(vararg effects: TagEffect) = Next(this, effects.toList())

    private fun arm(request: TagRequest, purpose: Purpose, title: String, vararg effects: TagEffect, fingerprint: String? = null) =
        copy(pending = Pending(request, purpose, scanId + 1, fingerprint), scanId = scanId + 1, prompt = ScanPrompt(title))
            .next(*effects)

    // ---- what the user starts ---------------------------------------------------

    fun scanToCheck() = copy(tagCheck = null).arm(TagRequest.Check, Purpose.Check, "Hold the tag you'll use to the back of the phone")

    /** "Check another tag" / "Change tag". */
    fun checkAnotherTag() = copy(tagCheck = null).next()

    /** [sealed] was encrypted and checked: write it to the next tag. */
    fun write(sealed: Sealed) = copy(toWrite = sealed, copying = false).arm(
        TagRequest.Write(sealed.text), Purpose.BackupWrite, "Hold the same tag to the back of the phone to write the backup",
        fingerprint = sealed.fingerprint,
    )

    /**
     * Board B7: write the backup [sealed], already on a tag (just written, or verified), to another tag as well:
     * the same text, so the same salt and nonce, the same password. Nothing secret is needed for it. Done and
     * Verify stay as they are.
     */
    fun copyTo(sealed: Sealed) = copy(toWrite = sealed, copying = true, overwriteTarget = null).arm(
        TagRequest.Write(sealed.text), Purpose.Copy, "Hold the other tag to the back of the phone to write the copy",
        fingerprint = sealed.fingerprint,
    )

    /** "Overwrite" in [TagNotice.ConfirmOverwrite]: only the tag it asked about. */
    fun overwrite(): Next {
        val sealed = toWrite ?: return next()
        val target = overwriteTarget ?: return next()
        return arm(
            TagRequest.Write(sealed.text, target), if (copying) Purpose.Copy else Purpose.BackupWrite,
            "Hold the same tag again to overwrite it", fingerprint = sealed.fingerprint,
        )
    }

    /** Clear the old data after the backup on a copy ([TagNotice.CopyWritten] with [Rest.Interrupted]). */
    fun clearCopyRest(text: String) =
        arm(TagRequest.ClearRest(text), Purpose.CopyClearRest, "Hold the copy to clear the old data after the backup")

    /** Verify, or Restore if [reveal]. Verify also looks at the chip and its write protection. */
    fun scanToRead(reveal: Boolean) = arm(
        TagRequest.Read(withInfo = !reveal), if (reveal) Purpose.Restore else Purpose.Verify,
        "Hold the tag to the back of the phone to read it",
    )

    /**
     * Give the tag that holds [lockTarget] (just verified) its write password, from [secret]; nothing if
     * there's no such tag. [otherMaker]: the user read the warning about a chip that isn't NXP's.
     */
    fun protect(secret: ByteArray, otherMaker: Boolean = false): Next {
        val target = lockTarget ?: return next()
        return arm(
            TagRequest.Protect(target, secret, ProtectStage.Store, otherMaker), Purpose.Protect,
            "Hold the same tag to the phone to store its write password (step 1 of 3)",
        )
    }

    /** Switch the write password of the tag that holds [lockTarget] off again. */
    fun unprotect(secret: ByteArray): Next {
        val target = lockTarget ?: return next()
        return arm(TagRequest.Unprotect(target, secret), Purpose.Unprotect, "Hold the same tag to remove its write password")
    }

    /** Lock [lockTarget]; nothing if there's none. [lookalike]: as [TagRequest.Lock]. */
    fun lock(lookalike: Boolean = false): Next {
        val target = lockTarget ?: return next()
        return arm(TagRequest.Lock(target, lookalike), Purpose.Lock, "Hold the same tag to make it permanently read-only")
    }

    /** Done's "Clear old data", after [Rest.Interrupted]: only the tag that holds the backup just written. */
    fun clearRest(): Next {
        val text = writtenText ?: return next()
        return arm(TagRequest.ClearRest(text), Purpose.ClearRest, "Hold the same tag to clear the old data after the backup")
    }

    fun cancel() = copy(scanId = scanId + 1, pending = null, prompt = null).next()

    /** Erase, first tap: see what the tag holds. Nothing is written. */
    fun scanToErase() = resetErase().flow.arm(TagRequest.Inspect, Purpose.Inspect, "Hold the tag to the phone to see what it holds")

    /** Erase the tag [eraseCheck] found; nothing if it can't be erased. */
    fun erase(): Next {
        val check = eraseCheck ?: return next()
        val target = check.target ?: return next()
        return arm(TagRequest.Erase(target, check.text), Purpose.Erase, "Hold the same tag to erase it")
    }

    /** Back to the start of Erase: nothing scanned, and its scan canceled. */
    fun resetErase(): Next {
        val erasing = pending?.purpose.let { it == Purpose.Inspect || it == Purpose.Erase }
        return (if (erasing) cancel().flow else this).copy(eraseCheck = null).next()
    }

    /** Back up's "Start over": nothing of the last backup kept. */
    fun startOver() = copy(
        tagCheck = null, toWrite = null, copying = false, overwriteTarget = null, backupFingerprint = null, writtenText = null,
        backupRest = null, lockTarget = null,
    ).next()

    /**
     * The app wiped its secrets (left the foreground, or idle): the scan ends, and so do the backup
     * waiting for it and the Erase tab's scan (nobody picking up the phone later can erase from it).
     */
    fun wiped() = cancel().flow.copy(toWrite = null, copying = false, overwriteTarget = null, eraseCheck = null).next()

    /** The tag's [text] was decrypted (Verify, Restore): Lock now applies to it. */
    fun opened(text: String) = copy(lockTarget = text).next()

    // ---- what the NFC thread reports ---------------------------------------------

    /** The NFC thread took [p] for a tag. */
    fun claimed(p: Pending) = (if (pending?.id == p.id) copy(pending = null) else this).next()

    /** The scan [p]'s tag is being worked with: say so at once (the first step comes before any I/O). */
    fun working(p: Pending, step: TagStep): Next {
        if (p.id != scanId) return next()
        val prompt = prompt ?: return next()
        val found = if (prompt.working == null) arrayOf<TagEffect>(TagEffect.Buzz(Haptic.Detected)) else emptyArray()
        return copy(prompt = prompt.copy(working = step, error = null)).next(*found)
    }

    /**
     * The result [r] of the scan [p]. Canceled, left the app or started another scan while the tag
     * was busy: results that only ask for more input are dropped. Writes, locks and erases did
     * happen, so those are still taken, but without closing the prompt of a newer scan that's
     * waiting for a tag.
     */
    fun handle(p: Pending, r: TagResult): Next {
        val stale = p.id != scanId
        val closed = if (!stale || pending == null) copy(prompt = null) else this
        fun buzz(h: Haptic) = TagEffect.Buzz(h)
        fun notify(n: TagNotice) = TagEffect.Notify(n)
        fun tapAgain(error: String, retry: TagRequest? = null) = copy(
            pending = if (retry == null) p else p.copy(request = retry), // stay armed: the user can simply tap again
            prompt = prompt?.copy(error = error, working = null) ?: ScanPrompt("Hold the tag to the phone", error),
        ).next(buzz(Haptic.Failure))
        if (stale && r.onlyAsks) return next()
        return when (r) {
            is TagResult.Checked -> copy(prompt = null, tagCheck = r.check)
                .next(buzz(if (r.check.usable && !r.check.classic) Haptic.Success else Haptic.Failure))
            is TagResult.Failed ->
                // A late failure that may have changed the tag is said, but doesn't arm a scan nobody waits for.
                if (stale) closed.next(buzz(Haptic.Failure), notify(TagNotice.LateFailure(r.message)))
                else tapAgain(r.message, r.retry)
            // A copy held to a tag with this very backup: the original, or a copy already made.
            is TagResult.NotEmpty -> if (p.purpose == Purpose.Copy && r.existing != null && r.existing == (p.request as? TagRequest.Write)?.text) {
                tapAgain(SAME_BACKUP)
            } else copy(prompt = null, overwriteTarget = r.target).next(
                buzz(Haptic.Failure),
                notify(TagNotice.ConfirmOverwrite(SeedBox.kindOf(r.existing) != SeedBox.Kind.NotBackup)),
            )
            is TagResult.WrittenUnchecked ->
                // Canceled: don't bring the scan prompt back by itself.
                if (stale) closed.next(buzz(Haptic.Tick), notify(TagNotice.WrittenUnchecked))
                else arm(
                    TagRequest.CheckWritten(r.text), if (p.purpose == Purpose.Copy) Purpose.Copy else Purpose.BackupWrite,
                    "Written. Hold the tag to the phone once more to check it",
                    buzz(Haptic.Tick), fingerprint = p.fingerprint,
                )
            // A copy: Done and Verify are about the first tag and stay; the copy's own old data is in the notice.
            is TagResult.Written -> if (p.purpose == Purpose.Copy) {
                closed.copy(toWrite = null, copying = false, overwriteTarget = null)
                    .next(buzz(Haptic.Success), notify(TagNotice.CopyWritten(r.text, r.rest)))
            } else closed.copy(
                // The fingerprint comes with the scan: the app may have wiped what was waiting meanwhile.
                backupFingerprint = p.fingerprint, toWrite = null, overwriteTarget = null,
                lockTarget = r.text, writtenText = r.text, backupRest = r.rest,
            ).next(buzz(Haptic.Success), TagEffect.BackupWritten)
            is TagResult.RestCleared ->
                if (r.rest == Rest.Interrupted) tapAgain(MOVED_AWAY)
                else if (p.purpose == Purpose.CopyClearRest) closed.next(buzz(Haptic.Success))
                else closed.copy(backupRest = r.rest).next(buzz(Haptic.Success))
            is TagResult.Locked -> closed.next(buzz(Haptic.Success), notify(TagNotice.Locked(r.checked)))
            is TagResult.AlreadyReadOnly -> copy(prompt = null).next(notify(TagNotice.AlreadyReadOnly(r.checked)))
            TagResult.CantMakeReadOnly -> copy(prompt = null).next(buzz(Haptic.Failure), notify(TagNotice.CantMakeReadOnly))
            is TagResult.Inspected -> copy(prompt = null, eraseCheck = r.check).next()
            is TagResult.Erased -> {
                // Taken whether or not the scan is current: the tag is empty either way.
                val request = p.request as? TagRequest.Erase
                val text = request?.text
                val effects = mutableListOf<TagEffect>(buzz(Haptic.Success))
                var f = closed
                // The Erase tab's scan of this tag is done with; a newer scan of another is kept.
                if (request != null && eraseCheck?.target === request.target) {
                    f = f.copy(eraseCheck = null)
                    effects += TagEffect.EraseDone
                }
                if (text != null) {
                    // Nothing may describe the erased backup any more: Lock, Done, Verify.
                    val wasBackup = text == writtenText
                    if (text == lockTarget) f = f.copy(lockTarget = null)
                    if (wasBackup) f = f.copy(writtenText = null, backupRest = null, backupFingerprint = null, tagCheck = null)
                    effects += TagEffect.Emptied(text, wasBackup)
                }
                effects += notify(TagNotice.Erased(r.rest))
                Next(f, effects)
            }
            is TagResult.Read -> copy(prompt = null).next(TagEffect.Decrypt(r.text, p.purpose == Purpose.Restore, r.differences, r.info))
            is TagResult.OtherMaker -> closed.next(buzz(Haptic.Failure), notify(TagNotice.ConfirmOtherMaker(r.chip)))
            // Each step in a new session: take the tag away and hold it again.
            TagResult.ProtectStored -> (p.request as? TagRequest.Protect)?.let { req ->
                arm(
                    req.next(ProtectStage.SwitchOn), Purpose.Protect,
                    "Stored. Take the tag away, then hold it to the phone again to switch the password on (step 2 of 3)",
                    buzz(Haptic.Tick),
                )
            } ?: closed.next()
            TagResult.ProtectSwitchedOn ->
                if (stale) closed.next(buzz(Haptic.Tick), notify(TagNotice.ProtectUnchecked))
                else (p.request as? TagRequest.Protect)?.let { req ->
                    arm(
                        req.next(ProtectStage.Confirm), Purpose.Protect,
                        "Switched on. Take the tag away, then hold it once more to check it (step 3 of 3)",
                        buzz(Haptic.Tick),
                    )
                } ?: closed.next()
            TagResult.Protected -> closed.next(
                buzz(Haptic.Success), notify(TagNotice.Protected), *protectionOf(p, Protection.Write),
            )
            is TagResult.NotEnforced -> closed.next(
                buzz(Haptic.Failure), notify(TagNotice.NotEnforced(r.switchedOff)),
                *protectionOf(p, if (r.switchedOff) Protection.Off else Protection.Write),
            )
            TagResult.Unprotected -> closed.next(
                buzz(Haptic.Success), notify(TagNotice.Unprotected), *protectionOf(p, Protection.Off),
            )
        }
    }

    private fun protectionOf(p: Pending, protection: Protection): Array<TagEffect> {
        val text = when (val r = p.request) {
            is TagRequest.Protect -> r.expected
            is TagRequest.Unprotect -> r.expected
            else -> return emptyArray()
        }
        return arrayOf(TagEffect.ProtectionChanged(text, protection))
    }

    private companion object {
        const val MOVED_AWAY = "The tag moved away too early. Hold it still and try again."

        /** A copy held to a tag that holds this backup already. */
        const val SAME_BACKUP =
            "This tag already holds this backup: it's the one you wrote or verified, or a copy. Hold the other tag to the phone."

        /** Results that only ask for more input: dropped when they arrive for a canceled or older scan. */
        val TagResult.onlyAsks: Boolean
            get() = when (this) {
                is TagResult.Checked, is TagResult.NotEmpty, is TagResult.AlreadyReadOnly, TagResult.CantMakeReadOnly,
                is TagResult.Inspected, is TagResult.Read, is TagResult.OtherMaker -> true
                // Only stored: protection is still off, so a canceled Protect leaves nothing to say.
                TagResult.ProtectStored -> true
                TagResult.ProtectSwitchedOn, TagResult.Protected, is TagResult.NotEnforced, TagResult.Unprotected -> false
                is TagResult.Failed -> !changedTag
                is TagResult.RestCleared -> rest == Rest.Interrupted
                is TagResult.WrittenUnchecked, is TagResult.Written, is TagResult.Locked, is TagResult.Erased -> false
            }
    }
}
