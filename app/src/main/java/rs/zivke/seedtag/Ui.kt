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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rs.zivke.seedtag.core.Bip39
import rs.zivke.seedtag.core.EraseCheck
import rs.zivke.seedtag.core.Haptic
import rs.zivke.seedtag.core.Passphrase
import rs.zivke.seedtag.core.PasswordFix
import rs.zivke.seedtag.core.Protection
import rs.zivke.seedtag.core.Rest
import rs.zivke.seedtag.core.SeedBox
import rs.zivke.seedtag.core.TagCheck
import rs.zivke.seedtag.core.TagContent
import rs.zivke.seedtag.core.TagInfo
import rs.zivke.seedtag.core.TagStep

// ============================================================== theme ===
//
// One fixed palette, matching the icon: ink surfaces, a single amber accent, red only for what is
// risky or permanent, green only for a result that's good. No dynamic (wallpaper) colors, so the
// app looks the same on every phone and its warnings always look like warnings.

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2B84B),
    onPrimary = Color(0xFF241A05),
    primaryContainer = Color(0xFF3A2D12),
    onPrimaryContainer = Color(0xFFF8DDA4),
    secondary = Color(0xFFB9C1CC),
    onSecondary = Color(0xFF1B1F24),
    secondaryContainer = Color(0xFF2A2F37),
    onSecondaryContainer = Color(0xFFE2E6EC),
    background = Color(0xFF111317),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF111317),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF22262D),
    onSurfaceVariant = Color(0xFF9EA4AD),
    surfaceContainerLowest = Color(0xFF0C0E11),
    surfaceContainerLow = Color(0xFF16191D),
    surfaceContainer = Color(0xFF1A1D22),
    surfaceContainerHigh = Color(0xFF21252B),
    surfaceContainerHighest = Color(0xFF2A2E35),
    outline = Color(0xFF3A3F47),
    outlineVariant = Color(0xFF2A2E35),
    error = Color(0xFFF28B82),
    onError = Color(0xFF3B0A06),
    errorContainer = Color(0xFF3A1D1B),
    onErrorContainer = Color(0xFFF7CCC7),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBEBCB),
    onPrimaryContainer = Color(0xFF2C1B00),
    secondary = Color(0xFF555D68),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8EC),
    onSecondaryContainer = Color(0xFF1B1F24),
    background = Color(0xFFF8F8F6),
    onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFF8F8F6),
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFECECE8),
    onSurfaceVariant = Color(0xFF5B616A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F2EF),
    surfaceContainer = Color(0xFFEDEDEA),
    surfaceContainerHigh = Color(0xFFE7E7E3),
    surfaceContainerHighest = Color(0xFFE0E0DB),
    outline = Color(0xFFCBCBC5),
    outlineVariant = Color(0xFFE0E0DA),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBE9E7),
    onErrorContainer = Color(0xFF410E0B),
)

/** Colors Material has no slot for: a good result, and "fair" in the strength meter. */
private class ExtraColors(val success: Color, val successContainer: Color, val onSuccessContainer: Color, val fair: Color)

private val DarkExtra = ExtraColors(Color(0xFF7FD3A0), Color(0xFF16291D), Color(0xFFC9EED6), Color(0xFFE5A54B))
private val LightExtra = ExtraColors(Color(0xFF1E7B45), Color(0xFFE3F3E8), Color(0xFF0B2E18), Color(0xFFB06A00))
private val LocalExtra = staticCompositionLocalOf { DarkExtra }

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SeedTagTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

private val Gutter = 20.dp

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
    // While words or a password are being typed, the mode switch is hidden: one task on screen.
    val typing = vm.tab == Screen.Backup && (vm.step == BackupStep.Words || vm.step == BackupStep.Password)
    // Each time the last prompt or dialog closes, the screen behind it scrolls back to the top, so
    // what changed (a tag check result, an error's context) is in view.
    val prompting = vm.busy != null || vm.scanPrompt != null || vm.dialog != null
    var actionsDone by remember { mutableIntStateOf(0) }
    LaunchedEffect(prompting) { if (!prompting) actionsDone++ }
    // What's behind some prompts (words, a password) is blurred while they're up: "Still there?" (nobody
    // is watching the screen, which is kept on), the two Back questions, the Settings, tab-switch and
    // clear-words questions, the progress dialog and "Ready to scan" (the phone is held to a tag, often in view of
    // others). Blur needs Android 12; before that, it's covered.
    val veiled = vm.busy != null || vm.scanPrompt != null ||
        vm.dialog.let {
            it == Dialog.StillThere || it is Dialog.ConfirmBack || it is Dialog.ConfirmSettings || it is Dialog.ConfirmTab ||
                it is Dialog.ConfirmClearWords
        }
    val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(if (veiled && blurs) Modifier.blur(20.dp) else Modifier) {
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
                        onFindSpot = { vm.page = Page.Antenna },
                        onAbout = { vm.page = Page.About },
                        onClose = vm::requestClose,
                        closeEnabled = !vm.tagWorking,
                    )
                },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    if (page == null && !typing) ModeSwitch(vm)
                    // The NFC spot page says it itself, as its first step.
                    Banners(
                        if (page == Page.Antenna) NfcState.Ready else nfcState, openNfcSettings, overlayDetected, accessibilityApps,
                        { settings(SettingsPage.Accessibility) }, hideAccessibilityBanner,
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
private val LocalAirplaneMode = compositionLocalOf { false }

/** USB or wireless debugging is on (read from Settings.Global, no permission). */
private val LocalDebugging = compositionLocalOf { false }
private val LocalOpenDeveloperSettings = staticCompositionLocalOf<() -> Unit> { {} }

/** Counts closed prompts and dialogs; [StepScreen] scrolls to the top when it changes. */
private val LocalActionsDone = compositionLocalOf { 0 }

/** A text style whose line box is just the font's height, centered, so the letters sit on a row's center line. */
private fun TextStyle.centered() =
    copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

/**
 * The top bar: one row, so the icon, the title, Find NFC spot, ⓘ and ✕ Close SeedTag (or the back arrow,
 * a page's title and ✕) all share the same vertical center.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Header(
    page: Page?,
    onBack: () -> Unit,
    onFindSpot: () -> Unit,
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
            Spacer(Modifier.width(8.dp))
            AppIcon()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            page?.title ?: "SeedTag",
            style = MaterialTheme.typography.titleLarge.centered(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (page == null) {
            TextButton(onClick = onFindSpot) {
                Text(Page.Antenna.button, style = MaterialTheme.typography.labelLarge.centered())
            }
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
) {
    val any = nfcState != NfcState.Ready || overlayDetected || accessibilityApps.isNotEmpty()
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

// ========================================================= components ===

private enum class Tone { Info, Caution, Success }

private class ToneColors(val container: Color, val accent: Color, val content: Color)

@Composable
private fun colorsOf(tone: Tone): ToneColors {
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
private fun iconOf(tone: Tone): ImageVector = ImageVector.vectorResource(
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
private fun Notice(
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
private fun TagDiffersNotice(differences: List<String>) {
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
private fun wrongPasswordText(d: Dialog.WrongPassword): String {
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
private fun Link(text: String, color: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
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
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "Step 2 of 4", a segmented progress bar, the title and a one-line explanation. */
@Composable
private fun StepHeader(
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
private fun StepScreen(
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
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = enabled) { Text(text) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        enabled = enabled,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { Text(text, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Permanent or destructive: outlined in red, unlike every other button. */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(text) }
}

/** Big status mark, title and one line under it: the top of a result screen. */
@Composable
private fun Hero(tone: Tone, title: String, subtitle: String) {
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
private class Fact(val label: String, val value: String, val tone: Tone? = null, val mono: Boolean = false)

@Composable
private fun Facts(facts: List<Fact>) {
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
private fun Steps(steps: List<String>) {
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
private fun Preflight(vm: SeedTagViewModel, flow: Flow) {
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

private enum class Flow { Backup, Verify, Restore }

/**
 * Dialogs are windows of their own, so the activity's guards don't cover them: drop touches that
 * come through another app's overlay (or, on Android 10–11, come while one covers part of the
 * dialog), hide overlays on Android 12+, and on Android 14+ keep
 * accessibility services that aren't accessibility tools out, as MainActivity does.
 */
@Composable
private fun GuardDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.rootView.filterTouchesWhenObscured = true
        val window = (view.parent as? DialogWindowProvider)?.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window?.setHideOverlayWindows(true)
        }
        // Android 10–11 can't hide overlays: also drop touches while one covers part of the dialog.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
            val callback = window?.callback
            if (callback != null && callback !is ObscuredTouchFilter) window.callback = ObscuredTouchFilter(callback)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.rootView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        view.rootView.excludeFromCaptureAndAutofill()
        onDispose {}
    }
}

// ============================================================= back up ===

private const val BACKUP_STEPS = 4

@Composable
private fun BackupScreen(vm: SeedTagViewModel) {
    when (vm.step) {
        BackupStep.Tag -> TagStep(vm)
        BackupStep.Length -> LengthStep(vm)
        BackupStep.Words -> WordsStep(vm)
        BackupStep.Password -> PasswordStep(vm)
        BackupStep.Done -> DoneStep(vm)
    }
}

/** The checklist item that comes right after "Read every screen carefully", and the About section. */
private const val SPARE_PHONE_TITLE = "Safest on a spare phone"
private const val SPARE_PHONE_SUMMARY =
    "Use SeedTag on a spare phone that's never online, not on the phone you use every day."
private const val SPARE_PHONE_DETAILS =
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

private const val CLOSE_SEEDTAG = "Close SeedTag"

/** Why Close SeedTag exists (board B4): the checklist's Nothing is kept, and the About section. */
private const val CLOSE_DETAILS =
    "Leaving SeedTag clears what you typed or decrypted, but a few copies can't be cleared by an app: the password " +
        "as text while Show is on, text already drawn on the screen, the memory used to turn the password into a " +
        "key, and copies of the key inside Android's encryption. They stay in SeedTag's memory until Android ends " +
        "SeedTag, which can take hours or days.\n\n" +
        "Close SeedTag (✕ at the top right, and at the end of Back up, Verify and Restore) ends it at once, so they go " +
        "with it. Even then, copies outside SeedTag, such as the screen's graphics memory, and freed memory until " +
        "it's used again, can't be cleared by any app: restart the phone before it goes back online."

private const val DISCLAIMER =
    "SeedTag comes without any warranty, and its developer is not responsible for any loss or damage resulting from " +
        "its use, including a lost seed or lost funds. You use it at your own risk.\n\n" +
        "SeedTag is beta software, still in development. It has only been tested with NXP NTAG213 tags: other " +
        "tag brands and types may not work as described. Before relying on a tag, back up to it, then Verify and " +
        "Restore it (and ideally decrypt it with seedtag.py), and keep another copy of your seed.\n\n" +
        "Before you enter a seed, it's up to you to make sure this phone is free of malware. SeedTag defends against " +
        "some attacks by other apps, but it can't cover every possible one. If the phone is compromised, your seed may be too."

@Composable
private fun TagStep(vm: SeedTagViewModel) {
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
            Link("Not sure where to hold it? Find your NFC spot") { vm.page = Page.Antenna }
        }
        if (check != null) TagCheckResult(check)
        Preflight(vm, Flow.Backup)
    }
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
            "readable on the phone you have when you need it. Use another tag (an NTAG213, the chip SeedTag " +
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
private fun capacityText(bytes: Int, estimated: Boolean): String {
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
                Button(onClick = vm::toPasswordStep, enabled = vm.mnemonicStatus is MnemonicStatus.Valid, modifier = Modifier.height(48.dp)) {
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

/** Completions above a keyboard; fixed height, so the keys don't jump. */
@Composable
private fun Suggestions(words: List<String>, onPick: (String) -> Unit) {
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

@Composable
private fun WordGrid(words: List<String>, current: String? = null, hidden: Boolean = false, max: Int = 24) {
    // Same mask for every word, so it doesn't give away word lengths.
    val cells = words.mapIndexed { i, w -> Triple(i + 1, if (hidden) "••••••" else w, false) } +
        if (current != null && words.size < max) listOf(Triple(words.size + 1, "$current▏", true)) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (n, w, typing) ->
                    Row(
                        Modifier.weight(1f)
                            .height(38.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .then(if (typing) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$n",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(20.dp),
                        )
                        Text(w, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Keypad(onLetter: (Char) -> Unit, onBackspace: () -> Unit, onBackspaceHeld: () -> Boolean) {
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

@Composable
private fun PasswordStep(vm: SeedTagViewModel) {
    var field by remember { mutableStateOf(PwField.Password) }
    StepScreen(
        bottom = {
            PasswordKeyboard(vm, field)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::requestBackToWords) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = vm::encryptAndWrite,
                    enabled = vm.passwordLength(PwField.Password) > 0 && vm.passwordsMatch,
                    modifier = Modifier.height(48.dp),
                ) { Text("Encrypt & write tag") }
            }
        },
    ) {
        StepHeader(4, BACKUP_STEPS, "Set a password", compact = true)
        Notice(
            Tone.Caution,
            "This password is the only key",
            "Anyone who finds the tag can try passwords offline, without limit. And if you lose the password, nothing can " +
                "decrypt the tag – not this app, not seedtag.py, not the developer, nobody.",
            "A generated 7-word passphrase (≈77 bits) is strongly recommended. Write it down, label it \"SeedTag " +
                "password\" and store it apart from the tag. There's no reset and no recovery, so keep the written copy " +
                "safe and keep another backup of the seed itself.",
        )
        // A new passphrase is shown, in the password field, until the user says it's written down.
        LaunchedEffect(vm.passphrasesGenerated) { if (vm.passphrasesGenerated > 0) field = PwField.Password }
        FilledTonalButton(
            onClick = vm::generatePassphrase,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(if (vm.generatedPassphrase) "Generate a different one" else "Generate a 7-word passphrase")
        }
        // While Repeat is typed, the passphrase is hidden: it's typed back from the written copy, which
        // checks that copy.
        val copying = vm.generatedPassphrase && field == PwField.Repeat
        if (vm.generatedPassphrase) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val on = MaterialTheme.colorScheme.onPrimaryContainer
                    if (copying) {
                        Text("Now type it from your written copy", style = MaterialTheme.typography.labelLarge, color = on)
                        Text(
                            "The passphrase is hidden, so that Repeat password checks what you wrote down, not the screen. " +
                                "If they don't match, your copy has a mistake: correct it before writing the tag.",
                            style = MaterialTheme.typography.bodySmall,
                            color = on.copy(alpha = 0.8f),
                        )
                        Link("Show it again") { field = PwField.Password }
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
                        Button(onClick = { field = PwField.Repeat }, modifier = Modifier.fillMaxWidth()) {
                            Text("I've written it down")
                        }
                    }
                }
            }
        }
        SecretField("Password", vm, PwField.Password, vm.generatedPassphrase && !copying, active = field == PwField.Password) {
            field = PwField.Password
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
            active = field == PwField.Repeat,
        ) {
            field = PwField.Repeat
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
                Facts(listOf(Fact("Fingerprint", it, mono = true)))
                Text(
                    "This isn't secret. If the backup is intact, Verify (here or in seedtag.py) shows the same value. " +
                        "It's SeedTag's own: a wallet may show a fingerprint too, but that's a different number.",
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
                "Optionally, recover it on an offline computer with seedtag.py.",
                "Keep a copy of seedtag.py (printed, or on a USB stick) with your written password: with Python, " +
                    "it's all anyone needs to recover the tag, even if SeedTag and its website are gone.",
                "Only then make the tag read-only (on the Verify tab – it's permanent) and keep another backup of the seed somewhere else.",
            ),
        )
        CloseSection(vm)
    }
}

/** Close SeedTag where a procedure ends: Done, and the Verify and Restore results. */
@Composable
private fun CloseSection(vm: SeedTagViewModel) {
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
private fun LockButton(vm: SeedTagViewModel) {
    DangerButton("Make tag read-only", vm::requestLock)
}

// ============================================================ erase tag ===

/**
 * The Erase tab: scan (nothing written), see what the tag holds, optionally check a backup's
 * password, then erase that very tag. Erasing fills the whole NDEF area with zeros before leaving
 * it empty.
 */
@Composable
private fun EraseScreen(vm: SeedTagViewModel) {
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
    // Only the guidance scrolls; the password, keyboard and Scan button stay fixed at the bottom.
    StepScreen(
        bottom = {
            SecretField("Password", vm, PwField.Read, revealDefault = false, active = true) {}
            PasswordKeyboard(vm, PwField.Read)
            PrimaryButton("Scan tag", if (reveal) vm::scanForRestore else vm::scanForVerify, enabled = vm.passwordLength(PwField.Read) > 0)
        },
    ) {
        StepHeader(
            1, 2,
            if (reveal) "Restore a seed" else "Verify a tag",
            if (reveal) "Type the password, then scan the tag. The words will be shown on screen." else
                "Type the password from your written copy, then scan the tag. The words stay hidden.",
        )
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
                SectionLabel("Make it read-only")
                Text(
                    "Once you're sure, you can make the tag read-only. That's permanent: its data can never be rewritten or " +
                        "erased. It doesn't stop someone who gets hold of the tag from locking it against reading with a " +
                        "password of their own, so keep it where strangers can't reach it, and keep other backups.",
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

private const val NOT_PROTECTABLE =
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
        if (info.settingsLocked) add(Fact("Settings", "Locked for good: its write password can't be changed", Tone.Caution))
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
private fun passwordFixNote(fix: PasswordFix): String? {
    val ignored = when (fix) {
        PasswordFix.None -> return null
        PasswordFix.Spaces -> "extra spaces"
        PasswordFix.Case -> "capital letters"
        PasswordFix.SpacesAndCase -> "extra spaces and capital letters"
    }
    return "The password only matched once $ignored were ignored. Make sure your written copy shows it exactly as it was set."
}

/**
 * Shows a password; it is never an editable text field. Without a text field the system keyboard
 * can't open, so no keyboard app sees what's typed, and there's nothing to copy, paste or autofill.
 */
@Composable
private fun SecretField(
    label: String,
    vm: SeedTagViewModel,
    field: PwField,
    revealDefault: Boolean,
    active: Boolean,
    onSelect: () -> Unit,
) {
    var reveal by remember(revealDefault) { mutableStateOf(revealDefault) }
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
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
        }
    }
}

/**
 * In-app keyboard for passwords, with BIP39 completions (the recommended passphrase is BIP39 words).
 * It only sends key presses to the view model, which edits the password buffer in place.
 */
@Composable
private fun PasswordKeyboard(vm: SeedTagViewModel, field: PwField) {
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

// ======================================================= find NFC spot ===

@Composable
private fun AntennaPage(vm: SeedTagViewModel, nfcState: NfcState, openNfcSettings: () -> Unit) {
    DisposableEffect(vm) {
        vm.startProbe()
        onDispose { vm.stopProbe() }
    }
    StepScreen {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Find your phone's NFC spot", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Every phone reads tags at one small spot on its back. Find it now so you know where to hold the tag " +
                    "when the app asks. Nothing is written to the tag and its contents aren't read.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("1 · Check that NFC is on")
            when (nfcState) {
                NfcState.Ready -> Notice(Tone.Success, "NFC is on", "Go on to the next step.")
                NfcState.Disabled -> Notice(Tone.Caution, "NFC is off", "Turn it on first – without it, no tag can be detected.") {
                    Link("Turn on NFC", onClick = openNfcSettings)
                }
                NfcState.Missing -> Notice(Tone.Caution, "This device has no NFC", "SeedTag can't read or write tags on it.")
            }
        }

        val p = vm.probe
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("2 · Slide the tag over the back of the phone")
            Text(
                "Use your tag or any contactless card. Move it slowly, flat against the phone, until the phone " +
                    "vibrates and Tag detected appears below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Detector(found = p != null)
            if (p == null) {
                Text("Waiting for a tag…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Tag detected" + if (p.count > 1) " · ${p.count} times" else "", style = MaterialTheme.typography.titleMedium)
                Text(
                    "That's the spot. Lift the tag away and bring it back to try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (p != null) {
            Facts(
                listOf(
                    Fact(
                        "This tag",
                        when {
                            p.ndef && p.writable == false -> "Read-only – SeedTag can read it but can't write to it."
                            p.ndef && p.ndefSize != null -> "NDEF tag, " + capacityText(p.ndefSize, estimated = false)
                            p.ndef -> "Can hold NDEF data, so SeedTag can use it."
                            p.formatable -> "Blank. Back up checks its size first and formats it when the backup is written."
                            else -> "Not an NDEF tag. SeedTag can't use it, but it's fine for finding the spot."
                        },
                    ),
                ),
            )
        }
        if (ChipTest.AVAILABLE) ChipTestSection(vm)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Tips")
            Steps(
                listOf(
                    "The spot is usually in the upper half of the back, near the camera, or in the middle. On some phones, it's at the very top edge.",
                    "Hold the tag flat against the phone, not edge-on, and keep it still for a moment.",
                    "Small tags, stickers and rings have a small antenna and usually a small NFC symbol. Put the side " +
                        "with the symbol directly against the phone's NFC spot.",
                    "Thick or metal cases, magnetic mounts, ring holders and card wallets can block it – take them off.",
                    "Later, you only need to hold the tag there when the app asks for it, not the whole time.",
                ),
            )
        }
    }
}

/** Debug builds only ([ChipTest]): raw commands, one per tap, and what the chip answered. */
@Composable
private fun ChipTestSection(vm: SeedTagViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Chip test (debug build only)")
        Text(
            "Sends one command per tap and shows the chip's raw answer: ${ChipTest.labels.joinToString(", ")}. " +
                "Nothing is written. Take the tag away between taps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val next = vm.chipTestNextLabel
        if (next == null) {
            SecondaryButton("Start chip test", vm::startChipTest)
        } else {
            Text("Next tap: $next", style = MaterialTheme.typography.titleSmall)
            vm.chipTestLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            SecondaryButton("Stop chip test", vm::stopChipTest)
        }
    }
}

/** A slowly pulsing ring while waiting; a green check once a tag has been seen. */
@Composable
private fun Detector(found: Boolean) {
    val x = LocalExtra.current
    val c = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "pulse")
    val k by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "k")
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        if (!found) {
            Box(Modifier.size(150.dp).scale(0.6f + 0.4f * k).alpha(1f - k).border(2.dp, c.primary, CircleShape))
        }
        Box(
            Modifier.size(96.dp).background(if (found) x.successContainer else c.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (found) {
                Icon(iconOf(Tone.Success), null, tint = x.success, modifier = Modifier.size(44.dp))
            } else {
                Text("NFC", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
            }
        }
    }
}

// =============================================================== about ===

/** Where the source code lives, and where to donate. Opened in the browser, only when tapped. */
private const val REPO_URL = "https://github.com/zivke/seedtag"
private const val DONATE_URL = "https://github.com/zivke/.github/blob/main/DONATE.md"

@Composable
private fun AboutPage() {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
    StepScreen {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SeedTag", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${version ?: "unknown"} · beta", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "SeedTag is free, open-source software. It stores a password-encrypted BIP39 seed phrase on an NFC " +
                    "tag and reads it back. Back up writes it, Verify checks it without showing the words and Restore " +
                    "shows them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            AboutSection(SPARE_PHONE_TITLE, SPARE_PHONE_SUMMARY + "\n\n" + SPARE_PHONE_DETAILS)
            AboutSection(
                "Open source",
                "SeedTag's full source code, including seedtag.py, is public on GitHub, so anyone can check what it " +
                    "does and build it themselves.",
                "github.com/zivke/seedtag" to REPO_URL,
            )
            AboutSection(
                "Privacy",
                "SeedTag works entirely offline. It has no internet permission, so it can't send anything anywhere, " +
                    "and it has no analytics or crash reporting. Its only permissions are NFC, hiding other apps' " +
                    "overlays and vibration. It stores nothing on the phone – everything typed or decrypted is wiped " +
                    "when you leave the app.",
            )
            AboutSection("Closing SeedTag", CLOSE_DETAILS)
            AboutSection(
                "Protection on the phone",
                "Seed words and passwords are typed on the app's own keyboards, never the system keyboard. Screenshots " +
                    "and screen recording are blocked, other apps' overlays are hidden (Android 12+) and taps that come " +
                    "through another app's window are ignored.",
            )
            AboutSection(
                "Encryption",
                "Argon2id (64 MiB, 3 passes) turns the password into a key, and AES-256-GCM encrypts the seed's raw " +
                    "entropy. The format is documented and versioned. seedtag.py, a single-file Python tool, encrypts " +
                    "and decrypts the same format on any computer, so your backup doesn't depend on this app.",
            )
            AboutSection(
                "License",
                "Free and open source under the GNU General Public License, version 3 or later. You may use, share and " +
                    "modify it, but anything you distribute that's based on it must be released under the same license, " +
                    "together with its source code. seedtag.py is under the BSD Zero Clause license, so anyone can always use it to " +
                    "recover a tag. © 2026 zivke.",
            )
            AboutSection(
                "Support SeedTag",
                "SeedTag is free and has no ads. If you've found it useful, you can support its development with a donation.",
                "Donate" to DONATE_URL,
            )
            Text(
                "Links open in your browser. Leaving SeedTag clears what you've typed in it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Notice(Tone.Caution, "Use at your own risk", DISCLAIMER)
    }
}

@Composable
private fun AboutSection(title: String, text: String, link: Pair<String, String>? = null) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        link?.let { (label, url) ->
            val uri = LocalUriHandler.current
            // No browser installed: nothing happens.
            Link("$label ↗") { runCatching { uri.openUri(url) } }
        }
    }
}

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
private fun Dialogs(vm: SeedTagViewModel, leave: () -> Unit, openSettings: (SettingsPage) -> Unit, close: () -> Unit) {
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
        is Dialog.ConfirmBack -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text(if (d.toWords) "Clear the password?" else "Leave SeedTag?") },
            text = {
                GuardDialogWindow()
                Text(
                    if (d.toWords) {
                        "Going back to the words clears the password. If you've written a generated passphrase down, " +
                            "cross that copy out: the next one will be different."
                    } else {
                        "Leaving SeedTag clears everything you entered or restored: none of it is kept anywhere."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dialog = null; if (d.toWords) vm.backToWords() else leave() }) {
                    DangerText(if (d.toWords) "Clear and go back" else "Leave")
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
        Dialog.ConfirmLock -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = { WarningIcon() },
            title = { Text("Make tag read-only?") },
            text = {
                GuardDialogWindow()
                Text(
                    "This is permanent: the tag's data can never be rewritten or erased. On many chips it doesn't stop " +
                        "someone who gets hold of the tag from making it unreadable, so keep the tag out of strangers' reach " +
                        "and keep other backups. " +
                        "Only do this after you've successfully verified the backup with your written password.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmLock) { DangerText("Make read-only") } },
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
