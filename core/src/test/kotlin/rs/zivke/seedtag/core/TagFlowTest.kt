// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tag flow, driven as the app drives it: the user starts a scan, the NFC thread claims it,
 * reports steps and a result. No tag, no Android: [tap] stands in for a tag touching the phone.
 */
class TagFlowTest {

    /** The 24-word vector's text: a real v1 backup (of a throwaway seed). */
    private val text = javaClass.getResource("/seedtag-v1-vectors.tsv")!!.readText().lines().first().split("\t")[4]
    private val sealed = Sealed(text, "297a46bd")
    private val target = OverwriteTarget(byteArrayOf(4, 1, 2, 3, 4, 5, 6), byteArrayOf(1, 2, 3))
    private val usable = TagCheck(supported = true, ndef = true, capacity = 137)
    private val reading = TagStep("Reading the tag")

    /** What the effects were, by kind, to compare in one line. */
    private val TagFlow.Next.buzzes get() = effects.filterIsInstance<TagEffect.Buzz>().map { it.haptic }
    private val TagFlow.Next.notices get() = effects.filterIsInstance<TagEffect.Notify>().map { it.notice }

    /** A tag touches the phone: the NFC thread claims the pending scan, reports its first step, then [result]. */
    private fun TagFlow.Next.tap(result: TagResult): TagFlow.Next {
        val p = checkNotNull(flow.pending) { "no scan pending" }
        val working = flow.claimed(p).flow.working(p, reading)
        val done = working.flow.handle(p, result)
        return TagFlow.Next(done.flow, working.effects + done.effects)
    }

    /** The scan the NFC thread is busy with, for results that arrive after something else happened. */
    private fun TagFlow.Next.claim(): Pair<Pending, TagFlow> {
        val p = flow.pending!!
        return p to flow.claimed(p).flow.working(p, reading).flow
    }

    @Test
    fun checkFindsAUsableTag() {
        val armed = TagFlow().scanToCheck()
        assertEquals(TagRequest.Check, armed.flow.pending!!.request)
        assertEquals("Hold the tag you'll use to the back of the phone", armed.flow.prompt!!.title)
        val done = armed.tap(TagResult.Checked(usable))
        assertEquals(listOf(Haptic.Detected, Haptic.Success), done.buzzes)
        assertEquals(usable, done.flow.tagCheck)
        assertNull(done.flow.pending)
        assertNull(done.flow.prompt)
        // A Classic tag, or one too small, buzzes as a failure.
        assertEquals(Haptic.Failure, armed.tap(TagResult.Checked(usable.copy(classic = true))).buzzes.last())
        assertEquals(Haptic.Failure, armed.tap(TagResult.Checked(usable.copy(capacity = 40))).buzzes.last())
    }

    @Test
    fun aFailedTapStaysArmed() {
        val armed = TagFlow().scanToCheck()
        val failed = armed.tap(TagResult.Failed("The tag moved away too early."))
        assertEquals(armed.flow.pending, failed.flow.pending)
        assertEquals("The tag moved away too early.", failed.flow.prompt!!.error)
        assertNull(failed.flow.prompt!!.working)
        assertEquals(Haptic.Failure, failed.buzzes.last())
        // The next tap goes through, and its step clears the error.
        val p = failed.flow.pending!!
        assertNull(failed.flow.claimed(p).flow.working(p, reading).flow.prompt!!.error)
        assertEquals(usable, failed.tap(TagResult.Checked(usable)).flow.tagCheck)
    }

    @Test
    fun resultsThatOnlyAskAreDroppedOnceStale() {
        val (p, busy) = TagFlow().scanToCheck().claim()
        val canceled = busy.cancel().flow
        for (r in listOf(
            TagResult.Checked(usable), TagResult.Failed("x"), TagResult.NotEmpty(null, target), TagResult.AlreadyReadOnly,
            TagResult.CantMakeReadOnly,
            TagResult.Inspected(EraseCheck(true, true, true, TagContent.Other, 137, null, target)), TagResult.Read(text),
            TagResult.RestCleared(Rest.Interrupted),
        )) {
            val next = canceled.handle(p, r)
            assertSame(r.toString(), canceled, next.flow)
            assertEquals(r.toString(), emptyList<TagEffect>(), next.effects)
        }
        // Steps of a canceled scan don't bring its prompt back.
        assertSame(canceled, canceled.working(p, reading).flow)
    }

    @Test
    fun writeAndReadBack() {
        val armed = TagFlow().scanToCheck().tap(TagResult.Checked(usable)).flow.write(sealed)
        assertEquals(TagRequest.Write(text), armed.flow.pending!!.request)
        val done = armed.tap(TagResult.Written(text, Rest.Cleared))
        assertEquals(listOf(Haptic.Detected, Haptic.Success), done.buzzes)
        assertTrue(TagEffect.BackupWritten in done.effects)
        with(done.flow) {
            assertEquals("297a46bd", backupFingerprint)
            assertEquals(text, writtenText)
            assertEquals(text, lockTarget)
            assertEquals(Rest.Cleared, backupRest)
            assertNull(toWrite)
            assertNull(prompt)
            assertEquals(usable, tagCheck)
        }
    }

    @Test
    fun overwriteOnlyTheTagAskedAbout() {
        val asked = TagFlow().write(sealed).tap(TagResult.NotEmpty(text, target))
        assertEquals(listOf(TagNotice.ConfirmOverwrite(looksLikeSeedTag = true)), asked.notices)
        assertNull(asked.flow.prompt)
        assertEquals(
            listOf(TagNotice.ConfirmOverwrite(looksLikeSeedTag = false)),
            TagFlow().write(sealed).tap(TagResult.NotEmpty("https://example.com", target)).notices,
        )
        val again = asked.flow.overwrite()
        assertEquals(TagRequest.Write(text, target), again.flow.pending!!.request)
        assertEquals("Hold the same tag again to overwrite it", again.flow.prompt!!.title)
        // Nothing to overwrite with, or no tag asked about: nothing happens.
        val nothing = asked.flow.copy(toWrite = null)
        assertSame(nothing, nothing.overwrite().flow)
        assertNull(TagFlow().write(sealed).flow.copy(pending = null).overwrite().flow.pending)
    }

    @Test
    fun aBlankTagIsReadBackOnASecondTap() {
        val first = TagFlow().write(sealed).tap(TagResult.WrittenUnchecked(text))
        assertEquals(Haptic.Tick, first.buzzes.last())
        assertEquals(TagRequest.CheckWritten(text), first.flow.pending!!.request)
        assertEquals("Written. Hold the tag to the phone once more to check it", first.flow.prompt!!.title)
        assertEquals("297a46bd", first.tap(TagResult.Written(text, Rest.Cleared)).flow.backupFingerprint)
    }

    @Test
    fun writesThatHappenedAreTakenEvenAfterCancel() {
        // Canceled while the blank tag was written: say so, and don't arm the read-back.
        val (p, busy) = TagFlow().write(sealed).claim()
        val late = busy.cancel().flow.handle(p, TagResult.WrittenUnchecked(text))
        assertEquals(listOf(TagNotice.WrittenUnchecked), late.notices)
        assertNull(late.flow.pending)
        // A backup written after Cancel is still the backup, but a newer scan's prompt stays open.
        val (q, busy2) = TagFlow().write(sealed).claim()
        val newer = busy2.cancel().flow.scanToRead(reveal = false).flow
        val written = newer.handle(q, TagResult.Written(text, Rest.Cleared))
        assertEquals("297a46bd", written.flow.backupFingerprint)
        assertEquals(newer.prompt, written.flow.prompt)
        assertEquals(newer.pending, written.flow.pending)
        assertTrue(TagEffect.BackupWritten in written.effects)
    }

    @Test
    fun clearingTheRest() {
        val done = TagFlow().write(sealed).tap(TagResult.Written(text, Rest.Interrupted)).flow
        val armed = done.clearRest()
        assertEquals(TagRequest.ClearRest(text), armed.flow.pending!!.request)
        val moved = armed.tap(TagResult.RestCleared(Rest.Interrupted))
        assertEquals(armed.flow.pending, moved.flow.pending)
        assertEquals("The tag moved away too early. Hold it still and try again.", moved.flow.prompt!!.error)
        assertEquals(Rest.Interrupted, moved.flow.backupRest)
        val cleared = moved.tap(TagResult.RestCleared(Rest.Cleared))
        assertEquals(Rest.Cleared, cleared.flow.backupRest)
        assertNull(cleared.flow.prompt)
        // Nothing written in this session: nothing to clear.
        assertNull(TagFlow().clearRest().flow.pending)
    }

    @Test
    fun verifyAndRestoreDecryptWhatTheyRead() {
        val verify = TagFlow().scanToRead(reveal = false)
        assertEquals(Purpose.Verify, verify.flow.pending!!.purpose)
        val read = verify.tap(TagResult.Read(text, listOf("a language code other than en")))
        assertEquals(listOf(TagEffect.Decrypt(text, false, listOf("a language code other than en"))), read.effects.drop(1))
        assertNull(read.flow.prompt)
        assertNull(read.flow.lockTarget) // only once it's decrypted
        val restore = TagFlow().scanToRead(reveal = true)
        assertEquals(Purpose.Restore, restore.flow.pending!!.purpose)
        assertEquals(TagEffect.Decrypt(text, true, emptyList()), restore.tap(TagResult.Read(text)).effects.last())
    }

    @Test
    fun lockOnlyTheTagLastWrittenOrDecrypted() {
        assertNull(TagFlow().lock().flow.pending)
        val opened = TagFlow().opened(text).flow
        val armed = opened.lock()
        assertEquals(TagRequest.Lock(text), armed.flow.pending!!.request)
        val locked = armed.tap(TagResult.Locked)
        assertEquals(listOf(TagNotice.Locked), locked.notices)
        assertEquals(Haptic.Success, locked.buzzes.last())
        assertEquals(listOf(TagNotice.AlreadyReadOnly), opened.lock().tap(TagResult.AlreadyReadOnly).notices)
        // A tag this phone can't make read-only: said once, and the scan ends (holding it again wouldn't help).
        val cant = opened.lock().tap(TagResult.CantMakeReadOnly)
        assertEquals(listOf(TagNotice.CantMakeReadOnly), cant.notices)
        assertNull(cant.flow.pending)
        assertNull(cant.flow.prompt)
        assertEquals(text, cant.flow.lockTarget)
    }

    @Test
    fun eraseTheTagScanned() {
        val check = EraseCheck(true, ndef = true, writable = true, TagContent.Backup, 137, text, target)
        val armed = TagFlow().opened(text).flow.scanToErase()
        assertEquals(TagRequest.Inspect, armed.flow.pending!!.request)
        val inspected = armed.tap(TagResult.Inspected(check))
        assertEquals(check, inspected.flow.eraseCheck)
        val erasing = inspected.flow.erase()
        assertEquals(TagRequest.Erase(target, text), erasing.flow.pending!!.request)
        val erased = erasing.tap(TagResult.Erased(Rest.Cleared))
        assertEquals(
            listOf(
                TagEffect.Buzz(Haptic.Success), TagEffect.EraseDone, TagEffect.Emptied(text, wasBackup = false),
                TagEffect.Notify(TagNotice.Erased(Rest.Cleared)),
            ),
            erased.effects.drop(1),
        )
        assertNull(erased.flow.eraseCheck)
        assertNull(erased.flow.lockTarget) // it held the backup Lock was for
        // Another tag's text keeps Lock's target; a tag that can't be erased offers nothing.
        val other = TagFlow().opened("other").flow.scanToErase().tap(TagResult.Inspected(check)).flow.erase()
            .tap(TagResult.Erased(Rest.NotPossible))
        assertEquals("other", other.flow.lockTarget)
        assertNull(TagFlow().scanToErase().tap(TagResult.Inspected(check.copy(target = null))).flow.erase().flow.pending)
    }

    @Test
    fun resetEraseCancelsOnlyAnEraseScan() {
        val inspecting = TagFlow().scanToErase().flow
        assertNull(inspecting.resetErase().flow.pending)
        assertNull(inspecting.resetErase().flow.prompt)
        val reading = TagFlow().scanToRead(reveal = false).flow
        assertEquals(reading.pending, reading.resetErase().flow.pending)
    }

    @Test
    fun theClaimOfAnOlderScanLeavesTheNewOne() {
        val first = TagFlow().scanToCheck().flow
        val p = first.pending!!
        val second = first.cancel().flow.scanToRead(reveal = false).flow
        assertEquals(second.pending, second.claimed(p).flow.pending)
        assertNull(first.claimed(p).flow.pending)
    }

    @Test
    fun detectedBuzzesOncePerTap() {
        val armed = TagFlow().scanToCheck().flow
        val p = armed.pending!!
        val one = armed.claimed(p).flow.working(p, reading)
        assertEquals(listOf(TagEffect.Buzz(Haptic.Detected)), one.effects)
        val two = one.flow.working(p, TagStep("Writing", 2, 4, writes = true))
        assertEquals(emptyList<TagEffect>(), two.effects)
        assertEquals(TagStep("Writing", 2, 4, writes = true), two.flow.prompt!!.working)
    }

    @Test
    fun wipeAndStartOver() {
        val waiting = TagFlow().scanToCheck().tap(TagResult.Checked(usable)).flow.write(sealed).tap(TagResult.NotEmpty(text, target)).flow
        val wiped = waiting.wiped().flow
        assertNull(wiped.toWrite)
        assertNull(wiped.overwriteTarget)
        assertNull(wiped.pending)
        assertEquals(usable, wiped.tagCheck) // nothing secret: kept
        val done = TagFlow().write(sealed).tap(TagResult.Written(text, Rest.Cleared)).flow
        val fresh = done.startOver().flow
        assertEquals(TagFlow(scanId = fresh.scanId), fresh)
    }

    // ---- B15: results that arrive after Cancel, a new scan, or the app's wipe ----------------

    private val check = EraseCheck(true, ndef = true, writable = true, TagContent.Backup, 137, text, target)

    @Test
    fun theFingerprintTravelsWithTheWrite() {
        // (a) The power button mid-write wipes what was waiting; the late result still knows its backup.
        val (p, busy) = TagFlow().write(sealed).claim()
        assertEquals("297a46bd", busy.wiped().flow.handle(p, TagResult.Written(text, Rest.Cleared)).flow.backupFingerprint)
        // The same through a blank tag's second tap, and through Overwrite.
        val second = TagFlow().write(sealed).tap(TagResult.WrittenUnchecked(text))
        val (q, busy2) = second.claim()
        assertEquals("297a46bd", busy2.wiped().flow.handle(q, TagResult.Written(text, Rest.Cleared)).flow.backupFingerprint)
        val again = TagFlow().write(sealed).tap(TagResult.NotEmpty(text, target)).flow.overwrite()
        assertEquals("297a46bd", again.flow.pending!!.fingerprint)
    }

    @Test
    fun aLateFailureThatMayHaveChangedTheTagIsShown() {
        // (b) Said, with a failure buzz, but no scan armed for it, and a newer scan's prompt left alone.
        val (p, busy) = TagFlow().scanToErase().tap(TagResult.Inspected(check)).flow.erase().claim()
        val newer = busy.cancel().flow.scanToRead(reveal = false).flow
        val late = newer.handle(p, TagResult.Failed("part of it may already be gone", changedTag = true))
        assertEquals(listOf(TagNotice.LateFailure("part of it may already be gone")), late.notices)
        assertEquals(listOf(Haptic.Failure), late.buzzes)
        assertEquals(newer.pending, late.flow.pending)
        assertEquals(newer.prompt, late.flow.prompt)
        // On time, it's the usual: stay armed, the message in the prompt.
        val now = TagFlow().scanToErase().tap(TagResult.Inspected(check)).flow.erase()
            .tap(TagResult.Failed("part of it may already be gone", changedTag = true))
        assertEquals(now.flow.pending!!.request, TagRequest.Erase(target, text))
        assertEquals(emptyList<TagNotice>(), now.notices)
    }

    @Test
    fun aLateEraseResetsTheEraseTab() {
        // (c) Canceled during the erase: the tab doesn't keep a scan of a tag that's now empty.
        val (p, busy) = TagFlow().scanToErase().tap(TagResult.Inspected(check)).flow.erase().claim()
        val late = busy.cancel().flow.handle(p, TagResult.Erased(Rest.Cleared))
        assertNull(late.flow.eraseCheck)
        assertTrue(TagEffect.EraseDone in late.effects)
        assertEquals(TagNotice.Erased(Rest.Cleared), late.notices.single())
        // A newer scan of another tag is kept.
        val other = EraseCheck(true, ndef = true, writable = true, TagContent.Other, 137, "https://example.com", OverwriteTarget(byteArrayOf(9), byteArrayOf(9)))
        val rescanned = busy.cancel().flow.scanToErase().tap(TagResult.Inspected(other)).flow
        val late2 = rescanned.handle(p, TagResult.Erased(Rest.Cleared))
        assertEquals(other, late2.flow.eraseCheck)
        assertTrue(TagEffect.EraseDone !in late2.effects)
    }

    @Test
    fun erasingTheBackupClearsWhatDescribedIt() {
        // (d) Back up's Done, Lock and Verify mustn't go on describing a tag that's now empty.
        val done = TagFlow().scanToCheck().tap(TagResult.Checked(usable)).flow.write(sealed).tap(TagResult.Written(text, Rest.Cleared)).flow
        val erased = done.scanToErase().tap(TagResult.Inspected(check)).flow.erase().tap(TagResult.Erased(Rest.Cleared))
        assertTrue(TagEffect.Emptied(text, wasBackup = true) in erased.effects)
        with(erased.flow) {
            assertNull(writtenText)
            assertNull(backupFingerprint)
            assertNull(backupRest)
            assertNull(lockTarget)
            assertNull(tagCheck)
        }
        // Another tag's erase leaves the backup alone (and says which text went, for Verify).
        val otherText = "https://example.com"
        val other = done.scanToErase().tap(TagResult.Inspected(check.copy(text = otherText))).flow.erase().tap(TagResult.Erased(Rest.Cleared))
        assertTrue(TagEffect.Emptied(otherText, wasBackup = false) in other.effects)
        assertEquals("297a46bd", other.flow.backupFingerprint)
        assertEquals(text, other.flow.lockTarget)
    }

    @Test
    fun theWipeEndsTheEraseScan() {
        // (e) Nobody picking up the phone later can erase from a scan made before the wipe.
        val scanned = TagFlow().scanToErase().tap(TagResult.Inspected(check)).flow
        val wiped = scanned.wiped().flow
        assertNull(wiped.eraseCheck)
        assertNull(wiped.erase().flow.pending)
    }
}
