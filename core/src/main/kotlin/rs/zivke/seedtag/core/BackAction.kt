// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag.core

/** What system Back does inside the app. */
enum class BackAction {
    /** Close the top-bar page. */
    ClosePage,
    /** The Words step's own Back: to the word count. */
    BackToLength,
    /** The Password step's own Back: to the words (it asks first if that clears a password). */
    BackToWords,
    /** Ask before leaving the app, which would wipe what's in memory. */
    AskToLeave;

    companion object {
        /** Null: Back leaves the app, since nothing would be lost. */
        fun of(pageOpen: Boolean, onWordsStep: Boolean, onPasswordStep: Boolean, holdsSecrets: Boolean): BackAction? = when {
            pageOpen -> ClosePage
            onWordsStep -> BackToLength
            onPasswordStep -> BackToWords
            holdsSecrets -> AskToLeave
            else -> null
        }
    }
}
