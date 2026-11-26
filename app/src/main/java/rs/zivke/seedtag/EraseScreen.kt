// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import rs.zivke.seedtag.core.EraseCheck
import rs.zivke.seedtag.core.TagContent

// ============================================================ erase tag ===

/**
 * The Erase tab: scan (nothing written), see what the tag holds, optionally check a backup's
 * password, then erase that very tag. Erasing fills the whole NDEF area with zeros before leaving
 * it empty.
 */
@Composable
internal fun EraseScreen(vm: SeedTagViewModel) {
    val c = vm.eraseCheck
    val verified = vm.eraseVerified
    val backup = c?.content == TagContent.Backup && c.target != null
    val typing = backup && verified == null && vm.eraseWithPassword
    StepScreen(
        bottom = {
            when {
                c == null -> PrimaryButton("Scan tag", vm::scanToErase)
                c.target == null -> PrimaryButton("Scan another tag", vm::scanToErase)
                typing -> {
                    SecretField("Password", vm, PwField.Erase, revealDefault = false, active = true) {}
                    PasswordKeyboard(vm, PwField.Erase)
                    PrimaryButton("Check password", vm::checkErasePassword, enabled = vm.passwordLength(PwField.Erase) > 0)
                    TextButton(onClick = vm::skipErasePassword, modifier = Modifier.fillMaxWidth()) {
                        Text("Don't check the password")
                    }
                }
                backup && verified == null -> {
                    PrimaryButton("Check the password first", vm::checkErasePasswordFirst)
                    DangerButton("Erase without checking", vm::requestErase)
                    SecondaryButton("Scan another tag", vm::scanToErase)
                }
                else -> {
                    DangerButton("Erase this tag", vm::requestErase)
                    SecondaryButton("Scan another tag", vm::scanToErase)
                }
            }
        },
    ) {
        if (!typing) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Erase a tag", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Erasing overwrites the tag's whole data area with zeros, then reads it again, confirming the tag " +
                        "to be empty. It can't be undone. It clears only this tag: if someone copied the backup from it " +
                        "earlier, their copy isn't touched. You don't have to erase an old backup to write a new one: " +
                        "Back up overwrites it, after asking you. A read-only tag can't be erased.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NfcSpotLink(vm)
            }
        }
        when {
            c == null -> Steps(
                listOf(
                    "Scan the tag. Nothing is written: the app only shows what it holds.",
                    "If it holds a SeedTag backup, you can check its password first, to be sure it's the backup you mean " +
                        "to erase. Without the password, the app can only tell it's a backup, not whose.",
                    "Erase it: the app asks first, then you hold the same tag to the phone again. Any other tag, or this one " +
                        "with different content, is refused.",
                ),
            )
            typing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Check the password", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Type the backup's password. The tag isn't scanned again: the app decrypts what it already read, and " +
                        "the words stay hidden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> EraseCheckResult(c, verified)
        }
    }
}

@Composable
private fun EraseCheckResult(c: EraseCheck, verified: VerifyResult?) {
    val (tone, title, hint) = when {
        !c.supported -> Triple(Tone.Caution, "Nothing to erase", "This tag doesn't support NDEF, the only data SeedTag reads or writes.")
        !c.ndef -> Triple(Tone.Info, "Nothing to erase", "This tag is blank: it has never been formatted, so it holds no NDEF data.")
        !c.writable -> Triple(
            Tone.Caution, "Can't be erased",
            "This tag is read-only. If it was made read-only for good, nothing can rewrite or erase it. To get rid of " +
                "what it holds, destroy the tag (cut through its antenna and chip).",
        )
        c.protected -> Triple(
            Tone.Caution, "Has a write password",
            "SeedTag gave this tag a write password, so it can't be erased. Verify it on the Verify tab with its " +
                "backup's password and tap Remove write password, then erase it here.",
        )
        verified != null -> Triple(Tone.Success, "Password checked", "Your password decrypts this tag to a valid ${verified.wordCount}-word seed.")
        c.content == TagContent.Backup -> Triple(
            Tone.Caution, "A SeedTag backup",
            "Erasing destroys it for good. Make sure it isn't your only copy of the seed. To be sure it's the backup you " +
                "mean, check its password first.",
        )
        c.content == TagContent.NewerBackup -> Triple(
            Tone.Caution, "A backup from a newer SeedTag",
            "This version can't read it, so it can't check its password. Erasing destroys it for good: make sure it isn't " +
                "your only copy of the seed.",
        )
        c.content == TagContent.Other -> Triple(Tone.Info, "Other data", "Not a SeedTag backup. Erasing replaces it with nothing.")
        else -> Triple(
            Tone.Info, "Empty",
            "It holds no data, but leftovers of something it held before may still be in its memory. Erasing overwrites them.",
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Notice(tone, title, hint)
        if (c.ndef) {
            Facts(
                buildList {
                    c.capacity?.let { add(Fact("Size", "$it bytes")) }
                    add(
                        when {
                            !c.writable -> Fact("State", "Read-only", Tone.Caution)
                            c.protected -> Fact("State", "Write password on", Tone.Caution)
                            else -> Fact("State", "Can be erased")
                        },
                    )
                    verified?.let { v ->
                        add(Fact("Fingerprint", v.fingerprint, mono = true))
                        when (v.matchesBackup) {
                            true -> add(Fact("Backup from this session", "Same seed", Tone.Caution))
                            false -> add(Fact("Backup from this session", "A different seed", null))
                            null -> Unit
                        }
                    }
                },
            )
        }
        verified?.let { v -> passwordFixNote(v.passwordFix)?.let { Notice(Tone.Caution, "Check your written password", it) } }
    }
}
