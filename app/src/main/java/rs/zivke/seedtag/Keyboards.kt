// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ========================================================== keyboards ===

@Composable
internal fun Keypad(onLetter: (Char) -> Unit, onBackspace: () -> Unit, onBackspaceHeld: () -> Boolean) {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (i == 1) Spacer(Modifier.weight(0.5f))
                if (i == 2) Spacer(Modifier.weight(1.5f))
                r.forEach { c -> Key(c.toString(), Modifier.weight(1f)) { onLetter(c) } }
                if (i == 1) Spacer(Modifier.weight(0.5f))
                if (i == 2) Key("⌫", Modifier.weight(1.5f), kind = KeyKind.Function, onHold = onBackspaceHeld, onClick = onBackspace)
            }
        }
    }
}

private enum class KeyKind { Char, Function, Active }

/** A held key waits this long, then repeats [Key]'s `onHold` every [KEY_REPEAT_MS]. */
private const val KEY_HOLD_MS = 400L
private const val KEY_REPEAT_MS = 60L

/**
 * One keyboard key. A tap runs [onClick] on release. With [onHold], holding the key runs it after
 * [KEY_HOLD_MS] and then every [KEY_REPEAT_MS] for as long as it returns true; a release after that
 * isn't also a tap.
 */
@Composable
private fun Key(
    label: String,
    modifier: Modifier,
    kind: KeyKind = KeyKind.Char,
    onHold: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val bg = when (kind) {
        KeyKind.Char -> c.surfaceContainerHighest
        KeyKind.Function -> c.surfaceContainerHigh
        KeyKind.Active -> c.primary
    }
    val fg = if (kind == KeyKind.Active) c.onPrimary else c.onSurface
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    var repeated by remember { mutableStateOf(false) }
    if (onHold != null) {
        val hold by rememberUpdatedState(onHold)
        val pressed by interaction.collectIsPressedAsState()
        LaunchedEffect(pressed) {
            if (!pressed) return@LaunchedEffect
            repeated = false
            delay(KEY_HOLD_MS)
            while (hold()) {
                repeated = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                delay(KEY_REPEAT_MS)
            }
        }
    }
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                if (repeated) {
                    repeated = false
                    return@clickable
                }
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = if (label.length > 1 && kind != KeyKind.Char) 14.sp else 18.sp, color = fg, maxLines = 1)
    }
}

/**
 * Shows a password; it is never an editable text field. Without a text field the system keyboard
 * can't open, so no keyboard app sees what's typed, and there's nothing to copy, paste or autofill.
 * Without [canReveal] there's no Show / Hide: it's shown exactly when [revealDefault] says.
 */
@Composable
internal fun SecretField(
    label: String,
    vm: SeedTagViewModel,
    field: PwField,
    revealDefault: Boolean,
    active: Boolean,
    canReveal: Boolean = true,
    onSelect: () -> Unit,
) {
    var chosen by remember(revealDefault) { mutableStateOf(revealDefault) }
    val reveal = if (canReveal) chosen else revealDefault
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) c.primary else c.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(c.surfaceContainer)
                .border(if (active) 1.5.dp else 1.dp, if (active) c.primary else c.outlineVariant, MaterialTheme.shapes.small)
                .clickable(onClick = onSelect)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Masked, only the length is read; the text itself only while it's shown.
            val shown = if (reveal) vm.revealPassword(field) else "•".repeat(vm.passwordLength(field))
            Text(
                shown + if (active) "▏" else "",
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            if (canReveal) TextButton(onClick = { chosen = !chosen }) { Text(if (reveal) "Hide" else "Show") }
        }
    }
}

/**
 * In-app keyboard for passwords, with BIP39 completions (the recommended passphrase is BIP39 words).
 * It only sends key presses to the view model, which edits the password buffer in place.
 */
@Composable
internal fun PasswordKeyboard(vm: SeedTagViewModel, field: PwField) {
    Suggestions(vm.passwordSuggestions(field)) { w -> vm.completePasswordWord(field, w) }
    TextKeyboard(
        onChar = { c -> vm.typePassword(field, c) },
        onBackspace = { vm.backspacePassword(field) },
        onBackspaceHeld = { vm.backspacePasswordHeld(field) },
    )
}

private enum class KeyLayer { Letters, Symbols, More, Accents }
private enum class Shift { Off, Once, Locked }

private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm,.")
private val SYMBOL_ROWS = listOf("1234567890", "@#$%&-+()/", "*\"':;!?,.")
private val MORE_ROWS = listOf("1234567890", "[]{}<>^_=~", "`|\\€£¥§°")
private val ACCENT_ROWS = listOf("áàâäãåąæçč", "ćđéèêëěęíî", "ïłñńňóôöõø", "œřśšßťúùûü", "ìòůýÿźżž")

/**
 * Widens a full-width element into the screen's side gutters, leaving [KeyboardEdge] on each side: ten
 * keys to a row are narrow, and every dp of key width helps someone typing a long passphrase.
 */
private fun Modifier.intoGutter() = layout { measurable, constraints ->
    val extra = ((Gutter - KeyboardEdge) * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

private val KeyboardEdge = 4.dp

/**
 * Full keyboard: letters with shift, digits and all printable ASCII symbols, and common Latin
 * accented letters. Characters outside these sets can't be typed in the app.
 */
@Composable
private fun TextKeyboard(onChar: (Char) -> Unit, onBackspace: () -> Unit, onBackspaceHeld: () -> Boolean) {
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
        Key(if (shift == Shift.Locked) "⇪" else "⇧", modifier, kind = if (shift != Shift.Off) KeyKind.Active else KeyKind.Function) {
            shift = when (shift) { Shift.Off -> Shift.Once; Shift.Once -> Shift.Locked; Shift.Locked -> Shift.Off }
        }
    }
    @Composable
    fun LayerKey(label: String, target: KeyLayer, modifier: Modifier) {
        Key(label, modifier, kind = KeyKind.Function) { layer = target }
    }

    Column(Modifier.intoGutter(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val rows = when (layer) {
            KeyLayer.Letters -> LETTER_ROWS
            KeyLayer.Symbols -> SYMBOL_ROWS
            KeyLayer.More -> MORE_ROWS
            KeyLayer.Accents -> ACCENT_ROWS
        }
        rows.forEachIndexed { i, r ->
            val lastRow = i == rows.lastIndex
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (layer == KeyLayer.Letters) LayerKey("?123", KeyLayer.Symbols, Modifier.weight(1.5f))
            else LayerKey("ABC", KeyLayer.Letters, Modifier.weight(1.5f))
            if (layer == KeyLayer.Accents) LayerKey("?123", KeyLayer.Symbols, Modifier.weight(1.5f))
            else LayerKey("àé", KeyLayer.Accents, Modifier.weight(1.5f))
            Key("space", Modifier.weight(5f), kind = KeyKind.Function) { onChar(' ') }
            Key("⌫", Modifier.weight(1.5f), kind = KeyKind.Function, onHold = onBackspaceHeld, onClick = onBackspace)
        }
    }
}
