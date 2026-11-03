// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

/** All recoverable errors the core can raise. Messages are safe to show to the user (never contain secrets). */
sealed class SeedTagException(message: String) : Exception(message) {
    class BadWordCount(count: Int) :
        SeedTagException("Expected 12, 15, 18, 21 or 24 words, got $count.")

    class UnknownWord(val position: Int) :
        SeedTagException("Word #$position is not in the BIP39 English list.")

    class BadChecksum :
        SeedTagException("Checksum mismatch – one or more words are wrong or in the wrong order.")

    class UnsupportedVersion(version: Int) :
        SeedTagException("Unsupported tag format version 0x%02x.".format(version))

    class Malformed(detail: String) :
        SeedTagException("Tag data is not a valid SeedTag blob ($detail).")

    class WrongPassword :
        SeedTagException("Wrong password, or the tag data is corrupted.")
}
