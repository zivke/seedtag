// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import rs.zivke.seedtag.core.Bip39
import rs.zivke.seedtag.core.Passphrase
import rs.zivke.seedtag.core.PasswordFix

// ============================================================== theme ===

private val Amber = Color(0xFFF2B84B)

@Composable
fun SeedTagTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Amber, onPrimary = Color(0xFF1B1F24))
        else -> lightColorScheme(primary = Color(0xFF8A5A00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ================================================================ app ===

/** CONFIRM and REJECT are Android 11+; older versions get a plain long-press buzz. */
private val Haptic.constant: Int
    get() = when (this) {
        Haptic.Tick -> HapticFeedbackConstants.KEYBOARD_TAP
        Haptic.Success ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        Haptic.Failure ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeedTagApp(vm: SeedTagViewModel, nfcState: NfcState, overlayDetected: Boolean, openNfcSettings: () -> Unit) {
    val view = LocalView.current
    LaunchedEffect(vm) { vm.haptics.collect { view.performHapticFeedback(it.constant) } }
    val page = vm.page
    BackHandler(enabled = page != null) { vm.page = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "SeedTag") },
                navigationIcon = { if (page != null) TextButton(onClick = { vm.page = null }) { Text("‹ Back") } },
                actions = {
                    if (page == null) {
                        Page.entries.forEach { p -> TextButton(onClick = { vm.page = p }) { Text(p.button) } }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (page == null) {
                TabRow(selectedTabIndex = vm.tab.ordinal) {
                    Screen.entries.forEach { t ->
                        Tab(selected = vm.tab == t, onClick = { vm.tab = t }, text = { Text(t.title) })
                    }
                }
            }
            NfcBanner(nfcState, openNfcSettings)
            if (overlayDetected) OverlayBanner()
            when (page) {
                Page.Antenna -> AntennaPage(vm)
                Page.About -> AboutPage()
                null -> when (vm.tab) {
                    Screen.Backup -> BackupScreen(vm)
                    Screen.Verify -> ReadScreen(vm, reveal = false)
                    Screen.Restore -> ReadScreen(vm, reveal = true)
                }
            }
        }
    }
    Dialogs(vm)
}

@Composable
private fun NfcBanner(state: NfcState, openNfcSettings: () -> Unit) {
    if (state == NfcState.Ready) return
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state == NfcState.Missing) "This device has no NFC." else "NFC is turned off.",
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (state == NfcState.Disabled) TextButton(onClick = openNfcSettings) { Text("Turn on") }
        }
    }
}

@Composable
private fun OverlayBanner() {
    WarningCard(
        "Another app is covering SeedTag",
        "Your taps are ignored while it does. Close it, or turn off apps such as screen dimmers or chat bubbles, " +
            "then tap again. If you don't know which app it is, don't enter your seed.",
        Modifier.padding(12.dp),
    )
}

/**
 * Every warning looks the same, and unlike anything else on screen: red card, red border, ⚠ and a
 * bold title, so it isn't skimmed over like the explanatory text around it.
 */
@Composable
private fun WarningCard(title: String, text: String, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error),
    ) {
        Row(Modifier.padding(12.dp)) {
            Text("⚠", fontSize = 24.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** Plain guidance that matters before a step, quieter than a warning. */
@Composable
private fun InfoCard(title: String, text: String, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Where the tag goes, said before any procedure asks for it. */
@Composable
private fun PrepareTagInfo(writing: Boolean) {
    InfoCard(
        "Have your NFC tag ready",
        (if (writing) "You'll need the tag at the end, after the password. " else "You'll need the tag after typing the password. ") +
            "When the app asks, hold it flat against the back of the phone, over the phone's NFC spot (usually near " +
            "the middle or the top), and keep it still until the app says it's done. A thick or metal case can block it. " +
            "Not sure where the spot is? Tap NFC spot at the top first.",
    )
}

/** Icon and title for dialogs that confirm something risky or permanent. */
@Composable
private fun WarningIcon() {
    Text("⚠", fontSize = 32.sp, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun WarningTitle(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
}

/**
 * Dialogs are windows of their own, so the activity's guards don't cover them: drop touches that
 * come through another app's overlay, and hide overlays on Android 12+.
 */
@Composable
private fun GuardDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.rootView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (view.parent as? DialogWindowProvider)?.window?.setHideOverlayWindows(true)
        }
        onDispose {}
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

// ============================================================= back up ===

@Composable
private fun BackupScreen(vm: SeedTagViewModel) {
    when (vm.step) {
        BackupStep.Length -> LengthStep(vm)
        BackupStep.Words -> WordsStep(vm)
        BackupStep.Password -> PasswordStep(vm)
        BackupStep.Done -> DoneStep(vm)
    }
}

/** Shown at the start of every procedure (Back up, Verify, Restore). */
@Composable
private fun ReadCarefullyWarning() {
    WarningCard(
        "Read every instruction carefully",
        "Each screen says exactly what to do. Read all of it before you tap: some steps, such as overwriting or " +
            "locking a tag, can't be undone.",
    )
}

@Composable
private fun LengthStep(vm: SeedTagViewModel) {
    ScreenColumn {
        ReadCarefullyWarning()
        PrepareTagInfo(writing = true)
        Text("1 · How many words is your recovery phrase?", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick the length written on your backup. The keypad then stops at that many words.",
            style = MaterialTheme.typography.bodySmall,
        )
        Bip39.validWordCounts.sorted().forEach { n ->
            // Can't pick fewer words than are already entered (after Back from the keypad).
            OutlinedButton(
                onClick = { vm.chooseLength(n) },
                modifier = Modifier.fillMaxWidth(),
                enabled = n >= vm.words.size,
            ) { Text("$n words") }
        }
    }
}

@Composable
private fun WordsStep(vm: SeedTagViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Only the words scroll; suggestions, keypad and buttons stay fixed at the bottom.
        val scroll = rememberScrollState()
        // Keep the word being typed in view as the grid grows.
        LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("2 · Enter your ${vm.wordCount} recovery words", style = MaterialTheme.typography.titleMedium)
            Text(
                "Type with the keypad below and tap the matching word. The system keyboard and clipboard are never used.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hide entered words", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = vm.hideWords, onCheckedChange = { vm.hideWords = it })
            }
            WordGrid(vm.words, current = vm.prefix, hidden = vm.hideWords, max = vm.wordCount)

            when (val s = vm.mnemonicStatus) {
                is MnemonicStatus.Incomplete -> Text("${s.count} of ${vm.wordCount} words", style = MaterialTheme.typography.bodyMedium)
                is MnemonicStatus.Invalid -> Text("✗ ${s.reason}", color = MaterialTheme.colorScheme.error)
                is MnemonicStatus.Valid -> Text("✓ Valid ${s.count}-word mnemonic", color = MaterialTheme.colorScheme.primary)
            }
        }

        // Fixed height, so the keypad doesn't jump when the suggestions appear or disappear.
        Row(
            Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            vm.suggestions.forEach { w ->
                SuggestionChip(onClick = { vm.pickWord(w) }, label = { Text(w) })
            }
        }
        Keypad(onLetter = vm::typeLetter, onBackspace = vm::backspace)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::backToLength) { Text("Back") }
            OutlinedButton(onClick = vm::clearWords, enabled = vm.words.isNotEmpty()) { Text("Clear") }
            Spacer(Modifier.weight(1f))
            Button(onClick = vm::toPasswordStep, enabled = vm.mnemonicStatus is MnemonicStatus.Valid) { Text("Next") }
        }
    }
}

@Composable
private fun WordGrid(words: List<String>, current: String? = null, hidden: Boolean = false, max: Int = 24) {
    // Same mask for every word, so it doesn't give away word lengths.
    val cells = words.mapIndexed { i, w -> "${i + 1}. ${if (hidden) "••••••" else w}" } +
        if (current != null && words.size < max) listOf("${words.size + 1}. $current▏") else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { c ->
                    Text(
                        c,
                        Modifier.weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Keypad(onLetter: (Char) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (i == 1) Spacer(Modifier.weight(0.5f))
                r.forEach { c -> Key(c.toString(), Modifier.weight(1f)) { onLetter(c) } }
                if (i == 1) Spacer(Modifier.weight(0.5f))
                if (i == 2) Key("⌫", Modifier.weight(1.5f), onClick = onBackspace)
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    val view = LocalView.current
    Box(
        modifier
            .height(44.dp)
            .background(bg, RoundedCornerShape(6.dp))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, color = fg, maxLines = 1)
    }
}

@Composable
private fun PasswordStep(vm: SeedTagViewModel) {
    var field by remember { mutableStateOf(PwField.Password) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Only the top part scrolls; the keyboard and buttons stay fixed at the bottom.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("3 · Choose the encryption password", style = MaterialTheme.typography.titleMedium)
            WarningCard(
                "This password is the only thing protecting your seed",
                "Anyone who finds the tag can try passwords offline, without limit. A generated 7-word passphrase " +
                    "(≈77 bits) is strongly recommended. Write it down, label it \"SeedTag password\" and store it apart from the tag.",
            )
            WarningCard(
                "Lose this password and the seed is gone",
                "Without it, nothing can decrypt the tag: not this app, not seedtag.py, not the developer, nobody. " +
                    "There's no reset and no recovery. Keep the written copy safe, and keep another backup of the seed itself.",
            )
            OutlinedButton(onClick = vm::generatePassphrase, modifier = Modifier.fillMaxWidth()) {
                Text("Generate 7-word passphrase")
            }
            if (vm.generatedPassphrase) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Write this down now, labelled \"SeedTag password\":", style = MaterialTheme.typography.labelMedium)
                        Text(vm.revealPassword(PwField.Password), fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "It's made of the same kind of words as a seed phrase, but it isn't one. Label it so that " +
                                "nobody, you or your heirs, mistakes one for the other, and keep it apart from the tag and the seed.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            SecretDisplay("Password", vm, PwField.Password, vm.generatedPassphrase, active = field == PwField.Password) {
                field = PwField.Password
            }
            SecretDisplay("Repeat password", vm, PwField.Repeat, vm.generatedPassphrase, active = field == PwField.Repeat) {
                field = PwField.Repeat
            }

            if (vm.passwordLength(PwField.Password) > 0) {
                val bits = vm.passwordBits
                val (msg, color) = when {
                    bits < 50 -> "Weak (≈$bits bits) – crackable offline. Use a passphrase." to MaterialTheme.colorScheme.error
                    bits < 70 -> "Fair (≈$bits bits) – consider a longer passphrase." to Color(0xFFC77700)
                    else -> "Strong (≈$bits bits)" to MaterialTheme.colorScheme.primary
                }
                Text(msg, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (vm.passwordSharesSeedWords) {
                    WarningCard(
                        "The password uses words from your seed",
                        "Anyone who sees the password learns part of the seed. Choose different words.",
                    )
                }
            }
            if (vm.passwordLength(PwField.Repeat) > 0 && !vm.passwordsMatch) {
                Text("Passwords don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }

        PasswordKeyboard(vm, field)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::backToWords) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = vm::encryptAndWrite,
                enabled = vm.passwordLength(PwField.Password) > 0 && vm.passwordsMatch,
            ) { Text("Encrypt & write tag") }
        }
    }
}

@Composable
private fun DoneStep(vm: SeedTagViewModel) {
    ScreenColumn {
        Text("✓ Written and read back", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        vm.backupFingerprint?.let {
            Text("Fingerprint", style = MaterialTheme.typography.labelMedium)
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 22.sp)
            Text(
                "Not secret. Verify on this phone or with seedtag.py shows the same value if the backup is intact.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        WarningCard(
            "Not finished yet",
            "Verify the tag with the password from your written copy before you rely on it or lock it. Keep another " +
                "copy of the seed somewhere else: NFC chips can fail.",
        )
        Text("Next steps", style = MaterialTheme.typography.titleSmall)
        Text(
            "1. Go to Verify and decrypt the tag by typing the password from your written copy.\n" +
                "2. Optionally recover it on an offline computer with seedtag.py.\n" +
                "3. Only then lock the tag (permanent, on the Verify tab) and keep another backup of the seed elsewhere.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { vm.tab = Screen.Verify }, modifier = Modifier.fillMaxWidth()) { Text("Verify now") }
        TextButton(onClick = vm::startOver, modifier = Modifier.fillMaxWidth()) { Text("Back up another seed") }
    }
}

/** Permanent and irreversible, so it looks it: red, unlike every other button. */
@Composable
private fun LockButton(vm: SeedTagViewModel) {
    Button(
        onClick = vm::requestLock,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Make tag read-only", fontWeight = FontWeight.SemiBold) }
}

// ===================================================== verify / restore ===

/**
 * Verify and Restore are two steps, like Back up: 1 · type the password and scan, 2 · the result.
 * The password field and keyboard are gone once the password has done its job.
 */
@Composable
private fun ReadScreen(vm: SeedTagViewModel, reveal: Boolean) {
    val result = vm.verifyResult?.takeIf { it.restored == reveal }
    if (result == null) ReadPasswordStep(vm, reveal) else ReadResultStep(vm, reveal, result)
}

@Composable
private fun ReadPasswordStep(vm: SeedTagViewModel, reveal: Boolean) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Only the guidance scrolls; the password, keyboard and Scan button stay fixed at the bottom.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (reveal) "Restore: decrypt and show the words" else "Verify: check the tag without showing the words",
                style = MaterialTheme.typography.titleMedium,
            )
            ReadCarefullyWarning()
            if (reveal) {
                WarningCard(
                    "Only do this in private",
                    "The words will be on screen. Make sure nobody, and no camera, can see it. They're hidden again as " +
                        "soon as you leave the app.",
                )
            } else {
                VerifyExplanation()
            }
            PrepareTagInfo(writing = false)
            Text("1 · Type the password, then scan the tag", style = MaterialTheme.typography.titleSmall)
        }
        SecretDisplay("Password", vm, PwField.Read, revealDefault = false, active = true) {}
        PasswordKeyboard(vm, PwField.Read)
        Button(
            onClick = if (reveal) vm::scanForRestore else vm::scanForVerify,
            enabled = vm.passwordLength(PwField.Read) > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan tag") }
    }
}

@Composable
private fun ReadResultStep(vm: SeedTagViewModel, reveal: Boolean, result: VerifyResult) {
    ScreenColumn {
        Text(if (reveal) "2 · Restored" else "2 · Verified", style = MaterialTheme.typography.titleMedium)
        val differs = result.matchesBackup == false
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (differs) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("✓ Decrypted: ${result.wordCount}-word seed, checksum OK", fontWeight = FontWeight.SemiBold)
                Text("Fingerprint: ${result.fingerprint}", fontFamily = FontFamily.Monospace)
                passwordFixNote(result.passwordFix)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                when (result.matchesBackup) {
                    true -> Text("✓ Matches the backup you just wrote", fontWeight = FontWeight.SemiBold)
                    false -> Text(
                        "✗ A different seed from the backup you just wrote (${vm.backupFingerprint}). Check you scanned the right tag.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                    null -> Unit
                }
            }
        }
        if (reveal) {
            val words = vm.restoredWords
            if (words != null) {
                WordGrid(words)
                Button(
                    onClick = vm::hideRestored,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Hide words") }
            } else {
                Text(
                    "The words are hidden. To see them again, restore the tag again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            VerifyResultExplanation(result)
            LockButton(vm)
        }
        OutlinedButton(onClick = vm::readAnother, modifier = Modifier.fillMaxWidth()) {
            Text(if (reveal) "Restore another tag" else "Verify another tag")
        }
    }
}

/** What Verify does and why, before the user starts. */
@Composable
private fun VerifyExplanation() {
    InfoCard(
        "What Verify does",
        "It proves the tag can be read back, that your written password decrypts it, and that it holds a valid " +
            "seed, without ever showing the words.\n\n" +
            "1. Type the password from your written copy, not from memory. That checks the copy too.\n" +
            "2. Tap Scan tag and hold the tag to the phone. The app reads it, decrypts it and checks the seed's checksum.\n" +
            "3. You get ✓ and a fingerprint. Right after a backup, it also says whether the tag holds the seed you just wrote.\n\n" +
            "Do it after every backup, before locking the tag, and again every now and then (say once a year) to make " +
            "sure the tag still reads.",
    )
}

/** What a successful Verify means, and what to do with the fingerprint. */
@Composable
private fun VerifyResultExplanation(result: VerifyResult) {
    InfoCard(
        "What this means",
        "The tag reads correctly, and the password you typed decrypts it to a valid ${result.wordCount}-word seed. " +
            "The fingerprint is the same for the same seed on any tag, with any password, and in seedtag.py; it isn't " +
            "secret and doesn't reveal the words. Compare it with the one shown after the backup: if they match, " +
            "this tag holds that seed.\n\n" +
            "Once you're sure, you can make the tag read-only below. That's permanent.",
    )
}

/** Says what had to be ignored in the typed password, so the user can fix their written copy. */
private fun passwordFixNote(fix: PasswordFix): String? {
    val ignored = when (fix) {
        PasswordFix.None -> return null
        PasswordFix.Spaces -> "extra spaces"
        PasswordFix.Case -> "capital letters"
        PasswordFix.SpacesAndCase -> "extra spaces and capital letters"
    }
    return "The password only matched with $ignored ignored. Check that your written copy shows it exactly as it was set."
}

/**
 * Shows a password; it is never an editable text field. Without a text field the system keyboard
 * can't open, so no keyboard app sees what's typed, and there's nothing to copy, paste or autofill.
 */
@Composable
private fun SecretDisplay(
    label: String,
    vm: SeedTagViewModel,
    field: PwField,
    revealDefault: Boolean,
    active: Boolean,
    onSelect: () -> Unit,
) {
    var reveal by remember(revealDefault) { mutableStateOf(revealDefault) }
    val accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth()
            .border(if (active) 2.dp else 1.dp, accent, RoundedCornerShape(6.dp))
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = accent)
            // Masked, only the length is read; the text itself only while it's shown.
            val shown = if (reveal) vm.revealPassword(field) else "•".repeat(vm.passwordLength(field))
            Text(shown + if (active) "▏" else "", fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        }
        TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
    }
}

/**
 * In-app keyboard for passwords, with BIP39 completions (the recommended passphrase is BIP39 words).
 * It only sends key presses to the view model, which edits the password buffer in place.
 */
@Composable
private fun PasswordKeyboard(vm: SeedTagViewModel, field: PwField) {
    // Fixed height, so the keyboard doesn't jump when the suggestions appear or disappear.
    Row(
        Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        vm.passwordSuggestions(field).forEach { w ->
            SuggestionChip(onClick = { vm.completePasswordWord(field, w) }, label = { Text(w) })
        }
    }
    TextKeyboard(
        onChar = { c -> vm.typePassword(field, c) },
        onBackspace = { vm.backspacePassword(field) },
    )
}

private enum class KeyLayer { Letters, Symbols, More, Accents }
private enum class Shift { Off, Once, Locked }

private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm,.")
private val SYMBOL_ROWS = listOf("1234567890", "@#$%&-+()/", "*\"':;!?,.")
private val MORE_ROWS = listOf("1234567890", "[]{}<>^_=~", "`|\\€£¥§°")
private val ACCENT_ROWS = listOf("áàâäãåąæçč", "ćđéèêëěęíî", "ïłñńňóôöõø", "œřśšßťúùûü", "ìòůýÿźżž")

/**
 * Full keyboard: letters with shift, digits and all printable ASCII symbols, and common Latin
 * accented letters. Characters outside these sets can't be typed in the app.
 */
@Composable
private fun TextKeyboard(onChar: (Char) -> Unit, onBackspace: () -> Unit) {
    var layer by remember { mutableStateOf(KeyLayer.Letters) }
    var shift by remember { mutableStateOf(Shift.Off) }
    val shiftable = layer == KeyLayer.Letters || layer == KeyLayer.Accents
    fun type(c: Char) {
        if (shiftable && shift != Shift.Off && c.isLetter()) {
            onChar(c.uppercaseChar())
            if (shift == Shift.Once) shift = Shift.Off
        } else {
            onChar(c)
        }
    }
    @Composable
    fun CharKey(c: Char, modifier: Modifier) {
        val label = if (shiftable && shift != Shift.Off && c.isLetter()) c.uppercaseChar() else c
        Key(label.toString(), modifier) { type(c) }
    }
    @Composable
    fun ShiftKey(modifier: Modifier) {
        Key(if (shift == Shift.Locked) "⇪" else "⇧", modifier, active = shift != Shift.Off) {
            shift = when (shift) { Shift.Off -> Shift.Once; Shift.Once -> Shift.Locked; Shift.Locked -> Shift.Off }
        }
    }
    @Composable
    fun LayerKey(label: String, target: KeyLayer, modifier: Modifier) {
        Key(label, modifier) { layer = target }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val rows = when (layer) {
            KeyLayer.Letters -> LETTER_ROWS
            KeyLayer.Symbols -> SYMBOL_ROWS
            KeyLayer.More -> MORE_ROWS
            KeyLayer.Accents -> ACCENT_ROWS
        }
        rows.forEachIndexed { i, r ->
            val lastRow = i == rows.lastIndex
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (lastRow) when (layer) {
                    KeyLayer.Letters, KeyLayer.Accents -> ShiftKey(Modifier.weight(1.5f))
                    KeyLayer.Symbols -> LayerKey("=\\<", KeyLayer.More, Modifier.weight(1.5f))
                    KeyLayer.More -> LayerKey("?123", KeyLayer.Symbols, Modifier.weight(1.5f))
                }
                if (r.length < 10 && !lastRow) Spacer(Modifier.weight((10 - r.length) / 2f))
                r.forEach { c -> CharKey(c, Modifier.weight(1f)) }
                if (r.length < 10 && !lastRow) Spacer(Modifier.weight((10 - r.length) / 2f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (layer == KeyLayer.Letters) LayerKey("?123", KeyLayer.Symbols, Modifier.weight(1.5f))
            else LayerKey("ABC", KeyLayer.Letters, Modifier.weight(1.5f))
            if (layer == KeyLayer.Accents) LayerKey("?123", KeyLayer.Symbols, Modifier.weight(1.5f))
            else LayerKey("àé", KeyLayer.Accents, Modifier.weight(1.5f))
            Key("space", Modifier.weight(5f)) { onChar(' ') }
            Key("⌫", Modifier.weight(1.5f), onClick = onBackspace)
        }
    }
}

// ======================================================= find NFC spot ===

@Composable
private fun AntennaPage(vm: SeedTagViewModel) {
    DisposableEffect(vm) {
        vm.startProbe()
        onDispose { vm.stopProbe() }
    }
    ScreenColumn {
        Text("Find your phone's NFC spot", style = MaterialTheme.typography.titleMedium)
        Text(
            "Every phone reads tags at one small spot on its back. Find it now, so you know where to hold the tag " +
                "when the app asks.\n\n" +
                "Take your NFC tag, or any contactless card, and slide it slowly over the back of the phone. When the " +
                "phone vibrates and the box below says Tag detected, you've found the spot. Nothing is written to the tag, " +
                "and its contents aren't read.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val p = vm.probe
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (p != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (p == null) {
                    Text("Waiting for a tag…", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(
                        "✓ Tag detected" + if (p.count > 1) " (${p.count} times)" else "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("That's the spot. Lift the tag away and back to try again.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            p.ndef && p.writable == false -> "This tag is read-only: SeedTag can read it but not write to it."
                            p.ndef -> "This tag can hold NDEF data" + (p.ndefSize?.let { " ($it bytes)" } ?: "") + ": SeedTag can use it."
                            p.formatable -> "This tag is blank: SeedTag will format it on the first write."
                            else -> "This isn't an NDEF tag: SeedTag can't use it, but it's fine for finding the spot."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        InfoCard(
            "Tips",
            "• The spot is usually in the upper half of the back, near the camera, or in the middle. On some phones " +
                "it's at the very top edge.\n" +
                "• Hold the tag flat against the phone, not edge-on, and keep it still for a moment.\n" +
                "• Thick or metal cases, magnetic mounts, ring holders and card wallets can block it: take them off.\n" +
                "• Small tags and rings have small antennas and must sit right on the spot.\n" +
                "• If nothing happens anywhere, check that NFC is turned on.",
        )
    }
}

// =============================================================== about ===

@Composable
private fun AboutPage() {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
    ScreenColumn {
        Text("SeedTag", style = MaterialTheme.typography.headlineSmall)
        Text("Version ${version ?: "unknown"}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Stores a password-encrypted BIP39 seed phrase on an NFC tag, and reads it back: Back up writes it, " +
                "Verify checks it without showing the words, Restore shows them.",
            style = MaterialTheme.typography.bodyMedium,
        )
        AboutSection(
            "Privacy",
            "SeedTag works entirely offline. It has no internet permission, so it can't send anything anywhere, " +
                "and no analytics or crash reporting. Its only permissions are NFC and hiding other apps' overlays. " +
                "It stores nothing on the phone: everything typed or decrypted is wiped when you leave the app.",
        )
        AboutSection(
            "Protection on the phone",
            "Seed words and passwords are typed on the app's own keyboards, never the system one. Screenshots and " +
                "screen recording are blocked, other apps' overlays are hidden (Android 12+), and taps that come " +
                "through another app's window are ignored.",
        )
        AboutSection(
            "Encryption",
            "Argon2id (64 MiB, 3 passes) turns the password into a key, and AES-256-GCM encrypts the seed's raw " +
                "entropy. The format is documented and versioned. seedtag.py, a single-file Python tool, encrypts " +
                "and decrypts the same format on any computer, so a backup doesn't depend on this app.",
        )
        AboutSection(
            "Licence",
            "Free and open source under the GNU General Public License, version 3 or later: you may use, share and " +
                "change it, but anything you distribute that's based on it must be released under the same licence, " +
                "with its source. seedtag.py is under the BSD Zero Clause licence, so anyone can always use it to " +
                "recover a tag. © 2026 zivke85.",
        )
    }
}

@Composable
private fun AboutSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ============================================================= dialogs ===

@Composable
private fun Dialogs(vm: SeedTagViewModel) {
    vm.busy?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(msg)
                }
            },
        )
        return
    }

    vm.scanPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelScan,
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Ready to scan") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(p.title, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Hold it flat against the back of the phone and keep it still until this window closes.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    p.error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelScan) { Text("Cancel") } },
        )
        return
    }

    when (val d = vm.dialog) {
        null -> Unit
        is Dialog.Error -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { WarningTitle("Error") },
            text = { Text(d.message) },
            confirmButton = { TextButton(onClick = { vm.dialog = null }) { Text("OK") } },
        )
        is Dialog.Info -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text(d.title) },
            text = { Text(d.message) },
            confirmButton = { TextButton(onClick = { vm.dialog = null }) { Text("OK") } },
        )
        is Dialog.ConfirmOverwrite -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { WarningTitle("Tag is not empty") },
            text = {
                GuardDialogWindow()
                Text(
                    if (d.looksLikeSeedTag) "This tag already holds a SeedTag backup. Overwriting destroys it. Continue?"
                    else "This tag already contains data. Overwriting replaces it. Continue?",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmOverwrite) { Text("Overwrite", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Cancel") } },
        )
        is Dialog.ConfirmWeakPassword -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { WarningTitle("Weak password") },
            text = {
                GuardDialogWindow()
                val parts = buildList {
                    if (d.sharesSeedWords) {
                        add("The password uses words from the seed phrase itself. Anyone who sees the password learns part of your seed.")
                    }
                    if (d.bits < Passphrase.MIN_BITS) {
                        add(
                            "This password is estimated at only about ${d.bits} bits. Anyone who gets the tag can try " +
                                "passwords offline, without limit, and a password this weak may be guessed.",
                        )
                    }
                    add("A generated 7-word passphrase is about 77 bits.")
                }
                Text(parts.joinToString("\n\n"))
            },
            confirmButton = {
                TextButton(onClick = vm::confirmWeakPassword) { Text("Write anyway", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Change password") } },
        )
        Dialog.ConfirmLock -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { WarningTitle("Make tag read-only?") },
            text = {
                GuardDialogWindow()
                Text(
                    "This is permanent: the tag can never be rewritten or erased. " +
                        "Only do it after you have successfully verified the backup with your written password.",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmLock) { Text("Lock forever", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Cancel") } },
        )
    }
}
