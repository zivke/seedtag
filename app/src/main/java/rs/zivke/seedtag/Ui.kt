// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import rs.zivke.seedtag.core.Haptic

// ================================================================ app ===

/** CONFIRM and REJECT are Android 11+; older versions get a plain long-press buzz. */
private val Haptic.constant: Int
    get() = when (this) {
        Haptic.Tick -> HapticFeedbackConstants.KEYBOARD_TAP
        Haptic.Success ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        Haptic.Failure ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        Haptic.Found -> HapticFeedbackConstants.LONG_PRESS
        Haptic.Detected -> HapticFeedbackConstants.KEYBOARD_TAP
    }

/** Plays [h]: [Haptic.Found] and [Haptic.Detected] as a real vibration, the rest as the system's own touch feedback. */
private fun View.play(h: Haptic) {
    if (h == Haptic.Found && buzz(context, longArrayOf(0, 150, 90, 250))) return
    if (h == Haptic.Detected && buzz(context, longArrayOf(0, 70))) return
    performHapticFeedback(h.constant)
}

/**
 * Firm pulses at full strength (where the motor can vary it): [timings] alternate off and on, in ms,
 * starting with off. Found: 150 and 250 ms, so a found tag is felt through the hand holding the phone;
 * Detected: one short 70 ms pulse. Played as touch feedback, so it follows the phone's
 * touch-vibration setting. False if the phone has no vibrator.
 */
private fun buzz(context: Context, timings: LongArray): Boolean {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }
    if (vibrator == null || !vibrator.hasVibrator()) return false
    val effect = if (vibrator.hasAmplitudeControl()) {
        VibrationEffect.createWaveform(timings, IntArray(timings.size) { if (it % 2 == 1) 255 else 0 }, -1)
    } else {
        VibrationEffect.createWaveform(timings, -1)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
    } else {
        vibrator.vibrate(effect)
    }
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeedTagApp(
    vm: SeedTagViewModel,
    nfcState: NfcState,
    airplaneMode: Boolean,
    overlayDetected: Boolean,
    openSettings: (SettingsPage) -> Unit,
    debugging: Boolean = false,
    accessibilityApps: List<String> = emptyList(),
    accessibilityOn: Boolean = false,
    paused: Boolean = false,
    hideAccessibilityBanner: () -> Unit = {},
    leave: () -> Unit = {},
    close: () -> Unit = {},
) {
    // Settings is another app: going there stops SeedTag, which wipes what was entered. Ask first.
    val settings: (SettingsPage) -> Unit = { p -> if (vm.holdsSecrets) vm.dialog = Dialog.ConfirmSettings(p) else openSettings(p) }
    val openNfcSettings = { settings(SettingsPage.Nfc) }
    val view = LocalView.current
    LaunchedEffect(vm) { vm.haptics.collect { view.play(it) } }
    val page = vm.page
    // Otherwise Back is the system's: it leaves the app, which has nothing to lose then.
    val backStaysInApp by remember(vm) { derivedStateOf { vm.backStaysInApp } } // not every keystroke
    BackHandler(enabled = backStaysInApp) { vm.back() }
    IdleWatch(vm)
    // While words or a password are being typed or checked, the mode switch is hidden: one task on screen.
    val typing = vm.tab == Screen.Backup && vm.step in setOf(BackupStep.Words, BackupStep.Review, BackupStep.Password) ||
        // Erase's password check: its keyboard and buttons leave little room on a small phone (board B46).
        vm.tab == Screen.Erase && vm.typingErasePassword
    // Each time the last prompt or dialog closes, the screen behind it scrolls back to the top, so
    // what changed (a tag check result, an error's context) is in view.
    val prompting = vm.busy != null || vm.scanPrompt != null || vm.dialog != null
    var actionsDone by remember { mutableIntStateOf(0) }
    LaunchedEffect(prompting) { if (!prompting) actionsDone++ }
    // What's behind some prompts (words, a password) is blurred while they're up: "Still there?" (nobody
    // is watching the screen, which is kept on), the two Back questions, the Settings, tab-switch and
    // clear-words questions, the progress dialog and "Ready to scan" (the phone is held to a tag, often in view of
    // others). Blur needs Android 12; before that, it's covered.
    // And while SeedTag is paused but may still be seen, with words or a password in it (board B25).
    val pausedVeil = paused && vm.holdsSecrets
    val veiled = pausedVeil || vm.busy != null || vm.scanPrompt != null ||
        vm.dialog.let {
            it == Dialog.StillThere || it is Dialog.ConfirmBack || it is Dialog.ConfirmSettings || it is Dialog.ConfirmTab ||
                it is Dialog.ConfirmClearWords
        }
    val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Veiled, it's out of a screen reader's reach as well: only the question in front of it is read (board B28).
    Box(if (veiled) Modifier.clearAndSetSemantics {}.then(if (blurs) Modifier.blur(20.dp) else Modifier) else Modifier) {
        CompositionLocalProvider(
            LocalAirplaneMode provides airplaneMode,
            LocalDebugging provides debugging,
            LocalOpenDeveloperSettings provides { settings(SettingsPage.Developer) },
            LocalActionsDone provides actionsDone,
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Header(
                        page = page,
                        onBack = { vm.page = null },
                        onAbout = { vm.page = Page.About },
                        onClose = vm::requestClose,
                        closeEnabled = !vm.tagWorking,
                    )
                },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    if (page == null && !typing) ModeSwitch(vm)
                    // The NFC spot page says it itself, as its first step.
                    // Where the words are on screen: entered, checked, or restored (board B23).
                    val wordsOnScreen = page == null && (
                        vm.tab == Screen.Restore ||
                            (vm.tab == Screen.Backup && (vm.step == BackupStep.Words || vm.step == BackupStep.Review))
                        )
                    Banners(
                        if (page == Page.Antenna) NfcState.Ready else nfcState, openNfcSettings, overlayDetected, accessibilityApps,
                        { settings(SettingsPage.Accessibility) }, hideAccessibilityBanner,
                        speaking = accessibilityOn && accessibilityApps.isEmpty() && wordsOnScreen,
                    )
                    when (page) {
                        Page.Antenna -> AntennaPage(vm, nfcState, openNfcSettings)
                        Page.About -> AboutPage()
                        null -> when (vm.tab) {
                            Screen.Backup -> BackupScreen(vm)
                            Screen.Verify -> ReadScreen(vm, reveal = false)
                            Screen.Restore -> ReadScreen(vm, reveal = true)
                            Screen.Erase -> EraseScreen(vm)
                        }
                    }
                }
            }
        }
        if (veiled && !blurs) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
    if (pausedVeil) {
        Box(Modifier.fillMaxSize().padding(Gutter), contentAlignment = Alignment.Center) {
            Notice(
                Tone.Info,
                "SeedTag is in the background",
                "Another window is in front of SeedTag, so its screen is hidden and it doesn't read tags. Go back to " +
                    "SeedTag to continue. If nothing is touched, what you entered is cleared after a few minutes.",
            )
        }
    }
    Dialogs(vm, leave, openSettings, close)
}

/**
 * While secrets are in memory: the screen stays on (a screen timeout would stop the app and wipe them
 * mid-entry), and the app's own idle timeout runs instead, paused while Argon2 works. Its own
 * composable, so a touch only recomposes this.
 */
@Composable
private fun IdleWatch(vm: SeedTagViewModel) {
    val view = LocalView.current
    val holding by remember(vm) { derivedStateOf { vm.holdsSecrets } }
    DisposableEffect(holding) {
        view.keepScreenOn = holding
        onDispose { view.keepScreenOn = false }
    }
    // Not idle while Argon2 runs or a tag is being read or written.
    val working = vm.busy != null || vm.scanPrompt?.working != null
    LaunchedEffect(holding, vm.touches, working) {
        if (!holding || working) return@LaunchedEffect
        delay(IDLE_WARN_MS)
        vm.idleWarning()
        delay(IDLE_GRACE_MS)
        vm.idleTimeout()
    }
}

/** Whether airplane mode is on, for [Preflight]. */
internal val LocalAirplaneMode = compositionLocalOf { false }

/** USB or wireless debugging is on (read from Settings.Global, no permission). */
internal val LocalDebugging = compositionLocalOf { false }
internal val LocalOpenDeveloperSettings = staticCompositionLocalOf<() -> Unit> { {} }

/** Counts closed prompts and dialogs; [StepScreen] scrolls to the top when it changes. */
internal val LocalActionsDone = compositionLocalOf { 0 }

/** A text style whose line box is just the font's height, centered, so the letters sit on a row's center line. */
private fun TextStyle.centered() =
    copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

/**
 * The top bar: one row, so the icon, the title, ⓘ and ✕ Close SeedTag (or the back arrow, a page's title
 * and ✕) all share the same vertical center. The NFC-spot finder is a link on each tab ([NfcSpotLink]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Header(
    page: Page?,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit,
    closeEnabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page != null) {
            IconButton(onClick = onBack) { Icon(ImageVector.vectorResource(R.drawable.ic_arrow_back), "Back") }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(12.dp))
            AppIcon()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            page?.title ?: "SeedTag",
            style = MaterialTheme.typography.titleLarge.centered(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (page == null) {
            IconButton(onClick = onAbout) { Icon(iconOf(Tone.Info), Page.About.title) }
        }
        IconButton(onClick = onClose, enabled = closeEnabled) {
            Icon(ImageVector.vectorResource(R.drawable.ic_close), CLOSE_SEEDTAG)
        }
    }
}

/** The launcher icon, small: its foreground on the icon's own background, cropped like a launcher does. */
@Composable
private fun AppIcon() {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF121418)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(30.dp).scale(1.45f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(vm: SeedTagViewModel) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp)) {
        Screen.entries.forEachIndexed { i, t ->
            SegmentedButton(
                selected = vm.tab == t,
                onClick = { vm.selectTab(t) },
                shape = SegmentedButtonDefaults.itemShape(i, Screen.entries.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onSurface,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    activeBorderColor = MaterialTheme.colorScheme.outline,
                    inactiveBorderColor = MaterialTheme.colorScheme.outline,
                ),
            ) { Text(t.title, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Device-level problems, above whatever is on screen. */
@Composable
private fun Banners(
    nfcState: NfcState,
    openNfcSettings: () -> Unit,
    overlayDetected: Boolean,
    accessibilityApps: List<String>,
    openAccessibilitySettings: () -> Unit,
    hideAccessibilityBanner: () -> Unit,
    speaking: Boolean = false,
) {
    val any = nfcState != NfcState.Ready || overlayDetected || accessibilityApps.isNotEmpty() || speaking
    if (!any) return
    Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (nfcState != NfcState.Ready) {
            Notice(
                Tone.Caution,
                if (nfcState == NfcState.Missing) "This device has no NFC" else "NFC is turned off",
                if (nfcState == NfcState.Missing) "SeedTag can't read or write tags on it." else "SeedTag needs it to read and write tags.",
            ) {
                if (nfcState == NfcState.Disabled) Link("Turn on NFC", onClick = openNfcSettings)
            }
        }
        if (overlayDetected) {
            Notice(
                Tone.Caution,
                "Another app is covering SeedTag",
                "SeedTag ignores your taps while it's there. Close it, or turn off apps such as screen dimmers or " +
                    "chat bubbles, then try again. If you don't know which app it is, don't enter your seed.",
            )
        }
        if (accessibilityApps.isNotEmpty()) AccessibilityBanner(accessibilityApps, openAccessibilitySettings, hideAccessibilityBanner)
        // Preinstalled services the banner above leaves out: neutral, since they're the phone's own (board B23).
        if (speaking) {
            Notice(
                Tone.Info,
                "Accessibility is on",
                "A screen reader or another accessibility feature of this phone is on (TalkBack or Select to Speak, " +
                    "say). It may read the words aloud, and passes what it reads to a text-to-speech app. If you don't " +
                    "need it now, turn it off before you enter or restore a seed.",
            ) {
                Link("Open settings", onClick = openAccessibilitySettings)
            }
        }
    }
}

/** Apps with accessibility access can read the screen and see every tap, seed words included. */
@Composable
private fun AccessibilityBanner(apps: List<String>, openSettings: () -> Unit, hide: () -> Unit) {
    val names = apps.joinToString(", ")
    val text = "$names ${if (apps.size == 1) "has" else "have"} accessibility access, which lets an app read the " +
        "screen and see what you tap. If you don't recognize " + (if (apps.size == 1) "it" else "one of them") +
        " or don't need it, turn it off in Settings › Accessibility before you enter your seed." +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            " SeedTag hides its screen from apps that aren't accessibility tools, but a malicious app can pretend to be one."
        } else {
            " On this Android version, SeedTag can't hide its screen from them."
        }
    Notice(Tone.Caution, "Apps that can read your screen", text) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Link("Open settings", onClick = openSettings)
            Link("Hide", onClick = hide)
        }
    }
}
