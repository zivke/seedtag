// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag

import android.os.Build
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeedTagApp(vm: SeedTagViewModel, nfcState: NfcState, overlayDetected: Boolean, openNfcSettings: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("SeedTag") }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = vm.tab.ordinal) {
                Screen.entries.forEach { t ->
                    Tab(selected = vm.tab == t, onClick = { vm.tab = t }, text = { Text(t.title) })
                }
            }
            NfcBanner(nfcState, openNfcSettings)
            if (overlayDetected) OverlayBanner()
            when (vm.tab) {
                Screen.Backup -> BackupScreen(vm)
                Screen.Verify -> ReadScreen(vm, reveal = false)
                Screen.Restore -> ReadScreen(vm, reveal = true)
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
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            "Another app is drawing over SeedTag, so your taps are ignored. Close it, or turn off apps such as " +
                "screen dimmers or chat bubbles, then tap again. If you don't know which app it is, don't enter your seed.",
            Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
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

@Composable
private fun LengthStep(vm: SeedTagViewModel) {
    ScreenColumn {
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
    Box(
        modifier
            .height(44.dp)
            .background(bg, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
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
            Text(
                "Anyone who finds the tag can try passwords offline, so this password is the only thing protecting your seed. " +
                    "A generated 7-word passphrase (≈77 bits) is strongly recommended. Write it down and store it apart from the tag.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = vm::generatePassphrase, modifier = Modifier.fillMaxWidth()) {
                Text("Generate 7-word passphrase")
            }
            if (vm.generatedPassphrase) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Write this down now:", style = MaterialTheme.typography.labelMedium)
                        Text(vm.revealPassword(PwField.Password), fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
                Text(msg, color = color, style = MaterialTheme.typography.bodySmall)
                if (vm.passwordSharesSeedWords) {
                    Text(
                        "Uses words from your seed phrase: anyone who sees the password learns part of the seed.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (vm.passwordLength(PwField.Repeat) > 0 && !vm.passwordsMatch) {
                Text("Passwords don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun ReadScreen(vm: SeedTagViewModel, reveal: Boolean) {
    // The keyboard is out of the way once there's a result; tap the field to bring it back.
    var typing by remember { mutableStateOf(true) }
    val decrypted = vm.verifyResult != null
    LaunchedEffect(decrypted) { if (decrypted) typing = false }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (reveal) "Restore: decrypt and show the words" else "Verify: check the tag without showing the words",
                style = MaterialTheme.typography.titleMedium,
            )
            if (reveal) {
                Text(
                    "Only do this in private. The words are hidden again as soon as you leave the app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SecretDisplay("Password", vm, PwField.Read, revealDefault = false, active = typing) { typing = true }

            val result = vm.verifyResult
            if (result != null) {
                val differs = result.matchesBackup == false
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (differs) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("✓ Decrypted: ${result.wordCount}-word seed, checksum OK", fontWeight = FontWeight.SemiBold)
                        Text("Fingerprint: ${result.fingerprint}", fontFamily = FontFamily.Monospace)
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
                if (!reveal) {
                    LockButton(vm)
                }
            }
            val words = vm.restoredWords
            if (reveal && words != null) {
                WordGrid(words)
                Button(
                    onClick = vm::hideRestored,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Hide words") }
            }
        }

        if (typing) PasswordKeyboard(vm, PwField.Read)
        Button(
            onClick = if (reveal) vm::scanForRestore else vm::scanForVerify,
            enabled = vm.passwordLength(PwField.Read) > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan tag") }
    }
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
                    Text(p.title, textAlign = TextAlign.Center)
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
            title = { Text("Error") },
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
            title = { Text("Tag is not empty") },
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
            title = { Text("Weak password") },
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
            title = { Text("Make tag read-only?") },
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
