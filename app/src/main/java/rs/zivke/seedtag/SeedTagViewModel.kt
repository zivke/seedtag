// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

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
enum class Page(val title: String, val button: String) { Antenna("Find NFC spot", "NFC spot"), About("About", "About") }

/** What the NFC-spot finder saw, from the tag's discovery info only: nothing is read from its memory. */
data class ProbeResult(val count: Int, val ndef: Boolean, val formatable: Boolean, val ndefSize: Int?, val writable: Boolean?)
enum class BackupStep { Length, Words, Password, Done }
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
 * [tag]: the chip and its write protection (Verify only); null if that couldn't be read.
 */
data class VerifyResult(
    val wordCount: Int,
    val fingerprint: String,
    val matchesBackup: Boolean? = null,
    val passwordFix: PasswordFix = PasswordFix.None,
    val restored: Boolean = false,
    val tag: TagInfo? = null,
)

sealed interface Dialog {
    data class Error(val message: String) : Dialog
    data class Info(val title: String, val message: String) : Dialog
    data class ConfirmOverwrite(val looksLikeSeedTag: Boolean) : Dialog
    data object ConfirmLock : Dialog
    data class ConfirmProtect(val chip: Chip) : Dialog
    /** The password is below [Passphrase.MIN_BITS] or reuses words of the seed. */
    data class ConfirmWeakPassword(val bits: Int, val sharesSeedWords: Boolean) : Dialog
}

/** Vibration for things that happen without a tap: a tag was handled, something succeeded or failed. */
enum class Haptic { Tick, Success, Failure }

private enum class Purpose { BackupWrite, Verify, Restore, Lock, Protect, Unprotect }
/** [id] tells a result for the current scan from one that arrives after Cancel or a new scan. */
private data class Pending(val request: TagRequest, val purpose: Purpose, val id: Long)

class SeedTagViewModel : ViewModel() {

    var tab by mutableStateOf(Screen.Backup)
    /** An open top-bar page, or null for the tabs. */
    var page by mutableStateOf<Page?>(null)

    // ---- back up --------------------------------------------------------------
    val words = mutableStateListOf<String>()
    var prefix by mutableStateOf("")
        private set
    /** Mask the words already entered (on by default, back on after wipeAll). */
    var hideWords by mutableStateOf(true)
    var step by mutableStateOf(BackupStep.Length)
        private set
    /** Number of words chosen before typing; one of Bip39.validWordCounts. */
    var wordCount by mutableStateOf(24)
        private set
    var generatedPassphrase by mutableStateOf(false)
        private set
    var backupFingerprint by mutableStateOf<String?>(null)
        private set
    private var pendingText: String? = null
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

    private fun passwordChanged(f: PwField) {
        passwordEdits++
        if (f != PwField.Read) generatedPassphrase = false
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
    /**
     * The verified tag's write-password secret ([rs.zivke.seedtag.core.TagKey]), kept after Verify so
     * Protect / Remove protection don't need the password typed again. Zeroed by readAnother() and
     * wipeAll(); [canChangeProtection] says whether it's there.
     */
    private var tagSecret: ByteArray? = null
    var canChangeProtection by mutableStateOf(false)
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

    val suggestions: List<String> get() = Bip39.suggest(prefix, 6)

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
        if (words.size >= wordCount || prefix.length >= 8) return
        val next = prefix + c
        if (Bip39.suggest(next, 1).isNotEmpty()) prefix = next // ignore keys that lead nowhere
    }

    fun backspace() {
        when {
            prefix.isNotEmpty() -> prefix = prefix.dropLast(1)
            words.isNotEmpty() -> words.removeAt(words.lastIndex)
        }
    }

    fun pickWord(w: String) {
        if (words.size < wordCount && Bip39.isWord(w)) { words += w; prefix = "" }
    }

    fun clearWords() { words.clear(); prefix = "" }

    fun chooseLength(n: Int) {
        if (n !in Bip39.validWordCounts || n < words.size) return
        wordCount = n; step = BackupStep.Words
    }

    fun backToLength() { prefix = ""; step = BackupStep.Length }

    fun toPasswordStep() {
        if (mnemonicStatus is MnemonicStatus.Valid) { prefix = ""; step = BackupStep.Password }
    }

    fun backToWords() { step = BackupStep.Words; clearPasswords(PwField.Password, PwField.Repeat); generatedPassphrase = false }

    fun generatePassphrase() {
        val p = Passphrase.generate(exclude = words.toSet())
        password.set(p); passwordRepeat.set(p)
        p.fill(' ')
        passwordEdits++
        generatedPassphrase = true
    }

    val passwordBits: Int
        get() { passwordEdits; return password.use { c, n -> Passphrase.estimateBits(c, n, words.toSet()) } }

    /** Words of the seed in the password: whoever sees the password learns part of the seed. */
    val passwordSharesSeedWords: Boolean
        get() { passwordEdits; return password.use { c, n -> Passphrase.sharesWords(c, n, words.toSet()) } }

    // ================================================================ flows ===

    fun encryptAndWrite() {
        if (password.isEmpty()) return showError("Enter a password.")
        if (!password.contentEquals(passwordRepeat)) return showError("The passwords don't match.")
        val bits = passwordBits
        val shares = passwordSharesSeedWords
        if (bits < Passphrase.MIN_BITS || shares) {
            dialog = Dialog.ConfirmWeakPassword(bits, shares)
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
            busy = "Encrypting and checking… (Argon2id runs twice, a few seconds)"
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
                        "Self-check failed: the encrypted backup didn't decrypt again. Nothing was written to the tag. " +
                            "Try again; if it keeps failing, don't make backups on this phone.",
                    )
                }
                val (text, fp) = sealed
                backupFingerprint = fp
                pendingText = text
                arm(TagRequest.Write(text), Purpose.BackupWrite, "Hold the tag to the back of the phone to write")
            } catch (e: SeedTagException) {
                showError(e.message ?: "Encryption failed.")
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
        arm(TagRequest.Write(text, allowOverwrite = true), Purpose.BackupWrite, "Hold the tag again to overwrite it")
    }

    fun scanForVerify() = scanForRead(Purpose.Verify)
    fun scanForRestore() = scanForRead(Purpose.Restore)

    private fun scanForRead(purpose: Purpose) {
        if (readPassword.isEmpty()) return showError("Enter the password first.")
        verifyResult = null; restoredWords = null; wipeTagSecret()
        arm(TagRequest.Read(withInfo = purpose == Purpose.Verify), purpose, "Hold the tag to the back of the phone to read")
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

    fun requestProtect() {
        val chip = verifyResult?.tag?.chip ?: return
        if (tagSecret == null) return showError("Verify the tag again first.")
        dialog = Dialog.ConfirmProtect(chip)
    }

    fun confirmProtect() {
        dialog = null
        val target = lockTarget ?: return
        val secret = tagSecret ?: return showError("Verify the tag again first.")
        arm(TagRequest.Protect(target, secret, finish = false), Purpose.Protect, PROTECT_STEP_1)
    }

    fun removeProtection() {
        val target = lockTarget ?: return
        val secret = tagSecret ?: return showError("Verify the tag again first.")
        arm(TagRequest.Unprotect(target, secret), Purpose.Unprotect, "Hold the same tag to the phone to remove its write protection")
    }

    fun cancelScan() { scanId++; pending.set(null); scanPrompt = null }

    fun hideRestored() { restoredWords = null }

    /** "Verify another tag" / "Restore another tag": back to the password step, nothing kept. */
    fun readAnother() {
        verifyResult = null; restoredWords = null; clearPasswords(PwField.Read); wipeTagSecret()
    }

    fun startOver() {
        wipeBackup(); step = BackupStep.Length; backupFingerprint = null; lockTarget = null
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
            _haptics.tryEmit(Haptic.Success)
        }
    }

    private fun handle(p: Pending, r: TagResult) {
        // Cancelled, left the app or started another scan while the tag was busy: drop results
        // that only ask for more input. Writes and locks did happen, so those are still shown.
        val stale = p.id != scanId
        when (r) {
            is TagResult.Failed -> {
                if (stale) return
                _haptics.tryEmit(Haptic.Failure)
                // stay armed so the user can simply tap again (or, with a retry, redo an earlier step)
                if (r.retry != null) {
                    pending.set(p.copy(request = r.retry))
                    scanPrompt = ScanPrompt(PROTECT_STEP_1, r.message)
                } else {
                    pending.set(p)
                    scanPrompt = scanPrompt?.copy(error = r.message) ?: ScanPrompt("Hold the tag to the phone", r.message)
                }
            }
            is TagResult.NotEmpty -> {
                if (stale) return
                scanPrompt = null
                _haptics.tryEmit(Haptic.Failure)
                val looksLike = r.existing?.let { runCatching { SeedBox.fromBase64(it).let { b -> b.isNotEmpty() && b[0].toInt() == SeedBox.VERSION } }.getOrNull() } == true
                dialog = Dialog.ConfirmOverwrite(looksLike)
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
                dialog = Dialog.Info("Tag locked", "The tag is now permanently read-only.")
            }
            is TagResult.Read -> {
                if (stale) return
                scanPrompt = null
                _haptics.tryEmit(Haptic.Tick)
                decrypt(r.text, reveal = p.purpose == Purpose.Restore, r.info)
            }
            TagResult.ProtectPrepared -> {
                if (stale) return
                _haptics.tryEmit(Haptic.Tick)
                val req = p.request as? TagRequest.Protect ?: return
                arm(TagRequest.Protect(req.expected, req.secret, finish = true), Purpose.Protect, PROTECT_STEP_2)
            }
            TagResult.Protected -> {
                scanPrompt = null
                _haptics.tryEmit(Haptic.Success)
                setProtection(Protection.Write)
                dialog = Dialog.Info(
                    "Tag write-protected",
                    "Nobody can overwrite, erase or lock this tag now without this backup's password. Reading it needs no " +
                        "password, as before. To back up onto it again, verify it and remove the protection first.",
                )
            }
            TagResult.Unprotected -> {
                scanPrompt = null
                _haptics.tryEmit(Haptic.Success)
                setProtection(Protection.Off)
                dialog = Dialog.Info("Protection removed", "Anyone can write to this tag again.")
            }
        }
    }

    private fun decrypt(text: String, reveal: Boolean, info: TagInfo?) {
        val pw = readPassword.copy()
        val wipesAtStart = wipes
        // Only Verify offers Protect / Remove protection, and only for a chip that has it.
        val wantSecret = !reveal && info?.chip != null
        viewModelScope.launch {
            busy = "Decrypting… (Argon2id, a few seconds; longer if the password needs a second try)"
            var secret: ByteArray? = null
            try {
                val (words, fp, fix) = withContext(Dispatchers.Default) {
                    // A wrong password is retried without extra spaces, then lower-cased.
                    val opened = SeedBox.openForgiving(SeedBox.fromBase64(text), pw, withTagSecret = wantSecret)
                    secret = opened.tagSecret
                    try {
                        Triple(Bip39.entropyToWords(opened.entropy), SeedBox.fingerprint(opened.entropy), opened.fix)
                    } finally { opened.entropy.fill(0) }
                }
                if (wipes != wipesAtStart) return@launch
                verifyResult = VerifyResult(words.size, fp, backupFingerprint?.let { it == fp }, fix, restored = reveal, tag = info)
                wipeTagSecret()
                tagSecret = secret; secret = null
                canChangeProtection = tagSecret != null
                lockTarget = text
                if (reveal) restoredWords = words
                clearPasswords(PwField.Read)
                _haptics.tryEmit(Haptic.Success)
            } catch (e: SeedTagException) {
                showError(e.message ?: "Decryption failed.")
            } finally {
                secret?.fill(0) // not kept: the scan was abandoned meanwhile
                pw.fill(' ')
                busy = null
            }
        }
    }

    private fun setProtection(p: Protection) {
        verifyResult = verifyResult?.let { v -> v.copy(tag = v.tag?.copy(protection = p)) }
    }

    // ============================================================== hygiene ===

    private fun wipeTagSecret() {
        tagSecret?.fill(0); tagSecret = null; canChangeProtection = false
    }

    private fun wipeBackup() {
        words.clear(); prefix = ""; clearPasswords(PwField.Password, PwField.Repeat); hideWords = true; wordCount = 24
        generatedPassphrase = false; pendingText = null
    }

    /** Called when the app leaves the foreground: drop every secret from the UI state. */
    fun wipeAll() {
        wipes++
        cancelScan()
        if (step != BackupStep.Done) step = BackupStep.Length
        wipeBackup()
        clearPasswords(PwField.Read); restoredWords = null; wipeTagSecret()
        if (dialog is Dialog.ConfirmOverwrite || dialog is Dialog.ConfirmWeakPassword || dialog is Dialog.ConfirmProtect) dialog = null
    }

    private fun showError(msg: String) {
        dialog = Dialog.Error(msg)
        _haptics.tryEmit(Haptic.Failure)
    }

    private companion object {
        const val PROTECT_STEP_1 = "Step 1 of 2: hold the same tag to the phone to store its write password"
        const val PROTECT_STEP_2 = "Step 1 done. Take the tag away, then hold it to the phone again to switch the protection on"
    }
}
