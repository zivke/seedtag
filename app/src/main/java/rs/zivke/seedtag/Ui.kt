// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag

import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

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
fun SeedTagApp(vm: SeedTagViewModel, nfcState: NfcState, openNfcSettings: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("SeedTag") }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            TabRow(selectedTabIndex = vm.tab.ordinal) {
                Screen.entries.forEach { t ->
                    Tab(selected = vm.tab == t, onClick = { vm.tab = t }, text = { Text(t.title) })
                }
            }
            NfcBanner(nfcState, openNfcSettings)
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
        BackupStep.Words -> WordsStep(vm)
        BackupStep.Password -> PasswordStep(vm)
        BackupStep.Done -> DoneStep(vm)
    }
}

@Composable
private fun WordsStep(vm: SeedTagViewModel) {
    ScreenColumn {
        Text("1 · Enter your recovery words", style = MaterialTheme.typography.titleMedium)
        Text(
            "Type with the keypad below and tap the matching word. The system keyboard and clipboard are never used.",
            style = MaterialTheme.typography.bodySmall,
        )
        WordGrid(vm.words, current = vm.prefix)

        when (val s = vm.mnemonicStatus) {
            is MnemonicStatus.Incomplete -> Text("${s.count} words", style = MaterialTheme.typography.bodyMedium)
            is MnemonicStatus.Invalid -> Text("✗ ${s.reason}", color = MaterialTheme.colorScheme.error)
            is MnemonicStatus.Valid -> Text("✓ Valid ${s.count}-word mnemonic", color = MaterialTheme.colorScheme.primary)
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.suggestions.forEach { w ->
                SuggestionChip(onClick = { vm.pickWord(w) }, label = { Text(w) })
            }
        }
        Keypad(onLetter = vm::typeLetter, onBackspace = vm::backspace)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::clearWords, enabled = vm.words.isNotEmpty()) { Text("Clear") }
            Spacer(Modifier.weight(1f))
            Button(onClick = vm::toPasswordStep, enabled = vm.mnemonicStatus is MnemonicStatus.Valid) { Text("Next") }
        }
    }
}

@Composable
private fun WordGrid(words: List<String>, current: String? = null) {
    val cells = words.mapIndexed { i, w -> "${i + 1}. $w" } +
        if (current != null && words.size < 24) listOf("${words.size + 1}. $current▏") else emptyList()
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
                if (i == 2) Key("⌫", Modifier.weight(1.5f), onBackspace)
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun PasswordStep(vm: SeedTagViewModel) {
    ScreenColumn {
        Text("2 · Choose the encryption password", style = MaterialTheme.typography.titleMedium)
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
                    Text(vm.password, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        SecretField("Password", vm.password, revealDefault = vm.generatedPassphrase) { vm.password = it; vm.onPasswordEdited() }
        SecretField("Repeat password", vm.passwordRepeat, revealDefault = vm.generatedPassphrase) { vm.passwordRepeat = it; vm.onPasswordEdited() }

        if (vm.password.isNotEmpty()) {
            val bits = vm.passwordBits
            val (msg, color) = when {
                bits < 50 -> "Weak (≈$bits bits) – crackable offline. Use a passphrase." to MaterialTheme.colorScheme.error
                bits < 70 -> "Fair (≈$bits bits) – consider a longer passphrase." to Color(0xFFC77700)
                else -> "Strong (≈$bits bits)" to MaterialTheme.colorScheme.primary
            }
            Text(msg, color = color, style = MaterialTheme.typography.bodySmall)
        }
        if (vm.passwordRepeat.isNotEmpty() && vm.password != vm.passwordRepeat) {
            Text("Passwords don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::backToWords) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = vm::encryptAndWrite,
                enabled = vm.password.isNotEmpty() && vm.password == vm.passwordRepeat,
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
                "3. Only then lock the tag (permanent) and keep another backup of the seed elsewhere.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { vm.tab = Screen.Verify }, modifier = Modifier.fillMaxWidth()) { Text("Verify now") }
        OutlinedButton(onClick = vm::requestLock, modifier = Modifier.fillMaxWidth()) { Text("Make tag read-only…") }
        TextButton(onClick = vm::startOver, modifier = Modifier.fillMaxWidth()) { Text("Back up another seed") }
    }
}

// ===================================================== verify / restore ===

@Composable
private fun ReadScreen(vm: SeedTagViewModel, reveal: Boolean) {
    ScreenColumn {
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
        SecretField("Password", vm.readPassword, revealDefault = false, imeAction = ImeAction.Done) { vm.readPassword = it }
        Button(
            onClick = if (reveal) vm::scanForRestore else vm::scanForVerify,
            enabled = vm.readPassword.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan tag") }

        val result = vm.verifyResult
        if (result != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("✓ Decrypted: ${result.wordCount}-word seed, checksum OK", fontWeight = FontWeight.SemiBold)
                    Text("Fingerprint: ${result.fingerprint}", fontFamily = FontFamily.Monospace)
                }
            }
            if (!reveal) {
                OutlinedButton(onClick = vm::requestLock, modifier = Modifier.fillMaxWidth()) { Text("Make tag read-only…") }
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
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    revealDefault: Boolean,
    imeAction: ImeAction = ImeAction.Next,
    onChange: (String) -> Unit,
) {
    var reveal by remember(revealDefault) { mutableStateOf(revealDefault) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        // KeyboardType.Password: keyboards don't learn, suggest or store what's typed here.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") } },
    )
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
        Dialog.ConfirmLock -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Make tag read-only?") },
            text = {
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
