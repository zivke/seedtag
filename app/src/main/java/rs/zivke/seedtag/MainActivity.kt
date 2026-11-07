// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag

import android.content.Intent
import android.os.Build
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

enum class NfcState { Ready, Disabled, Missing }

class MainActivity : ComponentActivity() {

    private val vm: SeedTagViewModel by viewModels()
    private var adapter: NfcAdapter? = null
    private val nfcState = mutableStateOf(NfcState.Ready)
    /** The last touch came through another app's overlay and was dropped. */
    private val overlayDetected = mutableStateOf(false)
    /** The current gesture is being dropped because an overlay covered it. */
    private var droppingGesture = false

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
                SeedTagApp(
                    vm = vm,
                    nfcState = nfcState.value,
                    overlayDetected = overlayDetected.value,
                    openNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val a = adapter
        nfcState.value = when {
            a == null -> NfcState.Missing
            !a.isEnabled -> NfcState.Disabled
            else -> NfcState.Ready
        }
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
     * window (FLAG_WINDOW_IS_OBSCURED) never gets to the UI, and the app says why. If an overlay
     * shows up in the middle of a gesture, what the UI saw so far is cancelled, so a tap can't land.
     * Dialogs are windows of their own; they filter touches in Ui.kt (GuardDialogWindow).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val obscured = ev.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Coming back from a dialog, the shade or another app can bring the bars back.
        if (hasFocus) hideSystemBars()
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
