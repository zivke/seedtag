// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke85

package rs.zivke.seedtag.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagKeyTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Same file test_seedtag.py reads, generated with seedtag.py from seedtag-v1-vectors.tsv. */
    @Test
    fun tagKeysMatchSeedtagPy() {
        val rows = javaClass.getResource("/tag-key-vectors.tsv")!!.readText().lines().filter { it.isNotBlank() }
        assertTrue(rows.size >= 5)
        for (row in rows) {
            val (pw, b64, ulcKey, ulcPages, pwd, pack) = row.split("\t")
            val opened = SeedBox.openForgiving(SeedBox.fromBase64(b64), String(hex(pw), Charsets.UTF_8).toCharArray(), withTagSecret = true)
            val secret = opened.tagSecret!!
            assertEquals(TagKey.SECRET_LEN, secret.size)
            assertEquals(ulcKey, TagKey.ultralightCKey(secret).hex())
            assertEquals(ulcPages, TagKey.ultralightCKeyPages(TagKey.ultralightCKey(secret)).hex())
            assertEquals(pwd, TagKey.ntagPwd(secret).hex())
            assertEquals(pack, TagKey.ntagPack(secret).hex())
        }
    }

    private operator fun <T> List<T>.component6() = this[5]

    @Test
    fun secretOnlyWhenAskedFor() {
        val v = javaClass.getResource("/tag-key-vectors.tsv")!!.readText().lines().first().split("\t")
        assertNull(SeedBox.openForgiving(SeedBox.fromBase64(v[1]), String(hex(v[0]), Charsets.UTF_8).toCharArray()).tagSecret)
    }

    /** MF0ICU2 datasheet, section 7.5.7: Key1 00..07 and Key2 08..0F go to pages 2Ch..2Fh like this. */
    @Test
    fun ultralightCKeyPageOrder() {
        assertEquals(
            "07060504" + "03020100" + "0f0e0d0c" + "0b0a0908",
            TagKey.ultralightCKeyPages(hex("000102030405060708090a0b0c0d0e0f")).hex(),
        )
    }

    /** The numerical example in MF0ICU2 section 7.5.6 (Table 9), from the reader's side. */
    @Test
    fun ultralightCAuthenticationExample() {
        val key = hex("49454d4b41455242214e4143554f5946")
        val auth = UltralightCAuth(key, hex("a8af3b256c75ed40"))
        assertEquals("0a638559fc7737f9f15d7862ebbe967a", auth.answer(hex("577293fd2f34ca51")).hex())
        assertTrue(auth.verify(hex("3b884fa07c137ce1")))
        assertFalse(auth.verify(hex("3b884fa07c137ce0")))
        auth.wipe()
    }

    @Test
    fun ultralightCAuthenticationRejectsAnotherKey() {
        val auth = UltralightCAuth(hex("00112233445566778899aabbccddeeff"), hex("a8af3b256c75ed40"))
        auth.answer(hex("577293fd2f34ca51"))
        assertFalse(auth.verify(hex("3b884fa07c137ce1")))
    }

    @Test
    fun ulcKeyIsTheFirstHalf() {
        val s = ByteArray(32) { it.toByte() }
        assertArrayEquals(s.copyOfRange(0, 16), TagKey.ultralightCKey(s))
        assertEquals("10111213", TagKey.ntagPwd(s).hex())
        assertEquals("1415", TagKey.ntagPack(s).hex())
    }
}
