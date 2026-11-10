// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

enum class NfcState { Ready, Disabled, Missing }

/**
 * True if this touch came through another app's window, or while one covers part of ours.
 *
 * FLAG_WINDOW_IS_OBSCURED: the touch point itself is under another window (all versions).
 * FLAG_WINDOW_IS_PARTIALLY_OBSCURED (Android 10+): another window covers some of ours, e.g. an
 * overlay that hides a warning but leaves its button clear. That one only counts on Android 10–11:
 * 8–9 don't report it, and 12+ hides other apps' overlays, where it would only catch harmless windows.
 */
fun MotionEvent.isObscured(): Boolean {
    if (flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0) return true
    return Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R &&
        flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0
}

/** A dialog window's callback that drops touches [isObscured] flags, before the dialog sees them. */
class ObscuredTouchFilter(private val inner: Window.Callback) : Window.Callback by inner {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = event.isObscured() || inner.dispatchTouchEvent(event)
}

class MainActivity : ComponentActivity() {

    private val vm: SeedTagViewModel by viewModels()
    private var adapter: NfcAdapter? = null
    private val nfcState = mutableStateOf(NfcState.Ready)
    /** Airplane mode is on: a hint that the phone is offline, not proof (Wi-Fi and Bluetooth can be on). */
    private val airplaneMode = mutableStateOf(false)
    /** The last touch came through another app's overlay and was dropped. */
    private val overlayDetected = mutableStateOf(false)
    /** The current gesture is being dropped because an overlay covered it. */
    private var droppingGesture = false
    /** Names of the enabled accessibility services that aren't part of the system. */
    private val accessibilityApps = mutableStateOf<List<String>>(emptyList())
    /** The list the user tapped Hide on; the banner comes back if it changes. */
    private val accessibilityHidden = mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, blank thumbnail in Recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // All typing uses the app's own keyboards and there are no text fields. On top of that,
        // this window never takes input from a keyboard app, so none can open over the app.
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        // Android 12+: other apps' overlays ("Display over other apps") are hidden while SeedTag is
        // on screen, so none can pose as SeedTag's keypad or hide what a button does.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        hideSystemBars()
        adapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            SeedTagTheme {
                val apps = accessibilityApps.value
                SeedTagApp(
                    vm = vm,
                    nfcState = nfcState.value,
                    airplaneMode = airplaneMode.value,
                    overlayDetected = overlayDetected.value,
                    openNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                    accessibilityApps = if (apps == accessibilityHidden.value) emptyList() else apps,
                    openAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    hideAccessibilityBanner = { accessibilityHidden.value = apps },
                )
            }
        }
        // Android 14+: only accessibility *tools* (screen readers such as TalkBack) can see this
        // window's content or act on it; any other accessibility service gets nothing. Children
        // inherit it from the decor view. Dialogs set it themselves (GuardDialogWindow in Ui.kt).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            window.decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
    }

    override fun onResume() {
        super.onResume()
        val a = adapter
        refreshRadios()
        accessibilityApps.value = enabledAccessibilityApps()
        // Reader mode for the whole time we're in the foreground: tags go to us only,
        // never to other apps (e.g. NFC Tools or the system tag viewer).
        a?.enableReaderMode(
            this,
            { tag -> vm.onTagDiscovered(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) },
        )
    }

    /**
     * Tapjacking guard for every Android version: a touch that reaches us through another app's
     * window (or, on Android 10–11, while one covers part of ours: [isObscured]) never gets to the
     * UI, and the app says why. If an overlay
     * shows up in the middle of a gesture, what the UI saw so far is canceled, so a tap can't land.
     * Dialogs are windows of their own; they filter touches in Ui.kt (GuardDialogWindow).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val obscured = ev.isObscured()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            droppingGesture = false
            overlayDetected.value = obscured
        }
        if (obscured && !droppingGesture) {
            droppingGesture = true
            overlayDetected.value = true
            if (ev.actionMasked != MotionEvent.ACTION_DOWN) {
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
        }
        return if (droppingGesture) true else super.dispatchTouchEvent(ev)
    }

    /**
     * Enabled accessibility services, other than preinstalled system ones: they can read the
     * screen and see every tap (on Android 14+ only if they declare themselves accessibility
     * tools). Needs no permission. Checked on every resume, e.g. after a visit to Settings.
     */
    private fun enabledAccessibilityApps(): List<String> {
        val am = getSystemService(AccessibilityManager::class.java) ?: return emptyList()
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo }
            .filter { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { s -> runCatching { s.applicationInfo.loadLabel(packageManager).toString() }.getOrDefault(s.packageName) }
            .distinct()
            .sorted()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Coming back from a dialog, the shade or another app can bring the bars back.
        if (hasFocus) hideSystemBars()
        // The quick-settings shade doesn't pause the activity, but airplane mode or NFC may have
        // been switched there.
        if (hasFocus) refreshRadios()
    }

    /**
     * NFC on or off, and airplane mode. Reading the airplane-mode setting needs no permission; Wi-Fi
     * and Bluetooth state would (ACCESS_WIFI_STATE, BLUETOOTH_CONNECT), so the app only reminds
     * users to check those.
     */
    private fun refreshRadios() {
        val a = adapter
        nfcState.value = when {
            a == null -> NfcState.Missing
            !a.isEnabled -> NfcState.Disabled
            else -> NfcState.Ready
        }
        airplaneMode.value = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    }

    /** Full screen: no status or navigation bar. A swipe from the edge shows them briefly. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onPause() {
        adapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onStop() {
        // Leaving the app (home, recents, screen off) drops every secret from memory-held UI state.
        if (!isChangingConfigurations) vm.wipeAll()
        super.onStop()
    }
}
