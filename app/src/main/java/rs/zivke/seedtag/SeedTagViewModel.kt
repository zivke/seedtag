// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag

import android.nfc.Tag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rs.zivke.seedtag.core.Bip39
import rs.zivke.seedtag.core.Passphrase
import rs.zivke.seedtag.core.SeedBox
import rs.zivke.seedtag.core.SeedTagException
import java.util.concurrent.atomic.AtomicReference

enum class Screen(val title: String) { Backup("Back up"), Verify("Verify"), Restore("Restore") }
enum class BackupStep { Words, Password, Done }

sealed interface MnemonicStatus {
    data class Incomplete(val count: Int) : MnemonicStatus
    data class Invalid(val reason: String) : MnemonicStatus
    data class Valid(val count: Int) : MnemonicStatus
}

data class ScanPrompt(val title: String, val error: String? = null)
data class VerifyResult(val wordCount: Int, val fingerprint: String)

sealed interface Dialog {
    data class Error(val message: String) : Dialog
    data class Info(val title: String, val message: String) : Dialog
    data class ConfirmOverwrite(val looksLikeSeedTag: Boolean) : Dialog
    data object ConfirmLock : Dialog
}

private enum class Purpose { BackupWrite, Verify, Restore, Lock }
private data class Pending(val request: TagRequest, val purpose: Purpose)

class SeedTagViewModel : ViewModel() {

    var tab by mutableStateOf(Screen.Backup)

    // ---- back up --------------------------------------------------------------
    val words = mutableStateListOf<String>()
    var prefix by mutableStateOf("")
        private set
    var step by mutableStateOf(BackupStep.Words)
        private set
    var password by mutableStateOf("")
    var passwordRepeat by mutableStateOf("")
    var generatedPassphrase by mutableStateOf(false)
        private set
    var backupFingerprint by mutableStateOf<String?>(null)
        private set
    private var pendingText: String? = null
    /** Tag text (Base64 blob, no secret) last written or decrypted: the only tag Lock will touch. */
    private var lockTarget: String? = null

    // ---- verify / restore -----------------------------------------------------
    var readPassword by mutableStateOf("")
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

    /** Read from the NFC binder thread, hence atomic rather than Compose state. */
    private val pending = AtomicReference<Pending?>(null)

    // =========================================================== word entry ===

    val suggestions: List<String> get() = Bip39.suggest(prefix, 6)

    val mnemonicStatus: MnemonicStatus
        get() {
            val n = words.size
            if (n !in Bip39.validWordCounts) return MnemonicStatus.Incomplete(n)
            return try {
                Bip39.wordsToEntropy(words.toList()).fill(0)
                MnemonicStatus.Valid(n)
            } catch (e: SeedTagException) {
                MnemonicStatus.Invalid(e.message ?: "invalid")
            }
        }

    fun typeLetter(c: Char) {
        if (words.size >= 24 || prefix.length >= 8) return
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
        if (words.size < 24 && Bip39.isWord(w)) { words += w; prefix = "" }
    }

    fun clearWords() { words.clear(); prefix = "" }

    fun toPasswordStep() {
        if (mnemonicStatus is MnemonicStatus.Valid) { prefix = ""; step = BackupStep.Password }
    }

    fun backToWords() { step = BackupStep.Words; password = ""; passwordRepeat = ""; generatedPassphrase = false }

    fun generatePassphrase() {
        val p = Passphrase.generate()
        password = String(p); passwordRepeat = password
        p.fill(' ')
        generatedPassphrase = true
    }

    fun onPasswordEdited() { generatedPassphrase = false }

    val passwordBits: Int get() = password.toCharArray().let { val b = Passphrase.estimateBits(it); it.fill(' '); b }

    // ================================================================ flows ===

    fun encryptAndWrite() {
        if (password.isEmpty()) return showError("Enter a password.")
        if (password != passwordRepeat) return showError("The passwords don't match.")
        val snapshot = words.toList()
        val pw = password.toCharArray()
        viewModelScope.launch {
            busy = "Encrypting… (deriving key with Argon2id, a few seconds)"
            try {
                val (text, fp) = withContext(Dispatchers.Default) {
                    val entropy = Bip39.wordsToEntropy(snapshot)
                    try {
                        SeedBox.toBase64(SeedBox.seal(entropy, pw)) to SeedBox.fingerprint(entropy)
                    } finally { entropy.fill(0) }
                }
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

    fun confirmOverwrite() {
        dialog = null
        val text = pendingText ?: return
        arm(TagRequest.Write(text, allowOverwrite = true), Purpose.BackupWrite, "Hold the tag again to overwrite it")
    }

    fun scanForVerify() = scanForRead(Purpose.Verify)
    fun scanForRestore() = scanForRead(Purpose.Restore)

    private fun scanForRead(purpose: Purpose) {
        if (readPassword.isEmpty()) return showError("Enter the password first.")
        verifyResult = null; restoredWords = null
        arm(TagRequest.Read, purpose, "Hold the tag to the back of the phone to read")
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

    fun cancelScan() { pending.set(null); scanPrompt = null }

    fun hideRestored() { restoredWords = null }

    fun startOver() {
        wipeBackup(); step = BackupStep.Words; backupFingerprint = null; lockTarget = null
    }

    // ================================================================= NFC ===

    private fun arm(request: TagRequest, purpose: Purpose, title: String) {
        pending.set(Pending(request, purpose))
        scanPrompt = ScanPrompt(title)
    }

    /** Called on the NFC reader thread. Blocking I/O is fine here. */
    fun onTagDiscovered(tag: Tag) {
        val p = pending.getAndSet(null) ?: return
        val result = TagIo.perform(tag, p.request)
        viewModelScope.launch { handle(p, result) }
    }

    private fun handle(p: Pending, r: TagResult) {
        when (r) {
            is TagResult.Failed -> {
                // stay armed so the user can simply tap again
                pending.set(p)
                scanPrompt = scanPrompt?.copy(error = r.message) ?: ScanPrompt("Hold the tag to the phone", r.message)
            }
            is TagResult.NotEmpty -> {
                scanPrompt = null
                val looksLike = r.existing?.let { runCatching { SeedBox.fromBase64(it).let { b -> b.isNotEmpty() && b[0].toInt() == SeedBox.VERSION } }.getOrNull() } == true
                dialog = Dialog.ConfirmOverwrite(looksLike)
            }
            is TagResult.WrittenUnchecked -> {
                arm(TagRequest.CheckWritten(r.text), Purpose.BackupWrite, "Written. Hold the tag to the phone once more to check it")
            }
            is TagResult.Written -> {
                scanPrompt = null
                wipeBackup()
                lockTarget = r.text
                step = BackupStep.Done
            }
            is TagResult.Locked -> {
                scanPrompt = null
                dialog = Dialog.Info("Tag locked", "The tag is now permanently read-only.")
            }
            is TagResult.Read -> {
                scanPrompt = null
                decrypt(r.text, reveal = p.purpose == Purpose.Restore)
            }
        }
    }

    private fun decrypt(text: String, reveal: Boolean) {
        val pw = readPassword.toCharArray()
        viewModelScope.launch {
            busy = "Decrypting… (deriving key with Argon2id, a few seconds)"
            try {
                val (words, fp) = withContext(Dispatchers.Default) {
                    val entropy = SeedBox.open(SeedBox.fromBase64(text), pw)
                    try {
                        Bip39.entropyToWords(entropy) to SeedBox.fingerprint(entropy)
                    } finally { entropy.fill(0) }
                }
                verifyResult = VerifyResult(words.size, fp)
                lockTarget = text
                if (reveal) restoredWords = words
                readPassword = ""
            } catch (e: SeedTagException) {
                showError(e.message ?: "Decryption failed.")
            } finally {
                pw.fill(' ')
                busy = null
            }
        }
    }

    // ============================================================== hygiene ===

    private fun wipeBackup() {
        words.clear(); prefix = ""; password = ""; passwordRepeat = ""
        generatedPassphrase = false; pendingText = null
    }

    /** Called when the app leaves the foreground: drop every secret from the UI state. */
    fun wipeAll() {
        cancelScan()
        if (step != BackupStep.Done) step = BackupStep.Words
        wipeBackup()
        readPassword = ""; restoredWords = null
        if (dialog is Dialog.ConfirmOverwrite) dialog = null
    }

    private fun showError(msg: String) { dialog = Dialog.Error(msg) }
}
