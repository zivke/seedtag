// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.nfc.Tag

/** The release build's stub of the debug-only chip test: no commands, never shown. */
object ChipTest {
    const val AVAILABLE = false
    val labels: List<String> get() = emptyList()
    fun run(tag: Tag, index: Int): String = ""
}
