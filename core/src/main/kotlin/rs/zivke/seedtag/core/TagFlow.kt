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
enum class Purpose { Check, BackupWrite, ClearRest, Verify, Restore, Lock, Inspect, Erase }

/** A scan waiting for a tag. [id] tells a result for the current scan from one that arrives after Cancel or a new scan. */
data class Pending(val request: TagRequest, val purpose: Purpose, val id: Long)

/** [working]: a tag was found and is being read or written, null while waiting for one. */
data class ScanPrompt(val title: String, val error: String? = null, val working: TagStep? = null)

/** A dialog the tag flow asks for. The app words it. */
sealed interface TagNotice {
    /** The tag holds data: overwrite it? [looksLikeSeedTag] if that's a SeedTag backup, of any format. */
    data class ConfirmOverwrite(val looksLikeSeedTag: Boolean) : TagNotice
    /** A blank tag was written, but the scan was canceled before it was read back. */
    data object WrittenUnchecked : TagNotice
    data object Locked : TagNotice
    data object AlreadyReadOnly : TagNotice
    data object Erased : TagNotice
}

/** What the app does besides taking the new [TagFlow]: the parts that live outside it. */
sealed interface TagEffect {
    data class Buzz(val haptic: Haptic) : TagEffect
    data class Notify(val notice: TagNotice) : TagEffect
    /** Decrypt [text] with the password typed (Argon2id, off the tag thread), then call [TagFlow.opened]. */
    data class Decrypt(val text: String, val reveal: Boolean, val differences: List<String>) : TagEffect
    /** The backup is on the tag: wipe the words and passwords, clear Verify and Restore, show Done. */
    data object BackupWritten : TagEffect
    /** The tag was erased: the Erase tab starts afresh (its password and decrypted check too). */
    data object EraseDone : TagEffect
}

/** A backup sealed and checked, waiting for its tag: [text] to write, [fingerprint] to show once written. */
data class Sealed(val text: String, val fingerprint: String)

/**
 * The tag flow: which scan waits for a tag, what earlier scans found, and what a tag's result does.
 * Immutable and free of Android, so every path can be tested on the JVM. Each call returns the
 * [Next] state and the [TagEffect]s for the app to carry out; nothing here holds a secret (tag texts
 * are Base64 blobs anyone can read from the tag, fingerprints aren't secret).
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

    private fun arm(request: TagRequest, purpose: Purpose, title: String, vararg effects: TagEffect) =
        copy(pending = Pending(request, purpose, scanId + 1), scanId = scanId + 1, prompt = ScanPrompt(title)).next(*effects)

    // ---- what the user starts ---------------------------------------------------

    fun scanToCheck() = copy(tagCheck = null).arm(TagRequest.Check, Purpose.Check, "Hold the tag you'll use to the back of the phone")

    /** "Check another tag" / "Change tag". */
    fun checkAnotherTag() = copy(tagCheck = null).next()

    /** [sealed] was encrypted and checked: write it to the next tag. */
    fun write(sealed: Sealed) = copy(toWrite = sealed)
        .arm(TagRequest.Write(sealed.text), Purpose.BackupWrite, "Hold the same tag to the back of the phone to write the backup")

    /** "Overwrite" in [TagNotice.ConfirmOverwrite]: only the tag it asked about. */
    fun overwrite(): Next {
        val text = toWrite?.text ?: return next()
        val target = overwriteTarget ?: return next()
        return arm(TagRequest.Write(text, target), Purpose.BackupWrite, "Hold the same tag again to overwrite it")
    }

    /** Verify, or Restore if [reveal]. */
    fun scanToRead(reveal: Boolean) =
        arm(TagRequest.Read, if (reveal) Purpose.Restore else Purpose.Verify, "Hold the tag to the back of the phone to read it")

    /** Lock [lockTarget]; nothing if there's none. */
    fun lock(): Next {
        val target = lockTarget ?: return next()
        return arm(TagRequest.Lock(target), Purpose.Lock, "Hold the same tag to make it permanently read-only")
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
        val target = eraseCheck?.target ?: return next()
        return arm(TagRequest.Erase(target), Purpose.Erase, "Hold the same tag to erase it")
    }

    /** Back to the start of Erase: nothing scanned, and its scan canceled. */
    fun resetErase(): Next {
        val erasing = pending?.purpose.let { it == Purpose.Inspect || it == Purpose.Erase }
        return (if (erasing) cancel().flow else this).copy(eraseCheck = null).next()
    }

    /** Back up's "Start over": nothing of the last backup kept. */
    fun startOver() = copy(
        tagCheck = null, toWrite = null, overwriteTarget = null, backupFingerprint = null, writtenText = null,
        backupRest = null, lockTarget = null,
    ).next()

    /** The app wiped its secrets (left the foreground, or idle): the scan ends, and so does the backup waiting for it. */
    fun wiped() = cancel().flow.copy(toWrite = null, overwriteTarget = null).next()

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
        fun tapAgain(error: String) = copy(
            pending = p, // stay armed, so the user can simply tap again
            prompt = prompt?.copy(error = error, working = null) ?: ScanPrompt("Hold the tag to the phone", error),
        ).next(buzz(Haptic.Failure))
        if (stale && r.onlyAsks) return next()
        return when (r) {
            is TagResult.Checked -> copy(prompt = null, tagCheck = r.check)
                .next(buzz(if (r.check.usable && !r.check.classic) Haptic.Success else Haptic.Failure))
            is TagResult.Failed -> tapAgain(r.message)
            is TagResult.NotEmpty -> copy(prompt = null, overwriteTarget = r.target).next(
                buzz(Haptic.Failure),
                notify(TagNotice.ConfirmOverwrite(SeedBox.kindOf(r.existing) != SeedBox.Kind.NotBackup)),
            )
            is TagResult.WrittenUnchecked ->
                // Canceled: don't bring the scan prompt back by itself.
                if (stale) closed.next(buzz(Haptic.Tick), notify(TagNotice.WrittenUnchecked))
                else arm(TagRequest.CheckWritten(r.text), Purpose.BackupWrite, "Written. Hold the tag to the phone once more to check it", buzz(Haptic.Tick))
            is TagResult.Written -> closed.copy(
                backupFingerprint = toWrite?.fingerprint, toWrite = null, overwriteTarget = null,
                lockTarget = r.text, writtenText = r.text, backupRest = r.rest,
            ).next(buzz(Haptic.Success), TagEffect.BackupWritten)
            is TagResult.RestCleared ->
                if (r.rest == Rest.Interrupted) tapAgain(MOVED_AWAY)
                else closed.copy(backupRest = r.rest).next(buzz(Haptic.Success))
            TagResult.Locked -> closed.next(buzz(Haptic.Success), notify(TagNotice.Locked))
            TagResult.AlreadyReadOnly -> copy(prompt = null).next(notify(TagNotice.AlreadyReadOnly))
            is TagResult.Inspected -> copy(prompt = null, eraseCheck = r.check).next()
            TagResult.Erased -> {
                // Lock must not be offered for a tag that no longer holds that backup.
                val erasedText = eraseCheck?.text
                val lock = if (erasedText != null && erasedText == lockTarget) null else lockTarget
                if (stale) closed.copy(lockTarget = lock).next(buzz(Haptic.Success), notify(TagNotice.Erased))
                else closed.copy(lockTarget = lock, eraseCheck = null)
                    .next(buzz(Haptic.Success), TagEffect.EraseDone, notify(TagNotice.Erased))
            }
            is TagResult.Read -> copy(prompt = null).next(TagEffect.Decrypt(r.text, p.purpose == Purpose.Restore, r.differences))
        }
    }

    private companion object {
        const val MOVED_AWAY = "The tag moved away too early. Hold it still and try again."

        /** Results that only ask for more input: dropped when they arrive for a canceled or older scan. */
        val TagResult.onlyAsks: Boolean
            get() = when (this) {
                is TagResult.Checked, is TagResult.Failed, is TagResult.NotEmpty, TagResult.AlreadyReadOnly,
                is TagResult.Inspected, is TagResult.Read -> true
                is TagResult.RestCleared -> rest == Rest.Interrupted
                is TagResult.WrittenUnchecked, is TagResult.Written, TagResult.Locked, TagResult.Erased -> false
            }
    }
}
