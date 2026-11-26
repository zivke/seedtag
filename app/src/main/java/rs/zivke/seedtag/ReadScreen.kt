// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import rs.zivke.seedtag.core.PasswordFix
import rs.zivke.seedtag.core.Protection
import rs.zivke.seedtag.core.TagInfo

// ===================================================== verify / restore ===

/**
 * Verify and Restore are two steps, like Back up: 1 · type the password and scan, 2 · the result.
 * The password field and keyboard are gone once the password has done its job.
 */
@Composable
internal fun ReadScreen(vm: SeedTagViewModel, reveal: Boolean) {
    val result = vm.verifyResult?.takeIf { it.restored == reveal }
    if (result == null) ReadPasswordStep(vm, reveal) else ReadResultStep(vm, reveal, result)
}

@Composable
private fun ReadPasswordStep(vm: SeedTagViewModel, reveal: Boolean) {
    // Only the guidance scrolls; the password, keyboard and Scan button stay fixed at the bottom.
    StepScreen(
        bottom = {
            SecretField("Password", vm, PwField.Read, revealDefault = false, active = true) {}
            PasswordKeyboard(vm, PwField.Read)
            PrimaryButton("Scan tag", if (reveal) vm::scanForRestore else vm::scanForVerify, enabled = vm.passwordLength(PwField.Read) > 0)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StepHeader(
                1, 2,
                if (reveal) "Restore a seed" else "Verify a tag",
                if (reveal) "Type the password, then scan the tag. The words will be shown on screen." else
                    "Type the password from your written copy, then scan the tag. The words stay hidden.",
            )
            NfcSpotLink(vm)
        }
        Preflight(vm, if (reveal) Flow.Restore else Flow.Verify)
    }
}

@Composable
private fun ReadResultStep(vm: SeedTagViewModel, reveal: Boolean, result: VerifyResult) {
    val differs = result.matchesBackup == false
    StepScreen(bottom = { SecondaryButton(if (reveal) "Restore another tag" else "Verify another tag", vm::readAnother) }) {
        Hero(
            if (differs) Tone.Caution else Tone.Success,
            when {
                differs -> "Not the backup you just wrote"
                reveal -> "Restored"
                else -> "Verified"
            },
            if (differs) {
                "Decrypted a ${result.wordCount}-word seed with a valid checksum, but it's a different seed from the " +
                    "backup written in this session."
            } else {
                "Decrypted a ${result.wordCount}-word seed with a valid checksum."
            },
        )
        Facts(
            buildList {
                add(Fact("Fingerprint", result.fingerprint, mono = true))
                add(
                    if (result.tagDifferences.isEmpty()) Fact("The tag's message", "Exactly as SeedTag writes it", Tone.Success)
                    else Fact("The tag's message", "Not as SeedTag writes it: see below", Tone.Caution),
                )
                result.tagInfo?.let { addAll(chipFacts(it)) }
                when (result.matchesBackup) {
                    true -> add(Fact("Backup from this session", "Same seed", Tone.Success))
                    false -> add(
                        Fact("Backup from this session", "A different seed (${result.backupFingerprint}). Check that you scanned the right tag.", Tone.Caution),
                    )
                    null -> Unit
                }
            },
        )
        passwordFixNote(result.passwordFix)?.let { Notice(Tone.Caution, "Check your written password", it) }
        if (result.tagDifferences.isNotEmpty()) TagDiffersNotice(result.tagDifferences)
        if (reveal) {
            val words = vm.restoredWords
            if (words != null) {
                WordGrid(words)
                DangerButton("Hide words", vm::hideRestored)
                Notice(
                    Tone.Caution,
                    "If your wallet shows no funds",
                    "These are only the seed's words. If your wallet also used a passphrase of its own (a \"25th word\", " +
                        "set in the wallet, not SeedTag's password), it isn't on the tag, and without it the wallet opens " +
                        "empty. A wallet can also look empty when the funds are under another account or address type: " +
                        "check its settings before you assume anything is lost.",
                )
            } else {
                Text(
                    "The words are hidden. To see them again, restore the tag once more.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Notice(
                Tone.Info,
                "What this means",
                "The tag reads correctly, and your password decrypts it to a valid ${result.wordCount}-word seed.",
                "The fingerprint is the same for the same seed on any tag, with any password and in seedtag.py. It " +
                    "isn't secret and doesn't reveal the words. Compare it with the one shown after the backup – if they " +
                    "match, this tag holds that seed. It's SeedTag's own: a wallet may show a fingerprint too, but that's " +
                    "a different number.",
            )
            val info = result.tagInfo
            val protection = info?.identity?.let { info.protection }
            if (info?.identity != null) WritePasswordSection(vm, info)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("A second tag")
                Text(
                    "Write this backup to another tag too, to keep somewhere else: the same text, so the same password " +
                        "opens it. No words or password needed. An identical copy gives away nothing more than one tag does.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryButton(COPY_BUTTON, vm::copyBackup)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Make it read-only")
                Text(
                    "Once you're sure, you can make the tag read-only. That's permanent: its data can never be rewritten or " +
                        "erased. " +
                        if (info?.identity != null) {
                            "On this tag SeedTag also locks its settings, so nobody can put a password on it either, and " +
                                "it reads the whole lock back. Keep other backups."
                        } else {
                            "It doesn't stop someone who gets hold of the tag from locking it against reading with a " +
                                "password of their own, so keep it where strangers can't reach it, and keep other backups."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (protection == null || protection == Protection.Off) LockButton(vm)
                else Text(
                    "The tag has a write password: remove it first, then make the tag read-only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CloseSection(vm)
    }
}

internal const val NOT_PROTECTABLE =
    "Not possible: SeedTag can give one only to an NTAG213, NTAG215 or NTAG216, or an NTAG21x lookalike that works like one"

/** Verify's lines about the chip: which it is, and its write password. */
private fun chipFacts(info: TagInfo): List<Fact> {
    val id = info.identity ?: return listOf(Fact("Chip", NOT_PROTECTABLE))
    return buildList {
        add(
            when {
                id.genuine -> Fact("Chip", id.chip.label)
                else -> Fact("Chip", "NTAG21x lookalike, not made by NXP: not officially supported", Tone.Caution)
            },
        )
        add(
            when (info.protection) {
                Protection.Off -> Fact("Write password", "Off: any phone can rewrite or erase the tag")
                Protection.Write -> Fact("Write password", "On: writing needs it, reading doesn't", Tone.Success)
                Protection.ReadWrite -> Fact("Write password", "On for reading too. SeedTag never sets that: another app did", Tone.Caution)
            },
        )
        if (info.settingsLocked) {
            add(
                if (info.protection == Protection.Off) Fact("Settings", "Locked for good: nobody can put a password on it")
                else Fact("Settings", "Locked for good: its write password can't be changed", Tone.Caution),
            )
        }
    }
}

/** Verify's "Write password": protect the tag from writes, or remove that again. */
@Composable
private fun WritePasswordSection(vm: SeedTagViewModel, info: TagInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Write password")
        when (info.protection) {
            Protection.Off -> {
                Text(
                    "SeedTag can give the tag a write password, made from your backup's password. Then no phone can rewrite " +
                        "or erase it, or make it read-only, without that password; reading stays open to any phone. Unlike " +
                        "making it read-only, it can be removed again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!info.settingsLocked) SecondaryButton("Protect from writes", vm::requestProtect)
            }
            Protection.Write -> {
                Text(
                    "Writing to the tag needs its write password. To rewrite, erase or lock the tag, remove it first. " +
                        "Only this backup's password can remove it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!info.settingsLocked) SecondaryButton("Remove write password", vm::unprotect)
            }
            Protection.ReadWrite -> Text(
                "Another app set a password on this tag. SeedTag doesn't change it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Says what had to be ignored in the typed password, so the user can fix their written copy. */
internal fun passwordFixNote(fix: PasswordFix): String? {
    val ignored = when (fix) {
        PasswordFix.None -> return null
        PasswordFix.Spaces -> "extra spaces"
        PasswordFix.Case -> "capital letters"
        PasswordFix.SpacesAndCase -> "extra spaces and capital letters"
    }
    return "The password only matched once $ignored were ignored. Make sure your written copy shows it exactly as it was set."
}
