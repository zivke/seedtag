// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rs.zivke.seedtag.core.Bip39
import rs.zivke.seedtag.core.Rest
import rs.zivke.seedtag.core.SeedBox
import rs.zivke.seedtag.core.TagCheck
import rs.zivke.seedtag.core.TagContent
import rs.zivke.seedtag.core.TagStep

// ============================================================= back up ===

private const val BACKUP_STEPS = 5

/** Board B7: the same backup to a second tag, from Done and from a Verify result. */
internal const val COPY_BUTTON = "Write a copy to another tag"

@Composable
internal fun BackupScreen(vm: SeedTagViewModel) {
    when (vm.step) {
        BackupStep.Tag -> TagStep(vm)
        BackupStep.Length -> LengthStep(vm)
        BackupStep.Words -> WordsStep(vm)
        BackupStep.Review -> ReviewStep(vm)
        BackupStep.Password -> PasswordStep(vm)
        BackupStep.Done -> DoneStep(vm)
    }
}

/** The checklist item that comes right after "Read every screen carefully", and the About section. */
internal const val SPARE_PHONE_TITLE = "Safest on a spare phone"
internal const val SPARE_PHONE_SUMMARY =
    "Use SeedTag on a spare phone that's never online, not on the phone you use every day."
internal const val SPARE_PHONE_DETAILS =
    "SeedTag itself can't go online, but other apps on the same phone can. If one of them is malware, it could " +
        "capture the words you type in Back up, see them on the Restore screen, or catch the password you type in " +
        "Verify – and the password plus the tag is the seed. An everyday phone has many apps and accounts and is " +
        "online almost all the time, so that risk can't be ruled out there.\n\n" +
        "A spare phone that's never online removes that risk: whatever might be on it has no way to send anything out. Any " +
        "old Android phone will do, though Android 12 and later hide other apps' overlays and Android 14 and later " +
        "also shut out accessibility services. To prepare it, while it can still go online: install all system " +
        "updates, then factory-reset it. In setup, skip Wi-Fi and don't add an account, and take the SIM out. Copy " +
        "SeedTag over by USB or an SD card instead of downloading it on the phone, and leave Developer options off. " +
        "From then on it never goes online. Keep it in a drawer for the yearly Verify, and factory-reset it before it " +
        "ever goes online again or is given away.\n\n" +
        "If you don't have a spare phone, you can use your everyday phone after making sure it's offline, but be " +
        "aware that it is a less safe option. For more details, read Go offline first, in the checklist on Back up, " +
        "Verify and Restore."

internal const val CLOSE_SEEDTAG = "Close SeedTag"

/** Why Close SeedTag exists (board B4): the checklist's Nothing is kept, and the About section. */
internal const val CLOSE_DETAILS =
    "Leaving SeedTag clears what you typed or decrypted, but a few copies can't be cleared by an app: the password " +
        "as text while Show is on, text already drawn on the screen, the memory used to turn the password into a " +
        "key, and copies of the key inside Android's encryption. They stay in SeedTag's memory until Android ends " +
        "SeedTag, which can take hours or days.\n\n" +
        "Close SeedTag (✕ at the top right, and at the end of Back up, Verify and Restore) ends it at once, so they go " +
        "with it. Even then, copies outside SeedTag, such as the screen's graphics memory, and freed memory until " +
        "it's used again, can't be cleared by any app: restart the phone before it goes back online."

internal const val DISCLAIMER =
    "SeedTag comes without any warranty, and its developer is not responsible for any loss or damage resulting from " +
        "its use, including a lost seed or lost funds. You use it at your own risk.\n\n" +
        "SeedTag is beta software, still in development. It has only been tested with NXP NTAG21x tags (NTAG213 and NTAG215): other " +
        "tag brands and types may not work as described. Before relying on a tag, back up to it, then Verify and " +
        "Restore it (and ideally decrypt it with seedtag.py), and keep another copy of your seed.\n\n" +
        "Before you enter a seed, it's up to you to make sure this phone is free of malware. SeedTag defends against " +
        "some attacks by other apps, but it can't cover every possible one. If the phone is compromised, your seed may be too."

@Composable
internal fun TagStep(vm: SeedTagViewModel) {
    val check = vm.tagCheck
    StepScreen(
        bottom = {
            when {
                check == null -> PrimaryButton("Scan tag", vm::scanToCheck)
                check.usable && check.classic -> {
                    PrimaryButton("Check another tag", vm::scanToCheck)
                    SecondaryButton("Use this tag anyway", vm::continueAfterCheck)
                }
                check.usable -> {
                    PrimaryButton("Continue with this tag", vm::continueAfterCheck)
                    SecondaryButton("Check another tag", vm::scanToCheck)
                }
                else -> PrimaryButton("Check another tag", vm::scanToCheck)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StepHeader(
                1, BACKUP_STEPS, "Check your tag",
                "First, SeedTag scans your tag to check whether it works, whether it already holds data and how much " +
                    "it can hold. Nothing is written to it. Tap Scan tag, then hold the tag to the phone.",
            )
            NfcSpotLink(vm)
        }
        if (check != null) TagCheckResult(check)
        Preflight(vm, Flow.Backup)
    }
}

/** The way to the NFC-spot finder, under the first step's heading on every tab. */
@Composable
internal fun NfcSpotLink(vm: SeedTagViewModel) {
    Link("Not sure where to hold the tag? Find your NFC spot") { vm.page = Page.Antenna }
}

/** The result of the first Back up step: can this tag take a backup, and how many words. */
@Composable
private fun TagCheckResult(c: TagCheck) {
    val (title, hint) = when {
        !c.supported -> "This tag can't be used" to
            "It doesn't support NDEF, the standard way of storing data on NFC tags. Use another tag (an NTAG215 or NTAG216, for example)."
        !c.writable -> "This tag can't be used" to "It's read-only, so nothing can be written to it. Use another tag."
        c.protected -> "This tag has a write password" to
            "SeedTag gave it a write password, so nothing can be written to it. To use it for a new backup, verify it on " +
            "the Verify tab with its backup's password and tap Remove write password, then check it here again."
        c.tooSmall -> "This tag is too small" to "Use a bigger tag (an NTAG215 or NTAG216, for example)."
        c.classic -> "Better use another tag" to
            "This is a MIFARE Classic tag. Many phones can't read MIFARE Classic at all, so a backup on it may not be " +
            "readable on the phone you have when you need it. Use another tag (an NTAG213 or NTAG215, the chips SeedTag " +
            "has been tested with, for example), or use this one only to practise."
        else -> "This tag works" to null
    }
    val good = c.usable && !c.classic
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val t = colorsOf(if (good) Tone.Success else Tone.Caution)
            Box(Modifier.size(40.dp).background(t.container, CircleShape), contentAlignment = Alignment.Center) {
                Icon(iconOf(if (good) Tone.Success else Tone.Caution), null, tint = t.accent, modifier = Modifier.size(22.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (c.supported && c.writable) {
            Facts(
                buildList {
                    add(
                        Fact(
                            "Room",
                            c.capacity?.let { capacityText(it, c.estimated) } ?: "Unknown – it's checked again when the backup is written.",
                            if (c.tooSmall) Tone.Caution else null,
                        ),
                    )
                    add(Fact("State", if (c.ndef) "Read without problems" else "Blank – it's formatted when the backup is written, then a second tap reads it back"))
                    add(readOnlyFact(c))
                    add(writePasswordFact(c))
                    when (c.content) {
                        TagContent.Backup -> add(Fact("Holds", "A SeedTag backup. A new backup would replace it (the app asks first).", Tone.Caution))
                        TagContent.NewerBackup -> add(
                            Fact(
                                "Holds",
                                "A backup from a newer version of SeedTag, which this version can't read. A new backup " +
                                    "would replace it (the app asks first). Update the app to read it.",
                                Tone.Caution,
                            ),
                        )
                        TagContent.Other -> add(Fact("Holds", "Other data, which the backup would replace (the app asks first).", Tone.Caution))
                        TagContent.Empty -> add(Fact("Holds", "Nothing yet"))
                    }
                },
            )
        }
    }
}

/** Back up's tag check: whether the tag can be made read-only for good (done on the Verify tab). */
private fun readOnlyFact(c: TagCheck): Fact = when (c.canMakeReadOnly) {
    true -> Fact("Read-only", "Can be made read-only for good, on the Verify tab once the backup is verified")
    false -> Fact("Read-only", "Can't be made read-only from this phone")
    null -> Fact("Read-only", "Not known yet: the tag is blank. Check it again once the backup is written.")
}

/** Back up's tag check: whether SeedTag can give the tag a write password (done on the Verify tab). */
private fun writePasswordFact(c: TagCheck): Fact {
    val chip = c.chip
    val id = chip?.identity
    return when {
        chip == null && !c.ndef -> Fact("Write password", "Not known yet: the tag is blank. Check it again once the backup is written.")
        chip == null -> Fact("Write password", "Not known: the chip couldn't be checked. Verify shows it once the backup is written.")
        id == null -> Fact("Write password", NOT_PROTECTABLE)
        c.protected -> Fact("Write password", "On (see above)", Tone.Caution)
        chip.settingsLocked -> Fact("Write password", "Not possible: this tag's settings are locked for good", Tone.Caution)
        !id.genuine -> Fact(
            "Write password",
            "Possible, at your own risk: it's an NTAG21x lookalike, which SeedTag doesn't officially support, and " +
                "setting one may destroy the tag. Set on the Verify tab once the backup is verified.",
            Tone.Caution,
        )
        else -> Fact("Write password", "Possible (${id.chip.label}): set on the Verify tab once the backup is verified")
    }
}

/** How much a tag holds, in words of backup. [estimated]: a blank tag's size, from its chip. */
internal fun capacityText(bytes: Int, estimated: Boolean): String {
    val size = (if (estimated) "about " else "") + "$bytes bytes"
    val max = SeedBox.maxWordsFor(bytes)
    return when (max) {
        null -> "$size – too small for any backup (12 words need ${SeedBox.ndefBytesFor(12)} bytes)."
        24 -> "$size – room for a backup of any length (24 words need ${SeedBox.ndefBytesFor(24)} bytes)."
        else -> "$size – room for a backup of up to $max words (24 words need ${SeedBox.ndefBytesFor(24)} bytes)."
    }
}

@Composable
private fun LengthStep(vm: SeedTagViewModel) {
    StepScreen(bottom = { SecondaryButton("Use another tag", vm::checkAnotherTag) }) {
        StepHeader(2, BACKUP_STEPS, "How many words?", "Choose the number of words in your recovery phrase. The keypad stops once that many are entered.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Bip39.validWordCounts.sorted().forEach { n ->
                // Can't pick fewer words than are already entered (after Back from the keypad), nor
                // more than the checked tag holds.
                LengthTile(n, enabled = n >= vm.words.size && vm.fitsTag(n), Modifier.weight(1f)) { vm.chooseLength(n) }
            }
        }
        vm.tagCheck?.let { c ->
            val max = c.maxWords
            Text(
                when {
                    max != null && max < 24 -> "This tag has room for up to $max words. Longer phrases need a bigger tag."
                    c.capacity == null -> "This tag's size is checked again when the backup is written."
                    else -> "This tag has room for a phrase of any length."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Notice(
            Tone.Caution,
            "Only the words are stored",
            "If your wallet also uses a BIP39 passphrase (a \"25th word\", set in the wallet), SeedTag doesn't store it: " +
                "back it up separately, or the words alone won't restore your funds. SeedTag's own password, set later, is " +
                "something else. Only English BIP39 phrases are supported, not Electrum, SLIP-39 (Shamir) or Monero phrases.",
        )
    }
}

@Composable
private fun LengthTile(n: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(84.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.alpha(if (enabled) 1f else 0.35f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("$n", style = MaterialTheme.typography.headlineSmall)
            Text("words", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WordsStep(vm: SeedTagViewModel) {
    val scroll = rememberScrollState()
    // Keep the word being typed in view as the grid grows.
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    StepScreen(
        scroll = scroll,
        scrollToTop = false,
        bottom = {
            // Fixed height, so the keypad doesn't jump when the suggestions appear or disappear.
            Suggestions(vm.suggestions, vm::pickWord)
            Keypad(onLetter = vm::typeLetter, onBackspace = vm::backspace, onBackspaceHeld = vm::backspaceLetter)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::backToLength) { Text("Back") }
                TextButton(onClick = vm::requestClearWords, enabled = vm.words.isNotEmpty()) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                Button(onClick = vm::toReviewStep, enabled = vm.mnemonicStatus is MnemonicStatus.Valid, modifier = Modifier.height(48.dp)) {
                    Text("Next")
                }
            }
        },
    ) {
        StepHeader(
            3, BACKUP_STEPS, "Enter your ${vm.wordCount} words",
            "Type on the keypad and tap the matching word. The system keyboard and clipboard are never used.",
            compact = true,
        ) {
            Link(if (vm.hideWords) "Show words" else "Hide words") { vm.hideWords = !vm.hideWords }
        }
        // While the words are hidden, so are the letters being typed (the suggestions show the candidates).
        val current = if (vm.hideWords) "•".repeat(vm.prefixLength) else vm.revealPrefix()
        WordGrid(vm.words, current = current, hidden = vm.hideWords, max = vm.wordCount)
        when (val s = vm.mnemonicStatus) {
            is MnemonicStatus.Incomplete -> Text(
                "${s.count} of ${vm.wordCount} words",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is MnemonicStatus.Invalid -> Text(s.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            is MnemonicStatus.Valid -> Text(
                "Valid ${s.count}-word phrase",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalExtra.current.success,
            )
        }
    }
}

/**
 * Check your words (board B6): the whole phrase, to compare with the written seed before it's encrypted. The
 * checksum can't catch a wrong word that's another BIP39 word, and nothing after this step can either: Verify
 * and Restore check the tag against what was entered here. *They match* needs the words shown.
 */
@Composable
private fun ReviewStep(vm: SeedTagViewModel) {
    StepScreen(
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::backToWords) { Text("Fix a word") }
                Spacer(Modifier.weight(1f))
                Button(onClick = vm::toPasswordStep, enabled = !vm.hideWords, modifier = Modifier.height(48.dp)) {
                    Text("They match")
                }
            }
        },
    ) {
        StepHeader(
            4, BACKUP_STEPS, "Check your words",
            "Compare every word with your written seed, one by one and in order, before it's encrypted.",
            compact = true,
        ) {
            Link(if (vm.hideWords) "Show words" else "Hide words") { vm.hideWords = !vm.hideWords }
        }
        WordGrid(vm.words, hidden = vm.hideWords, max = vm.wordCount)
        if (vm.hideWords) {
            Text(
                "Tap Show words to compare them: They match needs them shown. Make sure nobody can see the screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Notice(
            Tone.Info,
            "Why check them?",
            "The phrase's checksum catches most typos, but not all: a wrong word that's another BIP39 word gets " +
                "through about once in 16 times with 12 words, once in 256 with 24.",
            "If one does, the backup holds a different seed. Every check after this would still pass, because Verify " +
                "and Restore compare the tag with what you entered here, so you'd only find out when you need the seed. " +
                "Fix a word goes back to the keypad with every word kept: Backspace removes the last one.",
        )
    }
}

/** Completions above a keyboard; fixed height, so the keys don't jump. */
@Composable
internal fun Suggestions(words: List<String>, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        words.forEach { w ->
            SuggestionChip(
                onClick = { onPick(w) },
                label = { Text(w, style = MaterialTheme.typography.bodyLarge) },
                shape = MaterialTheme.shapes.small,
                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                border = null,
            )
        }
    }
}

/**
 * The seed words, numbered. Three to a row, or fewer when the longest BIP39 word (8 letters, with the typing
 * cursor) wouldn't fit at the user's font size (board B26): a seed word is never clipped. Cells grow with the font.
 */
@Composable
internal fun WordGrid(words: List<String>, current: String? = null, hidden: Boolean = false, max: Int = 24) {
    // Same mask for every word, so it doesn't give away word lengths.
    val cells = words.mapIndexed { i, w -> Triple(i + 1, if (hidden) "••••••" else w, false) } +
        if (current != null && words.size < max) listOf(Triple(words.size + 1, "$current▏", true)) else emptyList()
    val wordStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    val numberStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Monospace: every 8-letter word is as wide as this one.
    val wordWidth = with(density) { measurer.measure("abcdefgh▏", wordStyle).size.width.toDp() }
    val numberWidth = with(density) { maxOf(20.dp, measurer.measure("24", numberStyle).size.width.toDp() + 6.dp) }
    val gap = 6.dp
    BoxWithConstraints {
        val cell = numberWidth + wordWidth + 20.dp // + the horizontal padding
        val perRow = ((maxWidth + gap) / (cell + gap)).toInt().coerceIn(1, 3)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            cells.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { (n, w, typing) ->
                        Row(
                            Modifier.weight(1f)
                                .heightIn(min = 38.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .then(if (typing) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$n",
                                style = numberStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(numberWidth),
                                maxLines = 1,
                            )
                            Text(w, style = wordStyle, maxLines = 1, softWrap = false)
                        }
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PasswordStep(vm: SeedTagViewModel) {
    // A generated passphrase can't be edited (board B30): while it's shown, there's nothing to type into.
    val typing = !(vm.generatedPassphrase && vm.passwordField == PwField.Password)
    StepScreen(
        bottom = {
            if (typing) PasswordKeyboard(vm, vm.passwordField)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::requestBackToReview) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = vm::encryptAndWrite,
                    enabled = vm.passwordLength(PwField.Password) > 0 && vm.passwordsMatch,
                    modifier = Modifier.height(48.dp),
                ) { Text("Encrypt & write tag") }
            }
        },
    ) {
        StepHeader(5, BACKUP_STEPS, "Set a password", compact = true)
        Notice(
            Tone.Caution,
            "This password is the only key",
            "Anyone who finds the tag can try passwords offline, without limit. And if you lose the password, nothing can " +
                "decrypt the tag – not this app, not seedtag.py, not the developer, nobody.",
            "A generated 7-word passphrase (≈77 bits) is strongly recommended. Write it down, label it \"SeedTag " +
                "password\" and store it apart from the tag. There's no reset and no recovery, so keep the written copy " +
                "safe and keep another backup of the seed itself.",
        )
        FilledTonalButton(
            onClick = vm::generatePassphrase,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(if (vm.generatedPassphrase) "Generate a different one" else "Generate a 7-word passphrase")
        }
        // While Repeat is typed, the passphrase is hidden: it's typed back from the written copy, which
        // checks that copy.
        val copying = vm.generatedPassphrase && vm.passwordField == PwField.Repeat
        if (vm.generatedPassphrase) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val on = MaterialTheme.colorScheme.onPrimaryContainer
                    if (copying) {
                        Text("Now type it from your written copy", style = MaterialTheme.typography.labelLarge, color = on)
                        Text(
                            "The passphrase is hidden, so that Repeat password checks what you wrote down, not the screen. " +
                                "If they don't match, your copy has a mistake: correct it before writing the tag. Show it again " +
                                "clears what you've typed here, so you type it from your copy once more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = on.copy(alpha = 0.8f),
                        )
                        Link("Show it again") { vm.showPassphraseAgain() }
                    } else {
                        Text("Write this down now and label it \"SeedTag password\"", style = MaterialTheme.typography.labelLarge, color = on)
                        Text(
                            vm.revealPassword(PwField.Password),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = on,
                        )
                        Text(
                            "It's made of the same kind of words as a seed phrase, but it isn't one. Label it so that no one – " +
                                "neither you nor your heirs – mistakes one for the other, and keep it apart from the tag and the seed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = on.copy(alpha = 0.8f),
                        )
                        Button(onClick = { vm.passwordField = PwField.Repeat }, modifier = Modifier.fillMaxWidth()) {
                            Text("I've written it down")
                        }
                    }
                    Link("Type my own password instead", color = on, onClick = vm::requestOwnPassword)
                }
            }
        }
        // A generated passphrase: no Show (during Repeat it would put it on screen to copy from), and the
        // field can't be selected for typing.
        SecretField(
            "Password",
            vm,
            PwField.Password,
            revealDefault = vm.generatedPassphrase && !copying,
            active = vm.passwordField == PwField.Password && !vm.generatedPassphrase,
            canReveal = !vm.generatedPassphrase,
        ) {
            if (!vm.generatedPassphrase) vm.passwordField = PwField.Password
        }
        if (vm.passwordLength(PwField.Password) > 0) StrengthMeter(vm.passwordBits, vm.generatedPassphrase)
        if (vm.passwordHasStraySpaces) {
            Text(STRAY_SPACES, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        SecretField(
            if (vm.generatedPassphrase) "Repeat password (from your written copy)" else "Repeat password",
            vm,
            PwField.Repeat,
            revealDefault = vm.generatedPassphrase,
            active = vm.passwordField == PwField.Repeat,
        ) {
            vm.passwordField = PwField.Repeat
        }
        if (vm.passwordLength(PwField.Repeat) > 0 && !vm.passwordsMatch) {
            Text("The passwords don't match", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (vm.passwordLength(PwField.Password) > 0 && vm.passwordSharesSeedWords) {
            Notice(Tone.Caution, "The password uses words from your seed", "Anyone who sees the password learns part of the seed. Choose different words.")
        }
    }
}

/**
 * Three bars and a line. Only the generated passphrase ([generated]) is called strong: for a typed
 * password the estimate is an upper bound, since it can't recognise a well-known phrase.
 */
@Composable
private fun StrengthMeter(bits: Int, generated: Boolean) {
    val x = LocalExtra.current
    val (level, msg, color) = when {
        generated -> Triple(3, "Strong · ≈$bits bits · generated at random", x.success)
        bits < 50 -> Triple(1, "Weak · ≈$bits bits · can be cracked offline. Use a passphrase.", MaterialTheme.colorScheme.error)
        else -> Triple(
            2,
            "≈$bits bits at most · SeedTag can't rate a password you chose: well-known phrases, quotes, names " +
                "and dates are much weaker than they look. The generated passphrase is safer.",
            x.fair,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                Box(Modifier.weight(1f).height(4.dp).background(if (i < level) color else MaterialTheme.colorScheme.outlineVariant, CircleShape))
            }
        }
        Text(msg, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun DoneStep(vm: SeedTagViewModel) {
    StepScreen(
        bottom = {
            PrimaryButton("Verify now", { vm.selectTab(Screen.Verify) })
            SecondaryButton(COPY_BUTTON, vm::copyBackup, enabled = vm.writtenText != null)
            SecondaryButton("Back up another seed", vm::startOver)
        },
    ) {
        Hero(Tone.Success, "Backup written", "Written to the tag and checked by reading it back.")
        when (vm.backupRest) {
            Rest.Interrupted -> {
                Notice(
                    Tone.Caution,
                    "Old data may still be on the tag",
                    "The backup is written and checked. After it, in the rest of the tag's memory, whatever the tag held " +
                        "before was being cleared when the tag moved away, so some of it may still be there: an app that " +
                        "shows the tag's whole memory would show it. Tap Clear old data and hold the same tag still.",
                )
                SecondaryButton("Clear old data", vm::clearRest)
            }
            Rest.NotPossible -> Notice(
                Tone.Info,
                "Old data may still be on the tag",
                "Writing a backup leaves whatever the tag held before in the memory after the backup. SeedTag clears it " +
                    "only on NFC Forum Type 2 tags (NTAG, MIFARE Ultralight and compatible chips) whose memory layout it " +
                    "can be sure of. On this tag it couldn't, so the old data may still be there: an app that shows the " +
                    "tag's whole memory would show it. If this tag ever held something nobody should read (a seed in " +
                    "plain text, say), use a new tag instead.",
            )
            Rest.Cleared, null -> Unit
        }
        vm.backupFingerprint?.let {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Facts(
                    listOfNotNull(
                        Fact("Fingerprint", it, mono = true),
                        vm.writtenText?.let(SeedBox::checkCodeOf)?.let { code -> Fact("Check code", code, mono = true) },
                    ),
                )
                Text(
                    "Neither is secret. If the backup is intact, Verify (here or in seedtag.py) shows the same fingerprint. " +
                        "It's SeedTag's own: a wallet may show a fingerprint too, but that's a different number. The check " +
                        "code is the tag's text's: write it on the tag's label or with your written password. If someone " +
                        "ever types the text into seedtag.py, it prints the code before asking for the password, and a " +
                        "different one means a typo, not a wrong password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Notice(
            Tone.Caution,
            "Not finished yet",
            "Verify the tag with the password from your written copy before you rely on it or make it read-only. Keep another " +
                "copy of the seed somewhere else, too – NFC chips can fail.",
        )
        SectionLabel("Next steps")
        Steps(
            listOf(
                "Go to Verify and decrypt the tag by typing the password from your written copy.",
                "Once, on the Restore tab, show the words and compare them with your written seed again: that checks " +
                    "the whole round trip, tag included.",
                "Optionally, recover it on an offline computer with seedtag.py.",
                "Keep a copy of seedtag.py (printed, or on a USB stick) with your written password: with Python, " +
                    "it's all anyone needs to recover the tag, even if SeedTag and its website are gone.",
                "Only then make the tag read-only (on the Verify tab – it's permanent) and keep another backup of the seed somewhere else.",
                "For a second tag kept somewhere else, tap $COPY_BUTTON: the same backup, the same password.",
            ),
        )
        CloseSection(vm)
    }
}

/** Close SeedTag where a procedure ends: Done, and the Verify and Restore results. */
@Composable
internal fun CloseSection(vm: SeedTagViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("When you're done")
        Text(
            "Close SeedTag clears everything and ends the app, together with the copies in memory that leaving it " +
                "can't clear. Then restart the phone before it goes back online.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecondaryButton(CLOSE_SEEDTAG, vm::requestClose, enabled = !vm.tagWorking)
    }
}

/** Permanent and irreversible, so it looks it: outlined in red, unlike every other button. */
@Composable
internal fun LockButton(vm: SeedTagViewModel) {
    DangerButton("Make tag read-only", vm::requestLock)
}
