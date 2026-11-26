// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Process
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
    /** USB or wireless debugging is on: whatever the phone was paired with can install or replace apps. */
    private val debugging = mutableStateOf(false)
    /** The last touch came through another app's overlay and was dropped. */
    private val overlayDetected = mutableStateOf(false)
    /** The current gesture is being dropped because an overlay covered it. */
    private var droppingGesture = false
    /** Names of the enabled accessibility services that aren't part of the system. */
    private val accessibilityApps = mutableStateOf<List<String>>(emptyList())
    /** The list the user tapped Hide on; the banner comes back if it changes. */
    private val accessibilityHidden = mutableStateOf<List<String>>(emptyList())

    /**
     * Any accessibility service is on, preinstalled ones too (TalkBack, Select to Speak), or touch exploration
     * (board B23): they may speak what's on screen, words included, through a text-to-speech app.
     */
    private val accessibilityOn = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // No screenshots, no screen recording, blank thumbnail in Recents. Set before anything else
        // runs, so the window is never shown or captured without it.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
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
                    debugging = debugging.value,
                    overlayDetected = overlayDetected.value,
                    openSettings = ::openSettings,
                    accessibilityApps = if (apps == accessibilityHidden.value) emptyList() else apps,
                    accessibilityOn = accessibilityOn.value,
                    hideAccessibilityBanner = { accessibilityHidden.value = apps },
                    leave = { finish() },
                    close = ::closeSeedTag,
                )
            }
        }
        // Android 14+: only accessibility *tools* (screen readers such as TalkBack) can see this
        // window's content or act on it; any other accessibility service gets nothing. Children
        // inherit it from the decor view. Dialogs set it themselves (GuardDialogWindow in Components.kt).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            window.decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        window.decorView.excludeFromCaptureAndAutofill()
    }

    /**
     * Opens a Settings screen. Some phones (trimmed or vendor builds) lack one of them: then the main
     * Settings screen, and if even that is missing, nothing happens rather than a crash.
     */
    private fun openSettings(page: SettingsPage) {
        val action = when (page) {
            SettingsPage.Nfc -> Settings.ACTION_NFC_SETTINGS
            SettingsPage.Accessibility -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            SettingsPage.Developer -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        }
        runCatching { startActivity(Intent(action)) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    override fun onResume() {
        super.onResume()
        refreshRadios()
        refreshAccessibility()
        watchAccessibility(true)
        enableReader()
    }

    /**
     * Reader mode for the whole time we're in the foreground: tags go to us only, never to other
     * apps (e.g. NFC Tools or the system tag viewer).
     */
    private fun enableReader() {
        adapter?.enableReaderMode(
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
     * Dialogs are windows of their own; they filter touches in Components.kt (GuardDialogWindow).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val obscured = ev.isObscured()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            droppingGesture = false
            overlayDetected.value = obscured
            if (!obscured) vm.touched() // restarts the idle timeout
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

    private fun refreshAccessibility() {
        accessibilityApps.value = enabledAccessibilityApps()
        val am = getSystemService(AccessibilityManager::class.java)
        accessibilityOn.value = am != null && (
            am.isTouchExplorationEnabled ||
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).isNotEmpty()
            )
    }

    private val touchExplorationChanged = AccessibilityManager.TouchExplorationStateChangeListener { refreshAccessibility() }

    private val accessibilityChanged = AccessibilityManager.AccessibilityStateChangeListener {
        refreshAccessibility()
    }
    private var servicesChanged: Any? = null // AccessibilityServicesStateChangeListener, API 33+

    /**
     * While SeedTag is in front, a service switched on (over adb, say) shows in the banner right away,
     * not only on the next resume. The state listener fires when accessibility as a whole goes on or
     * off; on Android 13+ the services listener also catches a change to the list.
     */
    private fun watchAccessibility(on: Boolean) {
        val am = getSystemService(AccessibilityManager::class.java) ?: return
        if (on) {
            am.addAccessibilityStateChangeListener(accessibilityChanged)
            am.addTouchExplorationStateChangeListener(touchExplorationChanged)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val l = AccessibilityManager.AccessibilityServicesStateChangeListener { refreshAccessibility() }
                am.addAccessibilityServicesStateChangeListener(mainExecutor, l)
                servicesChanged = l
            }
        } else {
            am.removeAccessibilityStateChangeListener(accessibilityChanged)
            am.removeTouchExplorationStateChangeListener(touchExplorationChanged)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                (servicesChanged as? AccessibilityManager.AccessibilityServicesStateChangeListener)
                    ?.let(am::removeAccessibilityServicesStateChangeListener)
                servicesChanged = null
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Coming back from a dialog, the shade or another app can bring the bars back.
        if (hasFocus) hideSystemBars()
        // The quick-settings shade doesn't pause the activity, but airplane mode or NFC may have
        // been switched there. NFC switched back on: reader mode again, in case turning NFC off
        // dropped it (onResume, which normally turns it on, doesn't run for the shade).
        if (hasFocus) {
            val nfcWasReady = nfcState.value == NfcState.Ready
            refreshRadios()
            if (!nfcWasReady && nfcState.value == NfcState.Ready) enableReader()
        }
    }

    /**
     * NFC on or off, airplane mode, and USB / wireless debugging. Reading those settings needs no permission; Wi-Fi
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
        // Readable without a permission, like airplane mode. "adb_wifi_enabled" (Android 11+) has no
        // public constant.
        debugging.value = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) != 0 ||
            Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) != 0
    }

    /** Full screen: no status or navigation bar. A swipe from the edge shows them briefly. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onPause() {
        watchAccessibility(false)
        adapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onStop() {
        // Leaving the app (home, recents, screen off) drops every secret from memory-held UI state. The
        // screen doesn't time out meanwhile (IdleWatch in Ui.kt keeps it on, with the app's own timeout).
        if (!isChangingConfigurations) vm.wipeAll()
        super.onStop()
    }

    /** Close SeedTag was confirmed: [onDestroy] ends the process. */
    private var closing = false

    /**
     * Close SeedTag (board B4): clear everything, take the task out of Recents (so it doesn't come back
     * as an empty screen), then end the process in [onDestroy], once the activity is gone. The copies
     * [SeedTagViewModel.wipeAll] can't zero (a password shown as text, text already drawn, the Argon2id
     * memory, copies of the AES key) go with the process. Nothing is saved for a restart.
     */
    private fun closeSeedTag() {
        if (closing) return
        closing = true
        vm.wipeAll()
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (closing) Process.killProcess(Process.myPid())
    }
}

/**
 * Compose reports the text on screen to the system's content-capture service (Android 10+) and
 * registers the window with autofill (Android 8+). FLAG_SECURE should already stop content
 * capture; this doesn't depend on it: the window and everything in it are left out of both.
 * Used for the activity's window and for every dialog (GuardDialogWindow in Components.kt).
 */
internal fun View.excludeFromCaptureAndAutofill() {
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }
}
