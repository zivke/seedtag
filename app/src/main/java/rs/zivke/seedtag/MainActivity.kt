// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf

enum class NfcState { Ready, Disabled, Missing }

class MainActivity : ComponentActivity() {

    private val vm: SeedTagViewModel by viewModels()
    private var adapter: NfcAdapter? = null
    private val nfcState = mutableStateOf(NfcState.Ready)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, blank thumbnail in Recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        adapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            SeedTagTheme {
                SeedTagApp(
                    vm = vm,
                    nfcState = nfcState.value,
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
