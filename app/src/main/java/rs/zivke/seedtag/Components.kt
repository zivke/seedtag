// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.os.Build
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch

// ========================================================= components ===

internal enum class Tone { Info, Caution, Success }

internal class ToneColors(val container: Color, val accent: Color, val content: Color)

@Composable
internal fun colorsOf(tone: Tone): ToneColors {
    val c = MaterialTheme.colorScheme
    val x = LocalExtra.current
    return when (tone) {
        Tone.Info -> ToneColors(c.surfaceContainer, c.onSurfaceVariant, c.onSurface)
        Tone.Caution -> ToneColors(c.errorContainer, c.error, c.onErrorContainer)
        Tone.Success -> ToneColors(x.successContainer, x.success, x.onSuccessContainer)
    }
}

/** The icons are Material Symbols, copied into res/drawable (Apache-2.0; source in each file). */
@Composable
internal fun iconOf(tone: Tone): ImageVector = ImageVector.vectorResource(
    when (tone) {
        Tone.Info -> R.drawable.ic_info
        Tone.Caution -> R.drawable.ic_warning
        Tone.Success -> R.drawable.ic_check_circle
    },
)

/**
 * A message that matters before a step. Caution (risky, permanent, or a security risk) is red with
 * a hairline red border and a warning icon, the only red cards in the app, so it's never mistaken
 * for explanation. [details] fold away behind "Learn more" so a screen stays short.
 */
@Composable
internal fun Notice(
    tone: Tone,
    title: String,
    text: String? = null,
    details: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val t = colorsOf(tone)
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = t.container,
        border = if (tone == Tone.Caution) BorderStroke(1.dp, t.accent.copy(alpha = 0.45f)) else null,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(iconOf(tone), null, tint = t.accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = t.content)
                text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = t.content.copy(alpha = 0.86f)) }
                details?.let { Expandable(it, t.content.copy(alpha = 0.86f), if (tone == Tone.Info) MaterialTheme.colorScheme.primary else t.accent) }
                action?.invoke()
            }
        }
    }
}

/** The tag's message isn't exactly what SeedTag writes: what's different, when that's fine, and when it isn't. */
@Composable
internal fun TagDiffersNotice(differences: List<String>) {
    val list = if (differences.size == 1) differences[0] else differences.dropLast(1).joinToString(", ") + " and " + differences.last()
    Notice(
        Tone.Caution,
        "This tag isn't exactly as SeedTag writes it",
        "SeedTag writes one Text record with the backup's text and nothing else. This tag has $list. The backup " +
            "still opened.\n\nThat's expected if the tag was written or changed with another app: for example, " +
            "text from seedtag.py encrypt written with an NFC app. If nobody did that, whatever wrote this tag " +
            "wasn't a genuine SeedTag, and it may have hidden your seed on the tag for someone who reads it later. " +
            "Then treat the seed as exposed: move your funds to a new seed, and back that up with a SeedTag you trust.",
    )
}

/** What to check when a password doesn't open a tag, most likely cause first. */
internal fun wrongPasswordText(d: Dialog.WrongPassword): String {
    val words = d.nonWords
    val hint = when {
        words == null -> ""
        words.isEmpty() -> " Every word is a BIP39 word, so a mistyped word would be another real word: compare each one."
        else -> {
            val list = if (words.size == 1) "${words[0]}" else words.dropLast(1).joinToString(", ") + " and ${words.last()}"
            if (words.size == 1) {
                " Word $list isn't a BIP39 word. A generated passphrase uses only BIP39 words, so if yours is one, " +
                    "check that word first."
            } else {
                " Words $list aren't BIP39 words. A generated passphrase uses only BIP39 words, so if yours is one, " +
                    "check those first."
            }
        }
    }
    val intro = "Either it isn't the password this backup was written with, or the tag's data is damaged. SeedTag " +
        "already tried it without extra spaces and in lower case.\n\n" +
        "1. Tap Show and compare the password with your written copy, word by word and letter by letter.$hint\n\n"
    return intro + if (d.erase) {
        "2. Maybe this backup was written with another password. If you're sure it's the backup you mean to " +
            "erase, you can erase it without checking the password.\n\n" +
            "3. Not sure? Try the password with seedtag.py on a computer before you erase anything (README, " +
            "\"Recovering on a computer\")."
    } else {
        "2. Check that this is the right tag: another tag may have another password.\n\n" +
            "3. Still no luck? Try seedtag.py on a computer (README, \"Recovering on a computer\"). If the right " +
            "password fails there too, this tag's data is damaged: restore from another backup of your seed."
    }
}

/** "Learn more" that reveals [text] in place. */
@Composable
private fun ColumnScope.Expandable(text: String, textColor: Color, linkColor: Color) {
    var open by remember { mutableStateOf(false) }
    AnimatedVisibility(open) { Text(text, style = MaterialTheme.typography.bodyMedium, color = textColor) }
    Link(if (open) "Show less" else "Learn more", color = linkColor) { open = !open }
}

@Composable
internal fun Link(text: String, color: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

/** One line of a checklist: like a [Notice], without its own card. */
private class Item(
    val tone: Tone,
    val title: String,
    val text: String,
    val details: String? = null,
    val action: Pair<String, () -> Unit>? = null,
)

/** A titled group of [Item]s in one quiet card, divided by hairlines. */
@Composable
private fun Checklist(title: String, items: List<Item>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(title)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChecklistRow(item)
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(item: Item) {
    val t = colorsOf(item.tone)
    val caution = item.tone == Tone.Caution
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(iconOf(item.tone), null, tint = t.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (caution) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(item.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.details?.let { Expandable(it, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.primary) }
            item.action?.let { (label, onClick) -> Link(label, onClick = onClick) }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "Step 2 of 4", a segmented progress bar, the title and a one-line explanation. */
@Composable
internal fun StepHeader(
    step: Int,
    total: Int,
    title: String,
    subtitle: String? = null,
    compact: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Step $step of $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier.weight(1f).height(3.dp).background(
                        if (i < step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
                )
            }
        }
        Text(title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * Every screen: content that scrolls, and an optional [bottom] area that stays put (the main
 * button, or a keyboard and its buttons), under a hairline.
 */
@Composable
internal fun StepScreen(
    scroll: ScrollState = rememberScrollState(),
    scrollToTop: Boolean = true,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val actionsDone = LocalActionsDone.current
    LaunchedEffect(actionsDone) { if (scrollToTop) scroll.animateScrollTo(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = Gutter, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = content,
            )
            ScrollHints(scroll)
        }
        if (bottom != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = bottom,
            )
        }
    }
}

/**
 * Shows that a screen goes on beyond what's visible, e.g. above a keyboard: a fade at the edge with
 * more content, and at the bottom a ↓ button that scrolls most of a screen further.
 */
@Composable
private fun BoxScope.ScrollHints(scroll: ScrollState) {
    val bg = MaterialTheme.colorScheme.background
    val scope = rememberCoroutineScope()
    AnimatedVisibility(scroll.canScrollBackward, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth().height(16.dp).background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0f)))))
    }
    AnimatedVisibility(scroll.canScrollForward, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxWidth().height(48.dp).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .padding(bottom = 6.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { scope.launch { scroll.animateScrollBy(scroll.viewportSize * 0.8f) } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_keyboard_arrow_down),
                    "More below: scroll down",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = enabled) { Text(text) }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        enabled = enabled,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { Text(text, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Permanent or destructive: outlined in red, unlike every other button. */
@Composable
internal fun DangerButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(text) }
}

/** Big status mark, title and one line under it: the top of a result screen. */
@Composable
internal fun Hero(tone: Tone, title: String, subtitle: String) {
    val t = colorsOf(tone)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(72.dp).background(t.container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(iconOf(tone), null, tint = t.accent, modifier = Modifier.size(36.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Label / value lines in one quiet card. [Fact.tone] colors the value (null: plain). */
internal class Fact(val label: String, val value: String, val tone: Tone? = null, val mono: Boolean = false)

@Composable
internal fun Facts(facts: List<Fact>) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            facts.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(f.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        f.value,
                        style = if (f.mono) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        fontFamily = if (f.mono) FontFamily.Monospace else null,
                        color = when (f.tone) {
                            Tone.Caution -> MaterialTheme.colorScheme.error
                            Tone.Success -> LocalExtra.current.success
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/** A numbered list of short steps. */
@Composable
internal fun Steps(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                }
                Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * What to know before a procedure, as one checklist: going offline (live airplane-mode state),
 * having the tag ready, reading carefully, the procedure's own point, and the disclaimer.
 */
@Composable
internal fun Preflight(vm: SeedTagViewModel, flow: Flow) {
    val restart = "tap Close SeedTag (✕, top right) and restart the phone before it goes back online. Some spyware " +
        "doesn't survive a restart and loses whatever it captured."
    val offline = if (LocalAirplaneMode.current) {
        Item(
            Tone.Info,
            "Airplane mode is on",
            "Check that Wi-Fi and Bluetooth are off too – they can be turned on even in airplane mode.",
            "When you're done, $restart",
        )
    } else {
        Item(
            Tone.Caution,
            "Go offline first",
            "SeedTag can't go online, but other apps on this phone can – and malware could send out whatever you type or see here.",
            "On a spare phone that's never online, nothing can be sent out anyway. On your everyday phone, the " +
                "following reduces the risk but can't remove it completely: restart the phone, turn on airplane mode and check that Wi-Fi " +
                "and Bluetooth are off (NFC normally stays on). When you're done, $restart That stops malware that " +
                "sends data out right away, but not malware that survives the restart and sends what it caught later: " +
                "only a phone that's never online protects against that.",
        )
    }
    val items = buildList {
        add(
            Item(
                Tone.Caution,
                "Read every screen carefully",
                "Each one says exactly what to do. Read all of it, including every Learn more, before you actually " +
                    "do anything – the details matter. Some steps, such as overwriting a tag or making it read-only, can't be " +
                    "undone.",
            ),
        )
        add(Item(Tone.Caution, SPARE_PHONE_TITLE, SPARE_PHONE_SUMMARY, SPARE_PHONE_DETAILS))
        if (flow == Flow.Restore) {
            add(
                Item(
                    Tone.Caution,
                    "Only do this in private",
                    "The words will be shown on screen. Make sure no one – and no camera – can see it.",
                    "They're hidden again as soon as you leave the app.",
                ),
            )
        }
        add(offline)
        if (LocalDebugging.current) {
            add(
                Item(
                    Tone.Caution,
                    "USB or wireless debugging is on",
                    "A computer this phone was ever allowed to debug can silently replace SeedTag with a look-alike or " +
                        "turn on an accessibility service" +
                        (if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) ", and on this Android version it can also " +
                            "mirror the screen, SeedTag's included" else "") +
                        ". Don't use this phone for a real seed while debugging is on.",
                    "In Developer options, turn off USB debugging and wireless debugging, and tap Revoke USB " +
                        "debugging authorizations.",
                    "Open Developer options" to LocalOpenDeveloperSettings.current,
                ),
            )
        }
        add(
            Item(
                Tone.Info,
                "Hold the tag to the phone only when asked",
                (if (flow == Flow.Backup) "The app will ask for it twice – once now to check it and once at the end to write the backup. " else "The app will ask for it once, after you've typed the password. ") +
                    "You don't need to hold it while you read or type. When asked, hold it flat over the phone's NFC " +
                    "spot and keep it still until the app says it's done. The app only reads or writes the tag while " +
                    "it's held there.",
                "The spot is usually near the middle or the top of the back. A thick or metal case can block it. If " +
                    "the tag moves away too early, nothing is lost – just hold it there again. Small tags and rings have a " +
                    "small NFC symbol on them; put that side right against the spot.",
                "Find where to hold it" to { vm.page = Page.Antenna },
            ),
        )
        add(
            Item(
                Tone.Info,
                "Nothing is kept",
                "Everything you type or decrypt is cleared as soon as you leave SeedTag, and nothing is ever saved on " +
                    "the phone. If you leave halfway through, you'll have to type it again.",
                CLOSE_DETAILS,
            ),
        )
        if (flow == Flow.Verify) {
            add(
                Item(
                    Tone.Info,
                    "What Verify does",
                    "It checks that the tag can be read, that your written password opens it and that it holds a valid " +
                        "seed – without ever showing the words.",
                    "Type the password from your written copy, not from memory – that checks the copy too. You'll get ✓ " +
                        "and a fingerprint, and right after a backup, it also tells you whether the tag holds the seed you " +
                        "just wrote. Verify after every backup, before you make the tag read-only and again every now and then (say, " +
                        "once a year) to make sure the tag still reads.",
                ),
            )
        }
        if (flow == Flow.Backup) {
            add(Item(Tone.Caution, "Use at your own risk", "There's no warranty, and it's up to you to make sure this phone is free of malware.", DISCLAIMER))
        }
    }
    Checklist("Before you start", items)
}

internal enum class Flow { Backup, Verify, Restore }

/**
 * Dialogs are windows of their own, so the activity's guards don't cover them: drop touches that
 * come through another app's overlay (or, on Android 10–11, come while one covers part of the
 * dialog), hide overlays on Android 12+, and on Android 14+ keep
 * accessibility services that aren't accessibility tools out, as MainActivity does.
 */
@Composable
internal fun GuardDialogWindow() {
    val view = LocalView.current
    // In composition, not in an effect: done before the dialog's first frame is drawn (board B43).
    remember(view) {
        // Loud, not silent: without its window, none of this would hide anything.
        val window = checkNotNull((view.parent as? DialogWindowProvider)?.window) { "GuardDialogWindow outside a dialog" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        // Android 10–11 can't hide overlays: also drop touches while one covers part of the dialog.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
            val callback = window.callback
            if (callback != null && callback !is ObscuredTouchFilter) window.callback = ObscuredTouchFilter(callback)
        }
        // The dialog's own view as well as its root, in case a child doesn't inherit it.
        for (v in setOf(view, view.rootView)) {
            v.filterTouchesWhenObscured = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                v.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
            }
            v.excludeFromCaptureAndAutofill()
        }
        true
    }
}
