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

enum class Screen(val title: String) { Backup("Back up"), Verify("Verify"), Restore("Restore") }
/** Pages opened from the top bar ([button]), shown instead of the tabs. */
enum class Page(val title: String, val button: String) { Antenna("Where to hold the tag", "Find NFC spot"), About("About", "About") }

/** What the NFC-spot finder saw, from the tag's discovery info only: nothing is read from its memory. */
data class ProbeResult(val count: Int, val ndef: Boolean, val formatable: Boolean, val ndefSize: Int?, val writable: Boolean?)
/** [Tag]: scan the tag first, to see whether it can take a backup, and how many words. */
enum class BackupStep { Tag, Length, Words, Password, Done }
/** The three password fields: the new password and its repeat (Back up), and Verify / Restore. */
enum class PwField { Password, Repeat, Read }

sealed interface MnemonicStatus {
    data class Incomplete(val count: Int) : MnemonicStatus
    data class Invalid(val reason: String) : MnemonicStatus
    data class Valid(val count: Int) : MnemonicStatus
}

data class ScanPrompt(val title: String, val error: String? = null)
/**
 * [matchesBackup]: compared with the backup written in this session, null if there was none.
 * [passwordFix]: what had to be ignored in the typed password before it matched.
 * [restored]: from Restore rather than Verify, so each tab shows only its own result.
 */
data class VerifyResult(
    val wordCount: Int,
    val fingerprint: String,
    val matchesBackup: Boolean? = null,
    val passwordFix: PasswordFix = PasswordFix.None,
    val restored: Boolean = false,
)

sealed interface Dialog {
    data class Error(val message: String) : Dialog
    data class Info(val title: String, val message: String) : Dialog
    data class ConfirmOverwrite(val looksLikeSeedTag: Boolean) : Dialog
    data object ConfirmLock : Dialog
    /** Generate a passphrase over a password that's already there; [generated] if that one was generated too. */
    data class ConfirmNewPassphrase(val generated: Boolean) : Dialog
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
 */
enum class Haptic { Tick, Success, Failure, Found }

private enum class Purpose { Check, BackupWrite, Verify, Restore, Lock }

const val STRAY_SPACES =
    "The password starts or ends with a space, or has two spaces in a row. A written copy rarely shows that, " +
        "and the tag could only be decrypted with exactly those spaces. Use single spaces between words only."

/** Argon2id needs 64 MiB of heap at once; a low-memory phone may not have it. */
private const val OUT_OF_MEMORY =
    "There wasn't enough free memory for the key derivation (Argon2id needs 64 MiB). Nothing was written or kept. " +
        "Close other apps and try again."
/** [id] tells a result for the current scan from one that arrives after Cancel or a new scan. */
private data class Pending(val request: TagRequest, val purpose: Purpose, val id: Long)

class SeedTagViewModel : ViewModel() {

    var tab by mutableStateOf(Screen.Backup)
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
    var backupFingerprint by mutableStateOf<String?>(null)
        private set
    private var pendingText: String? = null
    /** The non-empty tag the overwrite dialog asked about: Overwrite applies to it alone. */
    private var overwriteTarget: OverwriteTarget? = null
    /** Tag text (Base64 blob, no secret) last written or decrypted: the only tag Lock will touch. */
    private var lockTarget: String? = null

    // ---- passwords ------------------------------------------------------------
    // Zeroable char buffers, edited in place by the app's keyboard: never Strings, which would
    // leave a copy on the heap for every key typed.
    private val password = SecretChars()
    private val passwordRepeat = SecretChars()
    private val readPassword = SecretChars()
    /** Bumped on every password change, so Compose redraws what depends on the buffers. */
    private var passwordEdits by mutableIntStateOf(0)

    private fun buffer(f: PwField) = when (f) {
        PwField.Password -> password
        PwField.Repeat -> passwordRepeat
        PwField.Read -> readPassword
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

    fun clearWords() { words.clear(); clearPrefix() }

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
        val wipesAtStart = wipes
        viewModelScope.launch {
            busy = "Encrypting and checking… (Argon2id runs twice; this takes a few seconds)"
            try {
                val sealed = withContext(Dispatchers.Default) {
                    val entropy = Bip39.wordsToEntropy(snapshot)
                    try {
                        val text = SeedBox.toBase64(SeedBox.seal(entropy, pw))
                        // Decrypt exactly what will be written, with a second, independent key derivation,
                        // so a fault on this device can't produce a backup nobody can open.
                        if (decryptsTo(text, pw, entropy)) text to SeedBox.fingerprint(entropy) else null
                    } finally { entropy.fill(0) }
                }
                if (wipes != wipesAtStart) return@launch
                if (sealed == null) {
                    return@launch showError(
                        "Self-check failed – the encrypted backup didn't decrypt again. Nothing was written to the tag. " +
                            "Try again, and if it keeps failing, don't make backups on this phone.",
                    )
                }
                val (text, fp) = sealed
                backupFingerprint = fp
                pendingText = text
                arm(TagRequest.Write(text), Purpose.BackupWrite, "Hold the same tag to the back of the phone to write the backup")
            } catch (e: SeedTagException) {
                showError(e.message ?: "Encryption failed.")
            } catch (e: OutOfMemoryError) {
                showError(OUT_OF_MEMORY)
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

    fun cancelScan() { scanId++; pending.set(null); scanPrompt = null }

    fun hideRestored() { restoredWords = null }

    /** "Verify another tag" / "Restore another tag": back to the password step, nothing kept. */
    fun readAnother() {
        verifyResult = null; restoredWords = null; clearPasswords(PwField.Read)
    }

    fun startOver() {
        wipeBackup(); step = BackupStep.Tag; tagCheck = null; backupFingerprint = null; lockTarget = null
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
        val result = TagIo.perform(tag, p.request)
        viewModelScope.launch { handle(p, result) }
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
        // that only ask for more input. Writes and locks did happen, so those are still shown.
        val stale = p.id != scanId
        when (r) {
            is TagResult.Checked -> {
                if (stale) return
                scanPrompt = null
                tagCheck = r.check
                _haptics.tryEmit(if (r.check.usable) Haptic.Success else Haptic.Failure)
            }
            is TagResult.Failed -> {
                if (stale) return
                _haptics.tryEmit(Haptic.Failure)
                // stay armed so the user can simply tap again
                pending.set(p)
                scanPrompt = scanPrompt?.copy(error = r.message) ?: ScanPrompt("Hold the tag to the phone", r.message)
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
                arm(TagRequest.CheckWritten(r.text), Purpose.BackupWrite, "Written. Hold the tag to the phone once more to check it")
            }
            is TagResult.Written -> {
                scanPrompt = null
                _haptics.tryEmit(Haptic.Success)
                wipeBackup()
                lockTarget = r.text
                step = BackupStep.Done
            }
            is TagResult.Locked -> {
                scanPrompt = null
                _haptics.tryEmit(Haptic.Success)
                dialog = Dialog.Info(
                    "Tag locked",
                    "The tag's data is now permanently read-only. Keep the tag where strangers can't reach it: on many " +
                        "chips, someone holding it could still lock it against reading.",
                )
            }
            is TagResult.AlreadyReadOnly -> {
                if (stale) return
                scanPrompt = null
                _haptics.tryEmit(Haptic.Tick)
                dialog = Dialog.Info(
                    "Already read-only",
                    "This tag was already read-only, so SeedTag didn't change it. It can't tell whether that's permanent: " +
                        "some tags are only marked read-only and can still be rewritten with other tools.",
                )
            }
            is TagResult.Read -> {
                if (stale) return
                scanPrompt = null
                _haptics.tryEmit(Haptic.Tick)
                decrypt(r.text, reveal = p.purpose == Purpose.Restore)
            }
        }
    }

    private fun decrypt(text: String, reveal: Boolean) {
        val pw = readPassword.copy()
        val wipesAtStart = wipes
        viewModelScope.launch {
            busy = "Decrypting… (Argon2id takes a few seconds, longer if the password needs a second try)"
            try {
                val (words, fp, fix) = withContext(Dispatchers.Default) {
                    // A wrong password is retried without extra spaces, then lower-cased.
                    val opened = SeedBox.openForgiving(SeedBox.fromBase64(text), pw)
                    try {
                        Triple(Bip39.entropyToWords(opened.entropy), SeedBox.fingerprint(opened.entropy), opened.fix)
                    } finally { opened.entropy.fill(0) }
                }
                if (wipes != wipesAtStart) return@launch
                verifyResult = VerifyResult(words.size, fp, backupFingerprint?.let { it == fp }, fix, restored = reveal)
                lockTarget = text
                if (reveal) restoredWords = words
                clearPasswords(PwField.Read)
                _haptics.tryEmit(Haptic.Success)
            } catch (e: SeedTagException) {
                showError(e.message ?: "Decryption failed.")
            } catch (e: OutOfMemoryError) {
                showError(OUT_OF_MEMORY)
            } finally {
                pw.fill(' ')
                busy = null
            }
        }
    }

    // ============================================================== hygiene ===

    private fun wipeBackup() {
        words.clear(); clearPrefix(); clearPasswords(PwField.Password, PwField.Repeat); hideWords = true; wordCount = 24
        generatedPassphrase = false; pendingText = null; overwriteTarget = null
    }

    /** Called when the app leaves the foreground: drop every secret from the UI state. */
    fun wipeAll() {
        wipes++
        cancelScan()
        if (step != BackupStep.Done && step != BackupStep.Tag) step = BackupStep.Length
        wipeBackup()
        clearPasswords(PwField.Read); restoredWords = null
        if (dialog is Dialog.ConfirmOverwrite || dialog is Dialog.ConfirmWeakPassword || dialog is Dialog.ConfirmNewPassphrase) dialog = null
    }

    private fun showError(msg: String) {
        dialog = Dialog.Error(msg)
        _haptics.tryEmit(Haptic.Failure)
    }
}
