// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.nfc.Tag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rs.zivke.seedtag.core.BackAction
import rs.zivke.seedtag.core.Bip39
import rs.zivke.seedtag.core.EraseCheck
import rs.zivke.seedtag.core.Haptic
import rs.zivke.seedtag.core.NtagChip
import rs.zivke.seedtag.core.Passphrase
import rs.zivke.seedtag.core.PasswordFix
import rs.zivke.seedtag.core.Pending
import rs.zivke.seedtag.core.Rest
import rs.zivke.seedtag.core.ScanPrompt
import rs.zivke.seedtag.core.SecretChars
import rs.zivke.seedtag.core.SeedBox
import rs.zivke.seedtag.core.SeedTagException
import rs.zivke.seedtag.core.Sealed
import rs.zivke.seedtag.core.TagCheck
import rs.zivke.seedtag.core.TagContent
import rs.zivke.seedtag.core.TagEffect
import rs.zivke.seedtag.core.TagFlow
import rs.zivke.seedtag.core.TagInfo
import rs.zivke.seedtag.core.TagNotice
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class Screen(val title: String) { Backup("Back up"), Verify("Verify"), Restore("Restore"), Erase("Erase") }
/** Pages shown instead of the tabs: the NFC-spot finder (a link on every tab's first step) and About (the top bar's ⓘ). */
enum class Page(val title: String) {
    Antenna("Where to hold the tag"),
    About("About"),
}

/** What the NFC-spot finder saw, from the tag's discovery info only: nothing is read from its memory. */
data class ProbeResult(val count: Int, val ndef: Boolean, val formatable: Boolean, val ndefSize: Int?, val writable: Boolean?)
/** [Tag]: scan the tag first, to see whether it can take a backup, and how many words. */
enum class BackupStep { Tag, Length, Words, Review, Password, Done }
/** The password fields: the new password and its repeat (Back up), Verify / Restore, and the Erase tag check. */
enum class PwField { Password, Repeat, Read, Erase }

sealed interface MnemonicStatus {
    data class Incomplete(val count: Int) : MnemonicStatus
    data class Invalid(val reason: String) : MnemonicStatus
    data class Valid(val count: Int) : MnemonicStatus
}

/**
 * [matchesBackup]: compared with the backup written in this session ([backupFingerprint]), null if
 * there was none.
 * [passwordFix]: what had to be ignored in the typed password before it matched.
 * [restored]: from Restore rather than Verify, so each tab shows only its own result.
 * [tagDifferences]: how the tag's message differs from what SeedTag writes, empty if it doesn't.
 */
data class VerifyResult(
    val wordCount: Int,
    val fingerprint: String,
    val matchesBackup: Boolean? = null,
    val backupFingerprint: String? = null,
    val passwordFix: PasswordFix = PasswordFix.None,
    val restored: Boolean = false,
    val tagDifferences: List<String> = emptyList(),
    /** The tag's text (the Base64 blob, not secret): which tag this result is about. */
    val tagText: String? = null,
    /** Verify only: the chip and its write protection, null if not known. */
    val tagInfo: TagInfo? = null,
)

/** A system Settings screen the app links to. */
enum class SettingsPage { Nfc, Accessibility, Developer }

sealed interface Dialog {
    data class Error(val message: String) : Dialog
    /**
     * The password didn't open the tag, even with spaces collapsed and lower-cased. [nonWords]: the
     * positions of words that aren't BIP39 words, if it looks like a word passphrase
     * ([Passphrase.nonWordPositions]); [erase]: on the Erase tab.
     */
    data class WrongPassword(val nonWords: List<Int>?, val erase: Boolean) : Dialog
    data class Info(val title: String, val message: String) : Dialog
    data class ConfirmOverwrite(val looksLikeSeedTag: Boolean) : Dialog
    /** A copy of the backup [text] is on another tag ([TagNotice.CopyWritten]); [rest]: its old data after it. */
    data class CopyWritten(val text: String, val rest: Rest) : Dialog
    /**
     * Make the verified tag read-only. [ntag]: Verify found an NTAG21x layout, which SeedTag locks itself, data
     * and settings; [lookalike]: one not made by NXP, so the question carries a warning.
     */
    data class ConfirmLock(val ntag: Boolean, val lookalike: Boolean) : Dialog
    /**
     * Give the verified tag a write password. [otherMaker]: Verify found a chip that answers like [chip] but
     * isn't NXP's, so the question carries a warning.
     */
    data class ConfirmProtect(val chip: NtagChip, val otherMaker: Boolean) : Dialog
    /** Erase the scanned tag, which holds [content]; [checked] if its backup was decrypted with its password first. */
    data class ConfirmErase(val content: TagContent, val checked: Boolean, val fingerprint: String?) : Dialog
    /** Generate a passphrase over a password that's already there; [generated] if that one was generated too. */
    data class ConfirmNewPassphrase(val generated: Boolean) : Dialog
    /** *Type my own instead*: clears the generated passphrase, which may already be written down. */
    data object ConfirmOwnPassword : Dialog
    /** Back would drop what was entered: [toReview] for the Password step's Back (clears the passwords), else leaving the app. */
    data class ConfirmBack(val toReview: Boolean) : Dialog
    /**
     * Close SeedTag: clear everything and end the process, so the copies [wipeAll] can't zero go with it
     * (board B4). MainActivity does the closing; this only asks.
     */
    data object ConfirmClose : Dialog
    /** Opening Settings at [page] would leave the app, which drops what was entered or restored. */
    data class ConfirmSettings(val page: SettingsPage) : Dialog
    /** The Words step's Clear would drop the [count] words entered so far. */
    data class ConfirmClearWords(val count: Int) : Dialog
    /**
     * Switching to tab [to] would hide the restored [words] and/or clear a typed [password]; from Back up
     * ([backup]), [words] are the seed words typed for the backup.
     */
    data class ConfirmTab(val to: Screen, val words: Boolean, val password: Boolean, val backup: Boolean = false) : Dialog
    /** Nothing was touched for [IDLE_WARN_MS] while secrets are in memory: they're wiped [IDLE_GRACE_MS] later. */
    data object StillThere : Dialog
    /**
     * A password the user typed (not the generated passphrase): the app can't rate it, so writing
     * always needs a confirmation. [bits] is the estimate, an upper bound; [sharesSeedWords] if it
     * reuses words of the seed.
     */
    data class ConfirmWeakPassword(val bits: Int, val sharesSeedWords: Boolean) : Dialog {
        val weak: Boolean get() = bits < Passphrase.MIN_BITS || sharesSeedWords
    }
}


const val STRAY_SPACES =
    "The password starts or ends with a space, or has two spaces in a row. A written copy rarely shows that, " +
        "and the tag could only be decrypted with exactly those spaces. Use single spaces between words only."

/** Argon2id needs 64 MiB of heap at once; a low-memory phone may not have it. */
private const val OUT_OF_MEMORY =
    "There wasn't enough free memory for the key derivation (Argon2id needs 64 MiB). Nothing was written or kept. " +
        "Close other apps and try again."
/**
 * While secrets are in memory the screen stays on (see [SeedTagViewModel.holdsSecrets]), so the app keeps its
 * own idle timeout instead: after this long without a touch it asks whether the user is still there...
 */
const val IDLE_WARN_MS = 3 * 60_000L
/** ...and wipes everything if there's no answer within this. */
const val IDLE_GRACE_MS = 60_000L


class SeedTagViewModel : ViewModel() {

    /** The tab shown; changed with [selectTab]. */
    var tab by mutableStateOf(Screen.Backup)
        private set

    /**
     * A tab was tapped. Leaving a tab clears its secrets: Back up loses the words and password typed for
     * the backup, Verify and Restore lose the typed password, Restore hides the restored words, Erase loses
     * its password (and starts afresh: scan canceled, result gone). If any of that is in memory, ask first
     * ([Dialog.ConfirmTab]).
     */
    fun selectTab(to: Screen) {
        if (to == tab) return
        // The tab bar is hidden while words or a password are typed, but Back from the Words step leads
        // to the word count, where it's shown and the words are still kept.
        val words = when (tab) {
            Screen.Restore -> restoredWords != null
            Screen.Backup -> words.isNotEmpty() || !prefixBuf.isEmpty()
            else -> false
        }
        val password = when (tab) {
            Screen.Verify, Screen.Restore -> !readPassword.isEmpty()
            Screen.Erase -> !erasePassword.isEmpty()
            Screen.Backup -> !this.password.isEmpty() || !passwordRepeat.isEmpty()
        }
        if (words || password) dialog = Dialog.ConfirmTab(to, words, password, backup = tab == Screen.Backup) else switchTab(to)
    }

    /** "Clear and switch" in [Dialog.ConfirmTab]. */
    fun confirmTab(to: Screen) {
        dialog = null
        switchTab(to)
    }

    private fun switchTab(to: Screen) {
        when (tab) {
            Screen.Verify, Screen.Restore -> { clearPasswords(PwField.Read); restoredWords = null }
            Screen.Erase -> resetErase()
            Screen.Backup -> {
                wipeBackup()
                if (step == BackupStep.Words || step == BackupStep.Review || step == BackupStep.Password) step = BackupStep.Length
            }
        }
        tab = to
    }
    /** An open top-bar page, or null for the tabs. */
    var page by mutableStateOf<Page?>(null)

    // ---- back up --------------------------------------------------------------
    val words = mutableStateListOf<String>()
    /**
     * Letters of the word being typed, in a zeroable buffer rather than a String: the first four
     * letters identify any BIP39 word, so String copies of them would add up to the seed.
     */
    private val prefixBuf = SecretChars(8)
    /** Bumped on every prefix change, so Compose redraws what depends on the buffer. */
    private var prefixEdits by mutableIntStateOf(0)
    val prefixLength: Int get() { prefixEdits; return prefixBuf.length }
    /** The letters typed as text, only for showing them while Show words is on. */
    fun revealPrefix(): String { prefixEdits; return prefixBuf.reveal() }
    private fun clearPrefix() { prefixBuf.clear(); prefixEdits++ }
    /** Mask the words already entered (on by default, back on after wipeAll). */
    var hideWords by mutableStateOf(true)
    var step by mutableStateOf(BackupStep.Tag)
        private set
    /** What the first step found out about the tag (nothing secret). Kept until Back up starts over. */
    val tagCheck: TagCheck? get() = flow.tagCheck
    /** Number of words chosen before typing; one of Bip39.validWordCounts. */
    var wordCount by mutableStateOf(24)
        private set
    var generatedPassphrase by mutableStateOf(false)
        private set
    /** Fingerprint of the backup last written and read back in this session. */
    val backupFingerprint: String? get() = flow.backupFingerprint
    /** The old data after the backup just written (shown on Done). */
    val backupRest: Rest? get() = flow.backupRest
    /** The backup last written and read back (not secret): what Done's copy writes. */
    val writtenText: String? get() = flow.writtenText

    // ---- passwords ------------------------------------------------------------
    // Zeroable char buffers, edited in place by the app's keyboard: never Strings, which would
    // leave a copy on the heap for every key typed.
    private val password = SecretChars()
    private val passwordRepeat = SecretChars()
    private val readPassword = SecretChars()
    private val erasePassword = SecretChars()
    /** Bumped on every password change, so Compose redraws what depends on the buffers. */
    private var passwordEdits by mutableIntStateOf(0)

    private fun buffer(f: PwField) = when (f) {
        PwField.Password -> password
        PwField.Repeat -> passwordRepeat
        PwField.Read -> readPassword
        PwField.Erase -> erasePassword
    }

    fun passwordLength(f: PwField): Int { passwordEdits; return buffer(f).length }

    /** The password as text, only while the user has asked to see it. */
    fun revealPassword(f: PwField): String { passwordEdits; return buffer(f).reveal() }

    /**
     * A generated passphrase is never edited in place (board B30): a stray key would quietly make it a typed
     * password that's no longer what the user wrote down. *Type my own instead* clears it first.
     */
    private fun editable(f: PwField): Boolean = !(f == PwField.Password && generatedPassphrase)

    fun typePassword(f: PwField, c: Char) { if (editable(f) && buffer(f).type(c)) passwordChanged(f) }

    fun backspacePassword(f: PwField) { if (!editable(f)) return; buffer(f).removeLast(); passwordChanged(f) }

    /** One repeat of a held ⌫ on the password keyboard: false once the field is empty. */
    fun backspacePasswordHeld(f: PwField): Boolean {
        if (!editable(f) || buffer(f).isEmpty()) return false
        backspacePassword(f)
        return true
    }

    /** Replaces the word being typed with [word] (a tapped BIP39 suggestion). */
    fun completePasswordWord(f: PwField, word: String) {
        if (!editable(f)) return
        val b = buffer(f)
        if (Bip39.isWord(word) && b.completeWord(word)) passwordChanged(f)
    }

    /** BIP39 completions of the word being typed, if it's lower-case letters only. */
    fun passwordSuggestions(f: PwField): List<String> {
        passwordEdits
        val b = buffer(f)
        val start = b.lastTokenStart()
        return b.use { chars, n ->
            if (start == n || (start until n).any { chars[it] !in 'a'..'z' }) emptyList()
            // The typed word too: tapping it puts the space after it ([SecretChars.completeWord]).
            else Bip39.suggestShortestFirst(chars, start, n, 6)
        }
    }

    val passwordsMatch: Boolean get() { passwordEdits; return password.contentEquals(passwordRepeat) }

    /** The new password starts or ends with a space, or has two in a row: refused, see [Passphrase.hasStraySpaces]. */
    val passwordHasStraySpaces: Boolean get() { passwordEdits; return password.use(Passphrase::hasStraySpaces) }

    private fun passwordChanged(f: PwField) {
        passwordEdits++
        // Typing the generated passphrase back into Repeat is the point; only editing it makes it typed.
        if (f == PwField.Password) generatedPassphrase = false
    }

    private fun clearPasswords(vararg fields: PwField) {
        fields.forEach { buffer(it).clear() }
        passwordEdits++
    }

    // ---- verify / restore -----------------------------------------------------
    var verifyResult by mutableStateOf<VerifyResult?>(null)
        private set
    var restoredWords by mutableStateOf<List<String>?>(null)
        private set

    // ---- erase ----------------------------------------------------------------
    /** What the Erase scan found (nothing secret), null before the scan. */
    val eraseCheck: EraseCheck? get() = flow.eraseCheck
    /** The backup on [eraseCheck], decrypted with the password typed: word count and fingerprint. */
    var eraseVerified by mutableStateOf<VerifyResult?>(null)
        private set
    /** The user chose to check the backup's password before erasing it. */
    var eraseWithPassword by mutableStateOf(false)
        private set

    fun checkErasePasswordFirst() { eraseWithPassword = true }

    /** "Don't check the password": the field goes away, and so does what was typed in it. */
    fun skipErasePassword() {
        eraseWithPassword = false
        clearPasswords(PwField.Erase)
    }
    /** Bumped by [resetErase]: a password check that finishes after it is thrown away. */
    private var eraseSession = 0L

    // ---- shared ---------------------------------------------------------------
    var busy by mutableStateOf<String?>(null)
        private set
    val scanPrompt: ScanPrompt? get() = flow.prompt
    /** The dialog under [Dialog.StillThere]: comes back after Keep, and late results land here, not over it. */
    private var below by mutableStateOf<Dialog?>(null)
    /** *Still there?* is up, on top of [below]. */
    private var idle by mutableStateOf(false)

    /**
     * The dialog shown: [Dialog.StillThere] while the idle timeout asks, on top of whatever was open.
     * Setting [Dialog.StillThere] raises it; anything else replaces the dialog under it.
     */
    var dialog: Dialog?
        get() = if (idle) Dialog.StillThere else below
        set(value) { if (value == Dialog.StillThere) idle = true else below = value }

    private val _haptics = MutableSharedFlow<Haptic>(extraBufferCapacity = 8)
    /** Played by the UI; emitted on the main thread. */
    val haptics: SharedFlow<Haptic> = _haptics

    /**
     * The tag flow: scans, what they found, and what a tag's result does ([TagFlow], tested in core).
     * Main thread only; changed through [apply].
     */
    private var flow by mutableStateOf(TagFlow())
    /** [TagFlow.pending], for the NFC binder thread to take atomically. */
    private val pending = AtomicReference<Pending?>(null)

    /** Takes [next]'s flow, then carries out its effects in order. */
    private fun apply(next: TagFlow.Next) {
        val before = flow
        flow = next.flow
        if (next.flow.pending !== before.pending) pending.set(next.flow.pending)
        next.effects.forEach { effect ->
            when (effect) {
                is TagEffect.Buzz -> _haptics.tryEmit(effect.haptic)
                is TagEffect.Notify -> dialog = dialogFor(effect.notice)
                is TagEffect.Decrypt -> decrypt(effect.text, effect.reveal, effect.differences, effect.info)
                is TagEffect.ProtectionChanged -> verifyResult?.let { r ->
                    val info = r.tagInfo
                    if (r.tagText == effect.text && info != null) verifyResult = r.copy(tagInfo = info.copy(protection = effect.protection))
                }
                TagEffect.BackupWritten -> {
                    // A Verify or Restore from before this backup describes another tag, or compared
                    // with an older backup: clear it, and a password typed there.
                    readAnother()
                    wipeBackup()
                    step = BackupStep.Done
                }
                TagEffect.EraseDone -> clearEraseState()
                is TagEffect.Emptied -> {
                    if (effect.wasBackup && step == BackupStep.Done) step = BackupStep.Tag
                    if (verifyResult?.tagText == effect.text) { verifyResult = null; restoredWords = null }
                }
            }
        }
    }
    /** The NFC-spot finder is open: tags that arrive with no scan pending are only counted. */
    private val probing = AtomicBoolean(false)
    var probe by mutableStateOf<ProbeResult?>(null)
        private set

    fun startProbe() { probe = null; probing.set(true) }
    fun stopProbe() { probing.set(false); probe = null; stopChipTest() }

    /** Debug builds only ([ChipTest]): the NFC-spot finder sends one raw command per tap instead of counting. */
    private val chipTesting = AtomicBoolean(false)
    private val chipTestNext = java.util.concurrent.atomic.AtomicInteger(0)
    var chipTestLines by mutableStateOf<List<String>>(emptyList())
        private set
    var chipTestNextLabel by mutableStateOf<String?>(null)
        private set

    fun startChipTest() {
        if (!ChipTest.AVAILABLE) return
        chipTestNext.set(0); chipTestLines = emptyList(); chipTestNextLabel = ChipTest.labels.first(); chipTesting.set(true)
    }

    fun stopChipTest() {
        chipTesting.set(false); chipTestLines = emptyList(); chipTestNextLabel = null
    }

    private fun chipTested(tag: Tag) {
        val i = chipTestNext.getAndIncrement()
        val line = ChipTest.run(tag, i)
        viewModelScope.launch {
            if (!chipTesting.get()) return@launch
            chipTestLines = chipTestLines + "${chipTestLines.size + 1}. $line"
            chipTestNextLabel = ChipTest.labels[(i + 1) % ChipTest.labels.size]
            _haptics.tryEmit(Haptic.Found)
        }
    }

    /** Bumped by wipeAll(): Argon2 work that finishes after it is thrown away. */
    private var wipes = 0L

    // =========================================================== word entry ===

    val suggestions: List<String> get() { prefixEdits; return prefixBuf.use { c, n -> Bip39.suggest(c, 0, n, 6) } }

    val mnemonicStatus: MnemonicStatus
        get() {
            val n = words.size
            if (n != wordCount) return MnemonicStatus.Incomplete(n)
            return try {
                Bip39.wordsToEntropy(words.toList()).fill(0)
                MnemonicStatus.Valid(n)
            } catch (e: SeedTagException) {
                MnemonicStatus.Invalid(e.message ?: "invalid")
            }
        }

    fun typeLetter(c: Char) {
        if (words.size >= wordCount || !prefixBuf.append(c.lowercaseChar())) return
        // Ignore keys that lead nowhere.
        if (prefixBuf.use { b, n -> Bip39.suggest(b, 0, n, 1) }.isEmpty()) prefixBuf.removeLast() else prefixEdits++
    }

    fun backspace() {
        when {
            !prefixBuf.isEmpty() -> { prefixBuf.removeLast(); prefixEdits++ }
            words.isNotEmpty() -> words.removeAt(words.lastIndex)
        }
    }

    /**
     * One repeat of a held ⌫ on the word keypad: deletes a letter of the word being typed, and stops
     * (false) at the start of the word. Entered words are only removed one tap at a time, so holding
     * the key can't wipe the whole phrase.
     */
    fun backspaceLetter(): Boolean {
        if (prefixBuf.isEmpty()) return false
        prefixBuf.removeLast(); prefixEdits++
        return true
    }

    fun pickWord(w: String) {
        if (words.size < wordCount && Bip39.isWord(w)) { words += w; clearPrefix() }
    }

    /** The Words step's Clear: asks first ([Dialog.ConfirmClearWords]) when there are words to lose. */
    fun requestClearWords() {
        if (words.isEmpty()) clearWords() else dialog = Dialog.ConfirmClearWords(words.size)
    }

    /** "Clear words" in [Dialog.ConfirmClearWords]. */
    fun confirmClearWords() {
        dialog = null
        clearWords()
    }

    private fun clearWords() { words.clear(); clearPrefix() }

    /** Word counts the checked tag has room for (all of them if its size is unknown). */
    fun fitsTag(n: Int): Boolean = tagCheck?.maxWords?.let { n <= it } ?: true

    fun scanToCheck() = apply(flow.scanToCheck())

    fun continueAfterCheck() {
        if (tagCheck?.usable == true) step = BackupStep.Length
    }

    /** "Check another tag" / "Change tag": back to the first step. */
    fun checkAnotherTag() {
        apply(flow.checkAnotherTag()); clearPrefix(); step = BackupStep.Tag
    }

    fun chooseLength(n: Int) {
        if (n !in Bip39.validWordCounts || n < words.size || !fitsTag(n)) return
        wordCount = n; step = BackupStep.Words
    }

    fun backToLength() { clearPrefix(); step = BackupStep.Length }

    /** The Words step's Next: a valid phrase goes to Check your words, where the user compares it (board B6). */
    fun toReviewStep() {
        if (mnemonicStatus is MnemonicStatus.Valid) { clearPrefix(); step = BackupStep.Review }
    }

    /**
     * Check your words' *They match*. Only while the words are shown: a wrong word can be another valid word
     * and still pass the checksum (1 in 16 for 12 words), so only comparing them catches it.
     */
    fun toPasswordStep() {
        if (step == BackupStep.Review && !hideWords && mnemonicStatus is MnemonicStatus.Valid) step = BackupStep.Password
    }

    /** Check your words' *Fix a word*: back to the keypad, every word kept. */
    fun backToWords() { step = BackupStep.Words }

    fun backToReview() { step = BackupStep.Review; clearPasswords(PwField.Password, PwField.Repeat); generatedPassphrase = false }

    /** The Password step's Back: asks first if that would clear a password. */
    fun requestBackToReview() {
        if (password.isEmpty() && passwordRepeat.isEmpty()) backToReview() else dialog = Dialog.ConfirmBack(toReview = true)
    }

    /**
     * Generate a passphrase into the password field. Repeat is left empty, for the user to type it back
     * from the written copy, which checks that copy. If there's a password already, ask first: it may
     * already be written down.
     */
    fun generatePassphrase() {
        if (!password.isEmpty()) {
            dialog = Dialog.ConfirmNewPassphrase(generatedPassphrase)
            return
        }
        newPassphrase()
    }

    /**
     * *Show it again*, while Repeat is typed from the written copy: Repeat is cleared, so it's typed from the copy
     * again rather than from the screen (board B30).
     */
    fun showPassphraseAgain() = clearPasswords(PwField.Repeat)

    /** *Type my own instead*: asks first, since the passphrase may be written down already. */
    fun requestOwnPassword() {
        if (generatedPassphrase) dialog = Dialog.ConfirmOwnPassword
    }

    /** "Clear it" in that dialog: both fields empty, for a password of the user's own. */
    fun confirmOwnPassword() {
        dialog = null
        clearPasswords(PwField.Password, PwField.Repeat)
        generatedPassphrase = false
    }

    /** "Replace" in the new-passphrase dialog. */
    fun confirmNewPassphrase() {
        dialog = null
        newPassphrase()
    }

    /** Counts generated passphrases, so the screen can show each new one. */
    var passphrasesGenerated by mutableIntStateOf(0)
        private set

    private fun newPassphrase() {
        val p = Passphrase.generate(exclude = words.toSet())
        password.set(p); passwordRepeat.clear()
        p.fill(' ')
        passphrasesGenerated++
        passwordEdits++
        generatedPassphrase = true
    }

    val passwordBits: Int
        get() { passwordEdits; return password.use { c, n -> Passphrase.estimateBits(c, n, words.toSet(), random = generatedPassphrase) } }

    /** Words of the seed in the password: whoever sees the password learns part of the seed. */
    val passwordSharesSeedWords: Boolean
        get() { passwordEdits; return password.use { c, n -> Passphrase.sharesWords(c, n, words.toSet()) } }

    // ================================================================ flows ===

    fun encryptAndWrite() {
        if (busy != null) return // a second tap before the busy dialog showed
        if (password.isEmpty()) return showError("Enter a password.")
        if (!password.contentEquals(passwordRepeat)) return showError("The passwords don't match.")
        if (passwordHasStraySpaces) return showError(STRAY_SPACES)
        // Only the generated passphrase is known to be random; anything typed is confirmed, however
        // long, since the estimate can't recognise a well-known phrase.
        if (!generatedPassphrase) {
            dialog = Dialog.ConfirmWeakPassword(passwordBits, passwordSharesSeedWords)
            return
        }
        encrypt()
    }

    /** "Write anyway" in the weak-password dialog. */
    fun confirmWeakPassword() {
        dialog = null
        if (!password.isEmpty() && password.contentEquals(passwordRepeat)) encrypt()
    }

    private fun encrypt() {
        val snapshot = words.toList()
        val pw = password.copy()
        runKdf(
            "Encrypting and checking… (Argon2id runs two or three times; this takes a few seconds)", pw,
            work = {
                // Once per run, before the first seal: a known answer for Argon2id and AES-GCM (board B21).
                if (!SeedBox.selfTest()) throw SeedTagException.SelfTestFailed()
                val entropy = Bip39.wordsToEntropy(snapshot)
                try {
                    val text = SeedBox.toBase64(SeedBox.seal(entropy, pw))
                    // Decrypt exactly what will be written, with a second, independent key derivation,
                    // so a fault on this device can't produce a backup nobody can open.
                    if (decryptsTo(text, pw, entropy)) text to SeedBox.fingerprint(entropy) else null
                } finally { entropy.fill(0) }
            },
        ) { sealed ->
            if (sealed == null) {
                showError(
                    "Self-check failed – the encrypted backup didn't decrypt again. Nothing was written to the tag. " +
                        "Try again, and if it keeps failing, don't make backups on this phone.",
                )
            } else {
                apply(flow.write(Sealed(sealed.first, sealed.second)))
            }
        }
    }

    /**
     * Runs [work] (Argon2id, 64 MiB) off the main thread, never two at once, with [label] in the
     * progress dialog, then [done] with its result on the main thread. If the app wiped meanwhile, or
     * [current] has turned false, nothing is shown: neither the result nor an error. Every exception
     * becomes an error dialog, never a crash. [pw] is zeroed at the end, also when nothing runs.
     */
    private fun <T> runKdf(
        label: String,
        pw: CharArray,
        current: () -> Boolean = { true },
        wrongPassword: Dialog.WrongPassword? = null,
        work: () -> T,
        /** The result came after a wipe or a newer step, so [done] isn't called: zero what it holds. */
        dropped: (T) -> Unit = {},
        done: (T) -> Unit,
    ) {
        if (busy != null) {
            pw.fill(' ')
            return showError("SeedTag is still busy with the previous step. Wait until it's done, then try again.")
        }
        val wipesAtStart = wipes
        fun stale() = wipes != wipesAtStart || !current()
        busy = label
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { work() }
                if (!stale()) done(result) else dropped(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SeedTagException.WrongPassword) {
                if (!stale()) dialog = wrongPassword ?: Dialog.Error(e.message ?: "Wrong password.")
            } catch (e: SeedTagException) {
                if (!stale()) showError(e.message ?: "Something went wrong. Nothing was written to a tag. Try again.")
            } catch (e: OutOfMemoryError) {
                if (!stale()) showError(OUT_OF_MEMORY)
            } catch (e: Exception) {
                // Only the type: another library's message isn't known to be free of secrets.
                if (!stale()) showError("Something went wrong (${e.javaClass.simpleName}). Nothing was written to a tag. Try again.")
            } finally {
                pw.fill(' ')
                busy = null
            }
        }
    }

    private fun decryptsTo(text: String, pw: CharArray, entropy: ByteArray): Boolean = try {
        val back = SeedBox.open(SeedBox.fromBase64(text), pw)
        try { back.contentEquals(entropy) } finally { back.fill(0) }
    } catch (e: SeedTagException) {
        false
    }

    fun confirmOverwrite() {
        dialog = null
        apply(flow.overwrite())
    }

    fun scanForVerify() = scanForRead(reveal = false)
    fun scanForRestore() = scanForRead(reveal = true)

    private fun scanForRead(reveal: Boolean) {
        if (readPassword.isEmpty()) return showError("Enter the password first.")
        verifyResult = null; restoredWords = null
        apply(flow.scanToRead(reveal))
    }

    fun requestLock() {
        if (flow.lockTarget == null) return showError("Write or verify the tag first.")
        val identity = verifyResult?.tagInfo?.identity
        dialog = Dialog.ConfirmLock(ntag = identity != null, lookalike = identity?.genuine == false)
    }

    fun confirmLock(lookalike: Boolean) {
        dialog = null
        apply(flow.lock(lookalike))
    }

    /** Done's "Clear old data", after [Rest.Interrupted]: only the tag that holds the backup just written. */
    fun clearRest() = apply(flow.clearRest())

    /**
     * Board B7: *Write a copy to another tag*, from Done (the backup just written) or from a Verify result (the
     * tag just decrypted). Only the tag's text: no words or password needed, and the copy opens with the same one.
     */
    fun copyBackup() {
        val verified = verifyResult?.takeIf { tab == Screen.Verify }
        val sealed = when {
            verified?.tagText != null -> Sealed(verified.tagText, verified.fingerprint)
            tab == Screen.Backup && step == BackupStep.Done -> flow.writtenText?.let { Sealed(it, flow.backupFingerprint ?: "") }
            else -> null
        } ?: return showError("Write or verify the tag first.")
        apply(flow.copyTo(sealed))
    }

    /** *Clear old data* in the copy's dialog. */
    fun clearCopyRest(text: String) {
        dialog = null
        apply(flow.clearCopyRest(text))
    }

    fun cancelScan() = apply(flow.cancel())

    // ---- erase ----------------------------------------------------------------

    /** Erase, first tap: see what the tag holds. Nothing is written. */
    fun scanToErase() {
        resetErase()
        apply(flow.scanToErase())
    }

    /** Decrypts the backup the scan read (no second scan), to be sure it's the one to erase. */
    fun checkErasePassword() {
        val text = eraseCheck?.text ?: return
        if (erasePassword.isEmpty()) return showError("Enter the password first.")
        val pw = erasePassword.copy()
        val session = eraseSession
        runKdf(
            "Checking the password… (Argon2id takes a few seconds, longer if the password needs a second try)", pw,
            current = { session == eraseSession },
            wrongPassword = Dialog.WrongPassword(Passphrase.nonWordPositions(pw), erase = true),
            work = {
                val opened = SeedBox.openForgiving(SeedBox.fromBase64(text), pw)
                try {
                    Triple(Bip39.entropyToWords(opened.entropy).size, SeedBox.fingerprint(opened.entropy), opened.fix)
                } finally { opened.entropy.fill(0) }
            },
        ) { (count, fp, fix) ->
            eraseVerified = VerifyResult(count, fp, backupFingerprint?.let { it == fp }, backupFingerprint, fix)
            clearPasswords(PwField.Erase)
            _haptics.tryEmit(Haptic.Success)
        }
    }

    fun requestErase() {
        val c = eraseCheck ?: return
        if (c.target == null) return
        dialog = Dialog.ConfirmErase(c.content, eraseVerified != null, eraseVerified?.fingerprint)
    }

    fun confirmErase() {
        dialog = null
        apply(flow.erase())
    }

    /** Back to the start of Erase: nothing scanned, no password, and its scan canceled. */
    private fun resetErase() {
        apply(flow.resetErase())
        clearEraseState()
    }

    /** The Erase tab's own state, outside [TagFlow]: the password, the decrypted check, the confirmation. */
    private fun clearEraseState() {
        eraseSession++
        eraseVerified = null; eraseWithPassword = false
        clearPasswords(PwField.Erase)
        if (below is Dialog.ConfirmErase) below = null
    }

    fun hideRestored() { restoredWords = null }

    /** "Verify another tag" / "Restore another tag": back to the password step, nothing kept. */
    fun readAnother() {
        verifyResult = null; restoredWords = null; clearPasswords(PwField.Read); setTagSecret(null)
    }

    /**
     * The verified tag's write-password secret ([TagKey.secret]), from Verify's decrypt; zeroed by
     * [readAnother] and [wipeAll]. A Protect scan refers to this very array, so a wipe during it reaches
     * tag I/O too (which then refuses a zeroed secret).
     */
    private var tagSecret: ByteArray? = null

    private fun setTagSecret(secret: ByteArray?) {
        tagSecret?.fill(0)
        tagSecret = secret
    }

    /** Verify's "Protect from writes": ask first, with a warning for a chip that isn't NXP's. */
    fun requestProtect() {
        val identity = verifyResult?.tagInfo?.identity
        if (tagSecret == null || flow.lockTarget == null || identity == null) return showError("Verify the tag first.")
        dialog = Dialog.ConfirmProtect(identity.chip, otherMaker = !identity.genuine)
    }

    fun confirmProtect() {
        val d = dialog as? Dialog.ConfirmProtect
        dialog = null
        val secret = tagSecret ?: return
        apply(flow.protect(secret, otherMaker = d?.otherMaker == true))
    }

    /** Verify's "Remove write password". Nothing is lost by it, so no question. */
    fun unprotect() {
        val secret = tagSecret ?: return showError("Verify the tag first.")
        apply(flow.unprotect(secret))
    }

    fun startOver() {
        wipeBackup(); step = BackupStep.Tag
        apply(flow.startOver())
    }

    // ================================================================= NFC ===

    /** Called on the NFC reader thread. Blocking I/O is fine here. */
    fun onTagDiscovered(tag: Tag) {
        val p = pending.getAndSet(null)
        if (p == null) {
            if (ChipTest.AVAILABLE && chipTesting.get()) chipTested(tag) else if (probing.get()) probed(tag)
            return
        }
        // Posted to the main thread in order: the claim first, then the steps, the result last.
        viewModelScope.launch { apply(flow.claimed(p)) }
        val result = TagIo.perform(tag, p.request) { step -> viewModelScope.launch { apply(flow.working(p, step)) } }
        viewModelScope.launch { apply(flow.handle(p, result)) }
    }

    /** Only the info Android hands over with the tag (no I/O): its type, NDEF size and lock state. */
    private fun probed(tag: Tag) {
        val ndef = Ndef.get(tag)
        val formatable = NdefFormatable.get(tag) != null
        val size = ndef?.maxSize
        val writable = ndef?.isWritable
        viewModelScope.launch {
            if (!probing.get()) return@launch
            probe = ProbeResult((probe?.count ?: 0) + 1, ndef != null, formatable, size, writable)
            _haptics.tryEmit(Haptic.Found)
        }
    }

    /** The words for a dialog the tag flow asks for. */
    private fun dialogFor(n: TagNotice): Dialog = when (n) {
        is TagNotice.ConfirmOverwrite -> Dialog.ConfirmOverwrite(n.looksLikeSeedTag)
        is TagNotice.CopyWritten -> Dialog.CopyWritten(n.text, n.rest)
        // Its message may say to tap again, but no scan is waiting any more.
        is TagNotice.LateFailure -> Dialog.Info(
            "The tag may have changed",
            n.message + "\n\nThis happened after the scan had ended (you left the app, or tapped Cancel). Holding the tag " +
                "to the phone does nothing until you start that step again.",
        )
        TagNotice.WrittenUnchecked -> Dialog.Info(
            "Written, not checked",
            "The backup was written to the blank tag, but the scan was canceled before it could be read back. " +
                "Tap Encrypt & write tag again and hold the same tag: the app asks before overwriting it, then " +
                "writes and checks it.",
        )
        is TagNotice.Locked -> if (n.checked) {
            Dialog.Info(
                "Tag is now read-only",
                "SeedTag locked the tag's data and its settings, and read them back: nobody can ever rewrite or erase " +
                    "it, or put a password on it. Reading stays open to any phone. Keep your other backups: a chip can " +
                    "still fail.",
            )
        } else {
            Dialog.Info(
                "Tag is now read-only",
                "Android locked its data, so it can't be rewritten or erased. SeedTag can't check this chip's lock bits " +
                    "itself. Keep the tag where strangers can't reach it: on many chips, someone holding it could still " +
                    "lock it against reading.",
            )
        }
        is TagNotice.AlreadyReadOnly -> if (n.checked) {
            Dialog.Info(
                "Already read-only",
                "This tag was already read-only for good: SeedTag read its lock bits and settings, and nothing was left " +
                    "to lock. It didn't change anything.",
            )
        } else {
            Dialog.Info(
                "Already read-only",
                "This tag was already read-only, so SeedTag didn't change it. It can't tell whether that's permanent: " +
                    "some tags are only marked read-only and can still be rewritten with other tools.",
            )
        }
        TagNotice.CantMakeReadOnly -> Dialog.Info(
            "Can't be made read-only",
            "This tag can't be made read-only from this phone. Android usually only offers it for NFC Forum Type 1 " +
                "and 2 tags (NTAG, MIFARE Ultralight). Nothing was changed: the backup on it is as it was.",
        )
        is TagNotice.ConfirmOtherMaker -> Dialog.ConfirmProtect(n.chip, otherMaker = true)
        TagNotice.Protected -> Dialog.Info(
            "Write password on",
            "The tag now refuses every write without its password, and SeedTag checked that on a fresh tap. Reading it " +
                "works as before, on any phone, with no password.\n\nThe write password comes from your backup's " +
                "password, so only someone who knows that can remove it: SeedTag (Verify, then Remove write password) " +
                "or seedtag.py tag-key. Erasing, rewriting or making the tag read-only all need it removed first.",
        )
        is TagNotice.NotEnforced -> Dialog.Info(
            "This chip doesn't enforce a write password",
            "It accepted a write without the password, or shows the password to anyone who reads it, so a password " +
                "would protect nothing. " +
                (if (n.switchedOff) "SeedTag left it switched off: the tag works as before." else
                    "SeedTag couldn't switch it off again. Verify the tag and tap Remove write password.") +
                " The backup on it is unchanged.",
        )
        TagNotice.ProtectUnchecked -> Dialog.Info(
            "Write password on, not checked",
            "The write password was switched on, but the scan ended before SeedTag could check that the tag refuses " +
                "writes without it. Verify the tag and tap Protect from writes again to check it.",
        )
        TagNotice.Unprotected -> Dialog.Info(
            "Write password removed",
            "Any phone can write to the tag, erase it or make it read-only again. The backup on it is unchanged.",
        )
        is TagNotice.Erased -> Dialog.Info(
            "Tag erased",
            when (n.rest) {
                Rest.Cleared ->
                    "The tag's whole data area was overwritten with zeros and then read back page by page: nothing " +
                        "it held is left in it. It can take a new backup."
                Rest.NotPossible ->
                    "The tag's data was overwritten with zeros and read again, and the tag is empty. It can take a new " +
                        "backup. On this kind of tag, SeedTag can't also check its memory page by page."
                Rest.Interrupted ->
                    "The tag's data was overwritten with zeros and read again, and the tag is empty. It moved away " +
                        "before SeedTag could also check its memory page by page: to be sure nothing is left, scan " +
                        "it and erase it once more."
            } +
                "\n\nErasing clears only this tag. If someone copied the backup from it " +
                "earlier (any phone can do that in a second), they still have their copy, and they can open it " +
                "if they know or guess your password. So if you think the tag may have been copied and your " +
                "password may be known or isn't strong, erasing doesn't protect you: move your funds to a new " +
                "seed.",
        )
    }

    /** What a decrypt found; [tagSecret] (Verify only) is zeroed by whoever ends up with it. */
    private class Decrypted(val words: List<String>, val fingerprint: String, val fix: PasswordFix, val tagSecret: ByteArray?)

    private fun decrypt(text: String, reveal: Boolean, differences: List<String>, info: TagInfo?) {
        val pw = readPassword.copy()
        runKdf(
            "Decrypting… (Argon2id takes a few seconds, longer if the password needs a second try)", pw,
            wrongPassword = Dialog.WrongPassword(Passphrase.nonWordPositions(pw), erase = false),
            work = {
                // A wrong password is retried without extra spaces, then lower-cased. Verify also keeps the
                // tag's write-password secret, for Protect / Remove write password.
                val opened = SeedBox.openForgiving(SeedBox.fromBase64(text), pw, withTagSecret = !reveal)
                try {
                    Decrypted(Bip39.entropyToWords(opened.entropy), SeedBox.fingerprint(opened.entropy), opened.fix, opened.tagSecret)
                } catch (e: Throwable) {
                    opened.tagSecret?.fill(0)
                    throw e
                } finally { opened.entropy.fill(0) }
            },
            dropped = { it.tagSecret?.fill(0) },
        ) { d ->
            val words = d.words
            val fp = d.fingerprint
            setTagSecret(d.tagSecret)
            verifyResult = VerifyResult(
                words.size, fp, backupFingerprint?.let { it == fp }, backupFingerprint, d.fix, restored = reveal,
                tagDifferences = differences, tagText = text, tagInfo = info,
            )
            apply(flow.opened(text))
            if (reveal) restoredWords = words
            clearPasswords(PwField.Read)
            _haptics.tryEmit(Haptic.Success)
        }
    }

    // ======================================================== back and idle ===

    /**
     * Seed words, a password, or restored words are in memory. Leaving the app would wipe them, so
     * meanwhile the screen stays on (the idle timeout takes over) and Back asks before leaving.
     */
    val holdsSecrets: Boolean
        get() {
            prefixEdits; passwordEdits
            return words.isNotEmpty() || !prefixBuf.isEmpty() || !password.isEmpty() || !passwordRepeat.isEmpty() ||
                !readPassword.isEmpty() || !erasePassword.isEmpty() || restoredWords != null
        }

    private val backAction: BackAction?
        get() = BackAction.of(
            pageOpen = page != null,
            onWordsStep = tab == Screen.Backup && step == BackupStep.Words,
            onReviewStep = tab == Screen.Backup && step == BackupStep.Review,
            onPasswordStep = tab == Screen.Backup && step == BackupStep.Password,
            holdsSecrets = holdsSecrets,
        )

    /** System Back is handled by [back] rather than leaving the app. */
    val backStaysInApp: Boolean get() = backAction != null

    /**
     * System Back: closes a top-bar page, does what the step's own Back button does on the Words and
     * Password steps, and elsewhere asks before leaving the app with secrets in memory ([BackAction]).
     */
    fun back() {
        when (backAction) {
            BackAction.ClosePage -> page = null
            BackAction.BackToLength -> backToLength()
            BackAction.BackToWords -> backToWords()
            BackAction.BackToReview -> requestBackToReview()
            BackAction.AskToLeave -> dialog = Dialog.ConfirmBack(toReview = false)
            null -> Unit
        }
    }

    /** A tag is being read or written right now: Close SeedTag waits for it ([requestClose]). */
    val tagWorking: Boolean get() = scanPrompt?.working != null

    /** Close SeedTag (top bar, Done, the Verify and Restore results): asks first, never during tag I/O. */
    fun requestClose() {
        if (!tagWorking) dialog = Dialog.ConfirmClose
    }

    /** Bumped by every touch (and Keep): restarts the idle timeout. */
    var touches by mutableIntStateOf(0)
        private set

    fun touched() { touches++ }

    /** The idle timeout ran out: ask, with a scan that's waiting for a tag canceled. */
    fun idleWarning() {
        // A tag is being read or written right now: that isn't idle, and the scan mustn't be canceled.
        if (scanPrompt?.working != null) { touched(); return }
        cancelScan()
        idle = true // over the dialog that's open, which comes back after Keep
        _haptics.tryEmit(Haptic.Found) // the longer buzz: felt even with the phone lying on the table
    }

    /** "Keep" in the still-there dialog. */
    fun stillThere() {
        idle = false
        touched()
    }

    /** No answer to the still-there dialog. */
    fun idleTimeout() {
        wipeAll()
        val minutes = (IDLE_WARN_MS + IDLE_GRACE_MS) / 60_000
        dialog = Dialog.Info(
            "Cleared",
            "Nothing was touched for $minutes minutes, so SeedTag cleared what you entered or restored. Start again " +
                "when you're ready.",
        )
    }

    // ============================================================== hygiene ===

    private fun wipeBackup() {
        words.clear(); clearPrefix(); clearPasswords(PwField.Password, PwField.Repeat); hideWords = true; wordCount = 24
        generatedPassphrase = false
    }

    /** Called when the app leaves the foreground: drop every secret from the UI state. */
    fun wipeAll() {
        wipes++
        apply(flow.wiped())
        if (step != BackupStep.Done && step != BackupStep.Tag) step = BackupStep.Length
        wipeBackup()
        clearPasswords(PwField.Read); restoredWords = null
        setTagSecret(null)
        clearEraseState() // its scan went with the flow's wipe
        // Every question goes, so nobody picking up the phone later can answer one. Messages stay.
        idle = false
        val d = below
        if (d is Dialog.ConfirmOverwrite || d is Dialog.ConfirmWeakPassword || d is Dialog.ConfirmNewPassphrase || d == Dialog.ConfirmOwnPassword ||
            d is Dialog.ConfirmBack || d is Dialog.ConfirmSettings || d is Dialog.ConfirmTab ||
            d is Dialog.ConfirmClearWords || d is Dialog.ConfirmErase || d is Dialog.ConfirmLock || d is Dialog.ConfirmProtect ||
            d == Dialog.ConfirmClose
        ) below = null
    }

    private fun showError(msg: String) {
        dialog = Dialog.Error(msg)
        _haptics.tryEmit(Haptic.Failure)
    }
}
