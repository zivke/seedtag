// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcA
import java.io.IOException

/**
 * Debug builds only (the release source set has a stub without any commands): sends one raw Type 2
 * command per tap, to see how a chip that isn't NXP's answers what the write password needs. Nothing
 * is written. PWD_AUTH uses the factory password FFFFFFFF, which no SeedTag tag has. After each
 * command, a READ of page 0 shows whether the chip's session survived it.
 */
object ChipTest {
    const val AVAILABLE = true

    private class Command(val label: String, val bytes: ByteArray)

    private val commands = listOf(
        Command("GET_VERSION (60)", byteArrayOf(0x60)),
        Command("READ 29h (30 29)", byteArrayOf(0x30, 0x29)),
        Command("READ 2Ch (30 2C)", byteArrayOf(0x30, 0x2C)),
        Command("PWD_AUTH FFFFFFFF (1B FF FF FF FF)", byteArrayOf(0x1B, -1, -1, -1, -1)),
    )

    val labels: List<String> get() = commands.map { it.label }

    /** Called on the NFC thread: runs command [index] on [tag], returns what to show. */
    fun run(tag: Tag, index: Int): String {
        val c = commands[index % commands.size]
        val nfcA = NfcA.get(tag) ?: return "${c.label}: not an NfcA tag"
        val id = "UID ${hex(tag.id ?: ByteArray(0))}, SAK %02X".format(nfcA.sak.toInt())
        return try {
            nfcA.use {
                it.connect()
                val answer = send(it, c.bytes)
                val after = send(it, byteArrayOf(0x30, 0x00))
                "${c.label}\n→ $answer\nREAD 00h afterwards: $after\n($id)"
            }
        } catch (e: Exception) {
            "${c.label}\n→ couldn't connect (${e.javaClass.simpleName})\n($id)"
        }
    }

    private fun send(nfcA: NfcA, bytes: ByteArray): String = try {
        val r = nfcA.transceive(bytes)
        "${hex(r)} (${r.size} bytes)"
    } catch (e: TagLostException) {
        "no answer (TagLostException)"
    } catch (e: IOException) {
        "refused or no answer (${e.javaClass.simpleName})"
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}
