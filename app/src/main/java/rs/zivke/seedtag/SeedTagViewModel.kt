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
import rs.zivke.seedtag.core.Bip39
import rs.zivke.seedtag.core.Passphrase
import rs.zivke.seedtag.core.PasswordFix
import rs.zivke.seedtag.core.SecretChars
import rs.zivke.seedtag.core.SeedBox
import rs.zivke.seedtag.core.SeedTagException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class Screen(val title: String) { Backup("Back up"), Verify("Verify"), Restore("Restore"), Erase("Erase") }
/** Pages opened from the top bar ([button]), shown instead of the tabs. */
enum class Page(val title: String, val button: String) {
    Antenna("Where to hold the tag", "Find NFC spot"),
    About("About", "About"),
}

/** What the NFC-spot finder saw, from the tag's discovery info only: nothing is read from its memory. */
data class ProbeResult(val count: Int, val ndef: Boolean, val formatable: Boolean, val ndefSize: Int?, val writable: Boolean?)
/** [Tag]: scan the tag first, to see whether it can take a backup, and how many words. */
enum class BackupStep { Tag, Length, Words, Password, Done }
/** The password fields: the new password and its repeat (Back up), Verify / Restore, and the Erase tag check. */
enum class PwField { Password, Repeat, Read, Erase }

sealed interface MnemonicStatus {
    data class Incomplete(val count: Int) : MnemonicStatus
    data class Invalid(val reason: String) : MnemonicStatus
    data class Valid(val count: Int) : MnemonicStatus
}

/** [working]: a tag was found and is being read or written, null while waiting for one. */
data class ScanPrompt(val title: String, val error: String? = null, val working: TagStep? = null)
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
)

/** A system Settings screen the app links to. */
enum class SettingsPage { Nfc, Accessibility, Developer }

sealed interface Dialog {
    data class Error(val message: String) : Dialog
    data class Info(val title: String, val message: String) : Dialog
    data class ConfirmOverwrite(val looksLikeSeedTag: Boolean) : Dialog
    data object ConfirmLock : Dialog
    /** Erase the scanned tag, which holds [content]; [checked] if its backup was decrypted with its password first. */
    data class ConfirmErase(val content: TagContent, val checked: Boolean, val fingerprint: String?) : Dialog
    /** Generate a passphrase over a password that's already there; [generated] if that one was generated too. */
    data class ConfirmNewPassphrase(val generated: Boolean) : Dialog
    /** Back would drop what was entered: [toWords] for the Password step's Back (clears the passwords), else leaving the app. */
    data class ConfirmBack(val toWords: Boolean) : Dialog
    /** Opening Settings at [page] would leave the app, which drops what was entered or restored. */
    data class ConfirmSettings(val page: SettingsPage) : Dialog
    /** The Words step's Clear would drop the [count] words entered so far. */
    data class ConfirmClearWords(val count: Int) : Dialog
    /** Switching to tab [to] would hide the restored [words] and/or clear a typed [password]. */
    data class ConfirmTab(val to: Screen, val words: Boolean, val password: Boolean) : Dialog
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

/**
 * Vibration for things that happen without a tap: a tag was handled, something succeeded or failed.
 * [Found]: the NFC-spot finder saw a tag; a longer buzz, so it's felt through a hand on the phone.
 * [Detected]: a scan's tag was found and the app is working with it; one short, firm pulse.
 */
enum class Haptic { Tick, Success, Failure, Found, Detected }

private enum class Purpose { Check, BackupWrite, ClearRest, Verify, Restore, Lock, Inspect, Erase }

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

/** [id] tells a result for the current scan from one that arrives after Cancel or a new scan. */
private data class Pending(val request: TagRequest, val purpose: Purpose, val id: Long)

class SeedTagViewModel : ViewModel() {

    /** The tab shown; changed with [selectTab]. */
    var tab by mutableStateOf(Screen.Backup)
        private set

    /**
     * A tab was tapped. Leaving a tab clears its secrets: Verify and Restore lose the typed password,
     * Restore hides the restored words, Erase loses its password (and starts afresh: scan canceled,
     * result gone). If any of that is in memory, ask first ([Dialog.ConfirmTab]).
     */
    fun selectTab(to: Screen) {
        if (to == tab) return
        val words = tab == Screen.Restore && restoredWords != null
        val password = when (tab) {
            Screen.Verify, Screen.Restore -> !readPassword.isEmpty()
            Screen.Erase -> !erasePassword.isEmpty()
            Screen.Backup -> false
        }
        if (words || password) dialog = Dialog.ConfirmTab(to, words, password) else switchTab(to)
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
            Screen.Backup -> Unit
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
    var tagCheck by mutableStateOf<TagCheck?>(null)
        private set
    /** Number of words chosen before typing; one of Bip39.validWordCounts. */
    var wordCount by mutableStateOf(24)
        private set
    var generatedPassphrase by mutableStateOf(false)
        private set
    /** Fingerprint of the backup last written and read back in this session. */
    var backupFingerprint by mutableStateOf<String?>(null)
        private set
    private var pendingText: String? = null
    /** Fingerprint of [pendingText]: it becomes [backupFingerprint] only once the tag is written. */
    private var pendingFingerprint: String? = null
    /** The non-empty tag the overwrite dialog asked about: Overwrite applies to it alone. */
    private var overwriteTarget: OverwriteTarget? = null
    /** Tag text (Base64 blob, no secret) last written or decrypted: the only tag Lock will touch. */
    private var lockTarget: String? = null
    /** The old data after the backup just written (shown on Done), and that backup's text. */
    var backupRest by mutableStateOf<Rest?>(null)
        private set
    private var writtenText: String? = null

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

    fun typePassword(f: PwField, c: Char) { if (buffer(f).append(c)) passwordChanged(f) }

    fun backspacePassword(f: PwField) { buffer(f).removeLast(); passwordChanged(f) }

    /** One repeat of a held ⌫ on the password keyboard: false once the field is empty. */
    fun backspacePasswordHeld(f: PwField): Boolean {
        if (buffer(f).isEmpty()) return false
        backspacePassword(f)
        return true
    }

    /** Replaces the word being typed with [word] (a tapped BIP39 suggestion). */
    fun completePasswordWord(f: PwField, word: String) {
        val b = buffer(f)
        if (Bip39.isWord(word) && b.replaceFrom(b.lastTokenStart(), word)) passwordChanged(f)
    }

    /** BIP39 completions of the word being typed, if it's lower-case letters only. */
    fun passwordSuggestions(f: PwField): List<String> {
        passwordEdits
        val b = buffer(f)
        val start = b.lastTokenStart()
        return b.use { chars, n ->
            if (start == n || (start until n).any { chars[it] !in 'a'..'z' }) emptyList()
            else Bip39.suggest(chars, start, n, 6).filter { it.length != n - start } // not the word already typed
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
    var eraseCheck by mutableStateOf<EraseCheck?>(null)
        private set
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
    var scanPrompt by mutableStateOf<ScanPrompt?>(null)
        private set
    var dialog by mutableStateOf<Dialog?>(null)

    private val _haptics = MutableSharedFlow<Haptic>(extraBufferCapacity = 8)
    /** Played by the UI; emitted on the main thread. */
    val haptics: SharedFlow<Haptic> = _haptics

    /** Read from the NFC binder thread, hence atomic rather than Compose state. */
    private val pending = AtomicReference<Pending?>(null)
    /** The NFC-spot finder is open: tags that arrive with no scan pending are only counted. */
    private val probing = AtomicBoolean(false)
    var probe by mutableStateOf<ProbeResult?>(null)
        private set

    fun startProbe() { probe = null; probing.set(true) }
    fun stopProbe() { probing.set(false); probe = null }

    /** Bumped on every arm and cancel. Main thread only. */
    private var scanId = 0L
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

    fun scanToCheck() {
        tagCheck = null
        arm(TagRequest.Check, Purpose.Check, "Hold the tag you'll use to the back of the phone")
    }

    fun continueAfterCheck() {
        if (tagCheck?.usable == true) step = BackupStep.Length
    }

    /** "Check another tag" / "Change tag": back to the first step. */
    fun checkAnotherTag() {
        tagCheck = null; clearPrefix(); step = BackupStep.Tag
    }

    fun chooseLength(n: Int) {
        if (n !in Bip39.validWordCounts || n < words.size || !fitsTag(n)) return
        wordCount = n; step = BackupStep.Words
    }

    fun backToLength() { clearPrefix(); step = BackupStep.Length }

    fun toPasswordStep() {
        if (mnemonicStatus is MnemonicStatus.Valid) { clearPrefix(); step = BackupStep.Password }
    }

    fun backToWords() { step = BackupStep.Words; clearPasswords(PwField.Password, PwField.Repeat); generatedPassphrase = false }

    /** The Password step's Back: asks first if that would clear a password. */
    fun requestBackToWords() {
        if (password.isEmpty() && passwordRepeat.isEmpty()) backToWords() else dialog = Dialog.ConfirmBack(toWords = true)
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
            "Encrypting and checking… (Argon2id runs twice; this takes a few seconds)", pw,
            work = {
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
                val (text, fp) = sealed
                pendingFingerprint = fp
                pendingText = text
                arm(TagRequest.Write(text), Purpose.BackupWrite, "Hold the same tag to the back of the phone to write the backup")
            }
        }
    }

    /**
     * Runs [work] (Argon2id, 64 MiB) off the main thread, never two at once, with [label] in the
     * progress dialog, then [done] with its result on the main thread. If the app wiped meanwhile, or
     * [current] has turned false, nothing is shown: neither the result nor an error. Every exception
     * becomes an error dialog, never a crash. [pw] is zeroed at the end, also when nothing runs.
     */
    private fun <T> runKdf(label: String, pw: CharArray, current: () -> Boolean = { true }, work: () -> T, done: (T) -> Unit) {
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
                if (!stale()) done(result)
            } catch (e: CancellationException) {
                throw e
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
        val text = pendingText ?: return
        val target = overwriteTarget ?: return
        arm(TagRequest.Write(text, target), Purpose.BackupWrite, "Hold the same tag again to overwrite it")
    }

    fun scanForVerify() = scanForRead(Purpose.Verify)
    fun scanForRestore() = scanForRead(Purpose.Restore)

    private fun scanForRead(purpose: Purpose) {
        if (readPassword.isEmpty()) return showError("Enter the password first.")
        verifyResult = null; restoredWords = null
        arm(TagRequest.Read, purpose, "Hold the tag to the back of the phone to read it")
    }

    fun requestLock() {
        if (lockTarget == null) return showError("Write or verify the tag first.")
        dialog = Dialog.ConfirmLock
    }

    fun confirmLock() {
        dialog = null
        val target = lockTarget ?: return
        arm(TagRequest.Lock(target), Purpose.Lock, "Hold the same tag to make it permanently read-only")
    }

    /** Done's "Clear old data", after [Rest.Interrupted]: only the tag that holds the backup just written. */
    fun clearRest() {
        val text = writtenText ?: return
        arm(TagRequest.ClearRest(text), Purpose.ClearRest, "Hold the same tag to clear the old data after the backup")
    }

    fun cancelScan() { scanId++; pending.set(null); scanPrompt = null }

    // ---- erase ----------------------------------------------------------------

    /** Erase, first tap: see what the tag holds. Nothing is written. */
    fun scanToErase() {
        resetErase()
        arm(TagRequest.Inspect, Purpose.Inspect, "Hold the tag to the phone to see what it holds")
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
        val target = eraseCheck?.target ?: return
        arm(TagRequest.Erase(target), Purpose.Erase, "Hold the same tag to erase it")
    }

    /** Back to the start of Erase: nothing scanned, no password, and its scan canceled. */
    private fun resetErase() {
        eraseSession++
        val p = pending.get()?.purpose
        if (p == Purpose.Inspect || p == Purpose.Erase) cancelScan()
        eraseCheck = null; eraseVerified = null; eraseWithPassword = false
        clearPasswords(PwField.Erase)
        if (dialog is Dialog.ConfirmErase) dialog = null
    }

    fun hideRestored() { restoredWords = null }

    /** "Verify another tag" / "Restore another tag": back to the password step, nothing kept. */
    fun readAnother() {
        verifyResult = null; restoredWords = null; clearPasswords(PwField.Read)
    }

    fun startOver() {
        wipeBackup(); step = BackupStep.Tag; tagCheck = null; backupFingerprint = null; lockTarget = null
        backupRest = null; writtenText = null
    }

    // ================================================================= NFC ===

    private fun arm(request: TagRequest, purpose: Purpose, title: String) {
        pending.set(Pending(request, purpose, ++scanId))
        scanPrompt = ScanPrompt(title)
    }

    /** Called on the NFC reader thread. Blocking I/O is fine here. */
    fun onTagDiscovered(tag: Tag) {
        val p = pending.getAndSet(null)
        if (p == null) {
            if (probing.get()) probed(tag)
            return
        }
        // Steps and the result are posted to the main thread in order, so the result always comes last.
        val result = TagIo.perform(tag, p.request) { step -> viewModelScope.launch { working(p, step) } }
        viewModelScope.launch { handle(p, result) }
    }

    /** The scan [p]'s tag is being worked with: say so at once (the first step comes before any I/O). */
    private fun working(p: Pending, step: TagStep) {
        if (p.id != scanId) return
        val prompt = scanPrompt ?: return
        if (prompt.working == null) _haptics.tryEmit(Haptic.Detected)
        scanPrompt = prompt.copy(working = step, error = null)
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

    private fun handle(p: Pending, r: TagResult) {
        // Canceled, left the app or started another scan while the tag was busy: drop results
        // that only ask for more input. Writes and locks did happen, so those are still shown, but
        // without closing the prompt of a newer scan that's waiting for a tag.
        val stale = p.id != scanId
        fun closePrompt() { if (!stale || pending.get() == null) scanPrompt = null }
        when (r) {
            is TagResult.Checked -> {
                if (stale) return
                scanPrompt = null
                tagCheck = r.check
                _haptics.tryEmit(if (r.check.usable && !r.check.classic) Haptic.Success else Haptic.Failure)
            }
            is TagResult.Failed -> {
                if (stale) return
                _haptics.tryEmit(Haptic.Failure)
                // stay armed so the user can simply tap again
                pending.set(p)
                scanPrompt = scanPrompt?.copy(error = r.message, working = null) ?: ScanPrompt("Hold the tag to the phone", r.message)
            }
            is TagResult.NotEmpty -> {
                if (stale) return
                scanPrompt = null
                _haptics.tryEmit(Haptic.Failure)
                overwriteTarget = r.target
                dialog = Dialog.ConfirmOverwrite(TagIo.looksLikeBackup(r.existing))
            }
            is TagResult.WrittenUnchecked -> {
                _haptics.tryEmit(Haptic.Tick)
                if (stale) {
                    // Canceled: don't bring the scan prompt back by itself.
                    closePrompt()
                    dialog = Dialog.Info(
                        "Written, not checked",
                        "The backup was written to the blank tag, but the scan was canceled before it could be read back. " +
                            "Tap Encrypt & write tag again and hold the same tag: the app asks before overwriting it, then " +
                            "writes and checks it.",
                    )
                    return
                }
                arm(TagRequest.CheckWritten(r.text), Purpose.BackupWrite, "Written. Hold the tag to the phone once more to check it")
            }
            is TagResult.Written -> {
                closePrompt()
                _haptics.tryEmit(Haptic.Success)
                // A Verify or Restore from before this backup describes another tag, or compared
                // with an older backup: clear it, and a password typed there.
                backupFingerprint = pendingFingerprint
                readAnother()
                wipeBackup()
                lockTarget = r.text
                writtenText = r.text
                backupRest = r.rest
                step = BackupStep.Done
            }
            is TagResult.RestCleared -> {
                if (r.rest == Rest.Interrupted) {
                    if (stale) return
                    _haptics.tryEmit(Haptic.Failure)
                    pending.set(p) // stay armed: tap again
                    scanPrompt = scanPrompt?.copy(error = "The tag moved away too early. Hold it still and try again.", working = null)
                        ?: ScanPrompt("Hold the tag to the phone", "The tag moved away too early. Hold it still and try again.")
                    return
                }
                closePrompt()
                _haptics.tryEmit(Haptic.Success)
                backupRest = r.rest
            }
            is TagResult.Locked -> {
                closePrompt()
                _haptics.tryEmit(Haptic.Success)
                dialog = Dialog.Info(
                    "Tag is now read-only",
                    "Its data can never be rewritten or erased. Keep the tag where strangers can't reach it: on many " +
                        "chips, someone holding it could still lock it against reading.",
                )
            }
            is TagResult.AlreadyReadOnly -> {
                if (stale) return
                scanPrompt = null
                dialog = Dialog.Info(
                    "Already read-only",
                    "This tag was already read-only, so SeedTag didn't change it. It can't tell whether that's permanent: " +
                        "some tags are only marked read-only and can still be rewritten with other tools.",
                )
            }
            is TagResult.Inspected -> {
                if (stale) return
                scanPrompt = null
                eraseCheck = r.check
            }
            is TagResult.Erased -> {
                closePrompt()
                _haptics.tryEmit(Haptic.Success)
                // Lock must not be offered for a tag that no longer holds that backup.
                if (eraseCheck?.text != null && eraseCheck?.text == lockTarget) lockTarget = null
                if (!stale) resetErase()
                dialog = Dialog.Info(
                    "Tag erased",
                    "The tag's whole data area was overwritten with zeros and read again, confirming the tag to be " +
                        "empty. It can take a new backup.\n\nErasing clears only this tag. If someone copied the backup from it " +
                        "earlier (any phone can do that in a second), they still have their copy, and they can open it " +
                        "if they know or guess your password. So if you think the tag may have been copied and your " +
                        "password may be known or isn't strong, erasing doesn't protect you: move your funds to a new " +
                        "seed.",
                )
            }
            is TagResult.Read -> {
                if (stale) return
                scanPrompt = null
                decrypt(r.text, reveal = p.purpose == Purpose.Restore, differences = r.differences)
            }
        }
    }

    private fun decrypt(text: String, reveal: Boolean, differences: List<String>) {
        val pw = readPassword.copy()
        runKdf(
            "Decrypting… (Argon2id takes a few seconds, longer if the password needs a second try)", pw,
            work = {
                // A wrong password is retried without extra spaces, then lower-cased.
                val opened = SeedBox.openForgiving(SeedBox.fromBase64(text), pw)
                try {
                    Triple(Bip39.entropyToWords(opened.entropy), SeedBox.fingerprint(opened.entropy), opened.fix)
                } finally { opened.entropy.fill(0) }
            },
        ) { (words, fp, fix) ->
            verifyResult = VerifyResult(
                words.size, fp, backupFingerprint?.let { it == fp }, backupFingerprint, fix, restored = reveal,
                tagDifferences = differences,
            )
            lockTarget = text
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

    /** System Back is handled by [back] rather than leaving the app. */
    val backStaysInApp: Boolean
        get() = page != null || (tab == Screen.Backup && (step == BackupStep.Words || step == BackupStep.Password)) ||
            holdsSecrets

    /**
     * System Back: closes a top-bar page, does what the step's own Back button does on the Words and
     * Password steps, and elsewhere asks before leaving the app with secrets in memory.
     */
    fun back() {
        when {
            page != null -> page = null
            tab == Screen.Backup && step == BackupStep.Words -> backToLength()
            tab == Screen.Backup && step == BackupStep.Password -> requestBackToWords()
            holdsSecrets -> dialog = Dialog.ConfirmBack(toWords = false)
        }
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
        dialog = Dialog.StillThere
        _haptics.tryEmit(Haptic.Found) // the longer buzz: felt even with the phone lying on the table
    }

    /** "Keep" in the still-there dialog. */
    fun stillThere() {
        if (dialog == Dialog.StillThere) dialog = null
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
        generatedPassphrase = false; pendingText = null; pendingFingerprint = null; overwriteTarget = null
    }

    /** Called when the app leaves the foreground: drop every secret from the UI state. */
    fun wipeAll() {
        wipes++
        cancelScan()
        if (step != BackupStep.Done && step != BackupStep.Tag) step = BackupStep.Length
        wipeBackup()
        clearPasswords(PwField.Read, PwField.Erase); restoredWords = null
        val d = dialog
        if (d is Dialog.ConfirmOverwrite || d is Dialog.ConfirmWeakPassword || d is Dialog.ConfirmNewPassphrase ||
            d is Dialog.ConfirmBack || d is Dialog.ConfirmSettings || d is Dialog.ConfirmTab ||
            d is Dialog.ConfirmClearWords || d == Dialog.StillThere
        ) dialog = null
    }

    private fun showError(msg: String) {
        dialog = Dialog.Error(msg)
        _haptics.tryEmit(Haptic.Failure)
    }
}
