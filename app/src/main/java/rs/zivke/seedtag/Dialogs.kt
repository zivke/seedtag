// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import rs.zivke.seedtag.core.Passphrase
import rs.zivke.seedtag.core.Rest
import rs.zivke.seedtag.core.TagContent
import rs.zivke.seedtag.core.TagStep

// ============================================================= dialogs ===

/** Icon for dialogs that confirm something risky or permanent. */
@Composable
private fun WarningIcon() {
    Icon(iconOf(Tone.Caution), null, tint = MaterialTheme.colorScheme.error)
}

@Composable
private fun DangerText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error)
}

/** A slowly pulsing dot: waiting for the tag. */
@Composable
private fun ScanPulse() {
    val c = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "scan")
    val k by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "k")
    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(88.dp).scale(0.5f + 0.5f * k).alpha(1f - k).border(2.dp, c.primary, CircleShape))
        Box(Modifier.size(40.dp).background(c.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).background(c.primary, CircleShape))
        }
    }
}

/** A tag step taking this long has hung: Cancel comes back. Real steps take well under a second. */
private const val STUCK_MS = 15_000L

/** The scan prompt once the tag is found: what's being done, the step, and to keep the tag still. */
@Composable
private fun TagWorking(step: TagStep) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 4.dp)
    }
    Text(step.label + "…", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
    if (step.of > 1) {
        LinearProgressIndicator(progress = { step.step.toFloat() / step.of }, modifier = Modifier.fillMaxWidth())
        Text("Step ${step.step} of ${step.of}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
    Text(
        if (step.writes) {
            "Don't move the tag until this window closes: taking it away now could leave the job half-done."
        } else {
            "Keep the tag still until this window closes."
        },
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (step.writes) c.error else c.onSurface,
    )
}

@Composable
internal fun Dialogs(vm: SeedTagViewModel, leave: () -> Unit, openSettings: (SettingsPage) -> Unit, close: () -> Unit) {
    vm.busy?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            confirmButton = {},
            text = {
                GuardDialogWindow()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(msg)
                }
            },
        )
        return
    }

    vm.scanPrompt?.let { p ->
        val working = p.working
        // While the tag is being written, Cancel couldn't stop it anyway (the I/O runs to its end),
        // and moving the tag away is what would leave the job half-done: no Cancel, no Back. Unless a
        // step takes far longer than any tag needs (a hung NFC service), so the user isn't stuck.
        var stuck by remember { mutableStateOf(false) }
        LaunchedEffect(working) {
            stuck = false
            if (working?.writes == true) { delay(STUCK_MS); stuck = true }
        }
        val writing = working?.writes == true && !stuck
        AlertDialog(
            onDismissRequest = { if (!writing) vm.cancelScan() },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = {
                Text(if (working != null) "Tag found" else "Ready to scan", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            },
            text = {
                GuardDialogWindow()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (working == null) {
                        ScanPulse()
                        Text(p.title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Hold it flat over the phone's NFC spot and keep it still until this window closes. The app " +
                                "only reads or writes the tag while it's held there. When the phone finds the tag, it " +
                                "buzzes once and this window says so.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        p.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        TagWorking(working)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { if (!writing) TextButton(onClick = vm::cancelScan) { Text("Cancel") } },
        )
        return
    }

    when (val d = vm.dialog) {
        null -> Unit
        is Dialog.Error -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text("Something went wrong") },
            text = {
                GuardDialogWindow()
                Text(d.message)
            },
            confirmButton = { TextButton(onClick = { vm.dialog = null }) { Text("OK") } },
        )
        is Dialog.WrongPassword -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text("This password doesn't open the tag") },
            text = {
                GuardDialogWindow()
                Text(wrongPasswordText(d), modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { vm.dialog = null }) { Text("OK") } },
        )
        is Dialog.Info -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text(d.title) },
            text = {
                GuardDialogWindow()
                Text(d.message)
            },
            confirmButton = { TextButton(onClick = { vm.dialog = null }) { Text("OK") } },
        )
        is Dialog.ConfirmOverwrite -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text("Tag is not empty") },
            text = {
                GuardDialogWindow()
                Text(
                    if (d.looksLikeSeedTag) "This tag already holds a SeedTag backup. Overwriting it destroys that backup. Continue?"
                    else "This tag already contains data. Overwriting replaces it. Continue?",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmOverwrite) { DangerText("Overwrite") } },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Cancel") } },
        )
        is Dialog.ConfirmWeakPassword -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text(if (d.weak) "Weak password" else "Use your own password?") },
            text = {
                GuardDialogWindow()
                val parts = buildList {
                    if (d.sharesSeedWords) {
                        add("The password uses words from the seed phrase itself. Anyone who sees the password learns part of your seed.")
                    }
                    if (d.bits < Passphrase.MIN_BITS) {
                        add(
                            "This password's strength is estimated at only about ${d.bits} bits. Anyone who gets hold of the " +
                                "tag can try passwords offline without limit, and a password this weak could be guessed.",
                        )
                    } else {
                        add(
                            "SeedTag can't tell how guessable a password you chose is: its estimate (≈${d.bits} bits at most) " +
                                "only looks at length and characters. Well-known phrases, quotes, lyrics, names and dates are in " +
                                "every cracking list, however long they are. Use your own password only if it's truly random, " +
                                "for example from a password manager or dice.",
                        )
                    }
                    add(
                        "A generated 7-word passphrase has about 77 bits. Words you choose yourself count only about 6 bits " +
                            "each, because people pick common words and phrases.",
                    )
                }
                Text(parts.joinToString("\n\n"))
            },
            confirmButton = { TextButton(onClick = vm::confirmWeakPassword) { DangerText("Write anyway") } },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Change password") } },
        )
        is Dialog.ConfirmNewPassphrase -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text("Replace the password?") },
            text = {
                GuardDialogWindow()
                Text(
                    if (d.generated) {
                        "This replaces the passphrase shown with a new one. If you've already written the old one down, " +
                            "cross that copy out so it can't be mistaken for the new one."
                    } else {
                        "This replaces the password you typed with a generated passphrase."
                    },
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmNewPassphrase) { DangerText("Replace") } },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Keep it") } },
        )
        is Dialog.CopyWritten -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Copy written") },
            text = {
                GuardDialogWindow()
                Text(
                    "The copy was written and read back. Both tags now hold the same backup, and the same password opens " +
                        "both. Verify the copy once with your written password, and keep the tags in different places." +
                        when (d.rest) {
                            Rest.Cleared -> ""
                            Rest.Interrupted -> "\n\nWhat the copy held before may still be in its memory after the backup: " +
                                "it was being cleared when the tag moved away. Tap Clear old data and hold the copy still."
                            Rest.NotPossible -> "\n\nOn this tag SeedTag can't clear what it held before, after the backup. " +
                                "If it ever held something nobody should read, use a new tag instead."
                        },
                )
            },
            confirmButton = { TextButton(onClick = { vm.dialog = null }) { Text("OK") } },
            dismissButton = if (d.rest == Rest.Interrupted) {
                { TextButton(onClick = { vm.clearCopyRest(d.text) }) { Text("Clear old data") } }
            } else null,
        )
        Dialog.ConfirmOwnPassword -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text("Type your own password?") },
            text = {
                GuardDialogWindow()
                Text(
                    "This clears the generated passphrase. If you've already written it down, cross that copy out: it " +
                        "won't open this backup. A password you choose yourself is usually much weaker than a generated one, " +
                        "and SeedTag can't tell how strong it really is.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmOwnPassword) { DangerText("Clear it") } },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Keep the passphrase") } },
        )
        is Dialog.ConfirmBack -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text(if (d.toReview) "Clear the password?" else "Leave SeedTag?") },
            text = {
                GuardDialogWindow()
                Text(
                    if (d.toReview) {
                        "Going back to the words clears the password. If you've written a generated passphrase down, " +
                            "cross that copy out: the next one will be different."
                    } else {
                        "Leaving SeedTag clears everything you entered or restored: none of it is kept anywhere."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dialog = null; if (d.toReview) vm.backToReview() else leave() }) {
                    DangerText(if (d.toReview) "Clear and go back" else "Leave")
                }
            },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Stay") } },
        )
        Dialog.ConfirmClose -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Close SeedTag?") },
            text = {
                GuardDialogWindow()
                val lost = buildList {
                    vm.backupFingerprint?.let {
                        add("the fingerprint of the backup you just wrote ($it: note it first if you need it), and Verify " +
                            "won't be able to say whether a tag holds that backup")
                    }
                    vm.verifyResult?.let { add(if (it.restored) "the Restore result" else "the Verify result") }
                    if (vm.restoredWords != null) add("the restored words")
                }
                Text(
                    "Everything you typed or decrypted is cleared, and SeedTag ends, so the copies that clearing " +
                        "can't reach go with it: the password as text while Show was on, text already drawn on the " +
                        "screen, the memory used to turn the password into a key, and copies of the key." +
                        (if (lost.isEmpty()) "" else "\n\nWhat's on screen goes too: " + lost.joinToString("; ") + ".") +
                        "\n\nCopies outside SeedTag, such as the screen's graphics memory, and freed memory until " +
                        "it's used again, can't be cleared by any app: restart the phone before it goes back online.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.dialog = null
                        if (vm.tagWorking) {
                            vm.dialog = Dialog.Info("Wait for the tag", "A tag is being read or written. Close SeedTag once it's done.")
                        } else {
                            close()
                        }
                    },
                ) { Text(CLOSE_SEEDTAG) }
            },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Stay") } },
        )
        is Dialog.ConfirmSettings -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text("Leave SeedTag?") },
            text = {
                GuardDialogWindow()
                Text(
                    "Settings is a separate app, so opening it leaves SeedTag, and leaving clears everything you " +
                        "entered or restored: none of it is kept anywhere." +
                        if (d.page == SettingsPage.Nfc) {
                            " To turn NFC on without leaving, use the NFC button in quick settings instead, if your " +
                                "phone has one: swipe down from the top of the screen (twice, if the first swipe only " +
                                "shows the status bar)."
                        } else {
                            ""
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dialog = null; openSettings(d.page) }) { DangerText("Clear and open Settings") }
            },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Stay") } },
        )
        is Dialog.ConfirmClearWords -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text(if (d.count == 1) "Clear the word?" else "Clear all ${d.count} words?") },
            text = {
                GuardDialogWindow()
                Text(
                    (if (d.count == 1) "This removes the word entered so far." else "This removes all ${d.count} words entered so far.") +
                        " They aren't kept anywhere: you'll have to type them again from the start.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmClearWords) { DangerText("Clear words") } },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text(if (d.count == 1) "Keep it" else "Keep them") } },
        )
        is Dialog.ConfirmTab -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = {
                Text(
                    when {
                        d.backup && d.words -> "Clear the words?"
                        d.words -> "Hide the restored words?"
                        else -> "Clear the password?"
                    },
                )
            },
            text = {
                GuardDialogWindow()
                Text(
                    when {
                        d.backup && d.words ->
                            "Switching to ${d.to.title} clears the words you typed for the backup" +
                                (if (d.password) " and its password" else "") + ". They aren't kept anywhere: to back " +
                                "up, you'll have to type them again."
                        d.words && d.password ->
                            "Switching to ${d.to.title} hides the restored words and clears the password you typed. " +
                                "Neither is kept anywhere: to see the words again, restore the tag once more."
                        d.words ->
                            "Switching to ${d.to.title} hides the restored words. They aren't kept anywhere: to see " +
                                "them again, restore the tag once more."
                        else ->
                            "Switching to ${d.to.title} clears the password you typed. It isn't kept anywhere: " +
                                "you'll have to type it again."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmTab(d.to) }) { DangerText("Clear and switch") } },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Stay") } },
        )
        Dialog.StillThere -> AlertDialog(
            onDismissRequest = vm::stillThere,
            title = { Text("Still there?") },
            text = {
                GuardDialogWindow()
                Text(
                    "Nothing was touched for ${IDLE_WARN_MS / 60_000} minutes. While words or a password are on " +
                        "the phone, the screen stays on, so SeedTag clears them itself in a minute unless you tap Keep.",
                )
            },
            confirmButton = { TextButton(onClick = vm::stillThere) { Text("Keep") } },
            dismissButton = { TextButton(onClick = vm::wipeAll) { Text("Clear now") } },
        )
        is Dialog.ConfirmErase -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text(if (d.content == TagContent.Backup || d.content == TagContent.NewerBackup) "Erase this backup?" else "Erase this tag?") },
            text = {
                GuardDialogWindow()
                Text(
                    when {
                        d.checked -> "This destroys the backup you just checked (fingerprint ${d.fingerprint}) for good. " +
                            "Make sure you have the seed saved somewhere else. If someone copied the backup from the tag earlier, " +
                            "their copy isn't touched."
                        d.content == TagContent.Backup || d.content == TagContent.NewerBackup ->
                            "This tag holds a SeedTag backup, and its password wasn't checked, so it may not be the one you " +
                                "think. Erasing destroys it for good. Make sure you have the seed saved somewhere else. If someone " +
                                "copied the backup from the tag earlier, their copy isn't touched."
                        d.content == TagContent.Other -> "Everything the tag holds is overwritten and can't be recovered."
                        else -> "Any leftovers of earlier data in the tag's memory are overwritten."
                    },
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmErase) { DangerText("Erase") } },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Cancel") } },
        )
        is Dialog.ConfirmLock -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text(if (d.lookalike) "Not officially supported" else "Make tag read-only?") },
            text = {
                GuardDialogWindow()
                Text(
                    (if (d.lookalike) {
                        "This tag is an NTAG21x lookalike: it works like NXP's NTAG21x chips, but NXP didn't make it, and " +
                            "SeedTag doesn't officially support it. SeedTag writes its lock bits where an NTAG213 has them " +
                            "and reads them back, so it says if any part didn't take, but it can't foresee how every " +
                            "lookalike behaves: one could stay partly writable, or stop working. Go on only if losing this " +
                            "tag wouldn't matter: you have other backups of the seed.\n\n"
                    } else "") +
                        (if (d.ntag) {
                            "This is permanent: the tag's data and its settings are locked for good. Nobody can ever " +
                                "rewrite or erase it, or put a password on it, so it can't get a write password either. " +
                                "Reading stays open to any phone. "
                        } else {
                            "This is permanent: the tag's data can never be rewritten or erased. On many chips it doesn't " +
                                "stop someone who gets hold of the tag from making it unreadable, so keep the tag out of " +
                                "strangers' reach and keep other backups. "
                        }) +
                        "Only do this after you've successfully verified the backup with your written password.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmLock(d.lookalike) }) {
                    DangerText(if (d.lookalike) "Make read-only anyway" else "Make read-only")
                }
            },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Cancel") } },
        )
        is Dialog.ConfirmProtect -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { if (d.otherMaker) WarningIcon() },
            title = {
                Text(
                    if (d.otherMaker) "Not officially supported" else "Give the tag a write password?",
                )
            },
            text = {
                GuardDialogWindow()
                Text(
                    (if (d.otherMaker) {
                        "This tag is an NTAG21x lookalike: it works like NXP's NTAG21x chips, but NXP didn't make it, and " +
                            "SeedTag doesn't officially support it. Setting a write password may destroy the tag. It does " +
                            "seem to support one: SeedTag found the password settings where an NTAG21x has them. At the " +
                            "end, SeedTag checks that the tag refuses writes without the password, and switches it off " +
                            "again if it doesn't, but it can't foresee how every lookalike behaves: one could, for " +
                            "example, never accept a write again. Go on only if losing this tag wouldn't matter: you " +
                            "have other backups of the seed.\n\n"
                    } else "") +
                        "The tag will refuse every write (rewriting, erasing, making it read-only) without the password. " +
                        "Reading stays open to any phone, as now. The password is made from your backup's password, so " +
                        "whoever can decrypt the tag can remove it: SeedTag (Verify, then Remove write password) or " +
                        "seedtag.py tag-key.\n\nIt takes three taps: hold the tag to the phone, take it away and hold " +
                        "it again when asked. The write password goes to the tag unencrypted, now and whenever it's " +
                        "removed, so do this where nobody could be listening in with a radio receiver nearby.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmProtect) { Text(if (d.otherMaker) "Protect anyway" else "Protect") } },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Cancel") } },
        )
    }
}
