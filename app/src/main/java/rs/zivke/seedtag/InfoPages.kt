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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ======================================================= find NFC spot ===

@Composable
internal fun AntennaPage(vm: SeedTagViewModel, nfcState: NfcState, openNfcSettings: () -> Unit) {
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
internal fun AboutPage() {
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
            OpenSourceLicenses()
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

/**
 * Board B19: what SeedTag includes from others, with each licence's full text, since Apache-2.0 and MIT ask for it
 * with every copy (and GPL §0 for the GPL's own "Appropriate Legal Notices"). The texts are in res/raw, the same
 * files as LICENSE and LICENSES/ (`make check` compares them), and read only when shown.
 */
@Composable
private fun OpenSourceLicenses() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Open-source licences", style = MaterialTheme.typography.titleSmall)
        Text(
            "SeedTag includes software and data by others, each under its own licence:\n\n" +
                "• AndroidX and Jetpack Compose, and five Material Symbols icons (Google); Kotlin, kotlinx.coroutines " +
                "and kotlinx.serialization (JetBrains); JetBrains annotations; JSpecify: Apache License 2.0.\n" +
                "• Bouncy Castle, for Argon2id: MIT License, © 2000-2026 The Legion of the Bouncy Castle Inc.\n" +
                "• The BIP39 English wordlist, from BIP-0039: MIT License.\n\n" +
                "SeedTag itself is under the GNU GPL, version 3 or later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LicenseText("GNU General Public License, version 3", R.raw.license_gpl_3_0)
        LicenseText("Apache License 2.0", R.raw.license_apache_2_0)
        LicenseText("MIT License: Bouncy Castle", R.raw.license_mit_bouncycastle)
        LicenseText("MIT License: the BIP39 wordlist", R.raw.license_mit_bip39)
    }
}

/** A licence's full text, folded away; read from [res] only once it's opened. */
@Composable
private fun LicenseText(title: String, res: Int) {
    var open by remember { mutableStateOf(false) }
    val resources = LocalResources.current
    Link(if (open) "Hide: $title" else "Show: $title") { open = !open }
    if (open) {
        // Hard-wrapped plain text: one Text per paragraph, its lines joined, so it fits any width.
        val paragraphs = remember(res) {
            resources.openRawResource(res).bufferedReader(Charsets.UTF_8).use { it.readText() }
                .split(Regex("\n\\s*\n"))
                .map { para -> para.lines().joinToString(" ") { it.trim() }.trim() }
                .filter { it.isNotEmpty() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            paragraphs.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
