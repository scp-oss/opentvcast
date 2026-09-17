package tv.opentvcast.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for [Codec] — the shared wire-format helpers that replaced four private
 * copies across [PairingStore], [PairingKeys], [FairPlay] and the BE32 readers in
 * DMAP/RTSP handling. These are pure functions on the parse/crypto path, so every
 * behaviour that a sender's bytes could expose is pinned here.
 */
class CodecTest {

    // ─── hex / unhex ─────────────────────────────────────────────────────────

    @Test
    fun `hex encodes lower-case, two chars per byte`() {
        assertEquals("00107f", Codec.hex(byteArrayOf(0x00, 0x10, 0x7f)))
        assertEquals("fffe80", Codec.hex(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte())))
    }

    @Test
    fun `unhex is the inverse of hex`() {
        val original = ByteArray(257) { (it * 37 + 11).toByte() }  // odd size + wrap-around values
        assertArrayEquals(original, Codec.unhex(Codec.hex(original)))
    }

    @Test
    fun `unhex accepts upper and lower case digits`() {
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xcd.toByte()), Codec.unhex("ABcd"))
    }

    @Test
    fun `unhex rejects odd length and bad digits`() {
        assertThrows(IllegalArgumentException::class.java) { Codec.unhex("abc") }
        assertThrows(IllegalArgumentException::class.java) { Codec.unhex("zz") }
        assertThrows(IllegalArgumentException::class.java) { Codec.unhex("0g") }
    }

    @Test
    fun `unhex of empty string is empty`() {
        assertEquals(0, Codec.unhex("").size)
    }

    // ─── be32 ────────────────────────────────────────────────────────────────

    @Test
    fun `be32 reads big-endian unsigned values`() {
        assertEquals(0x00000001L, Codec.be32(byteArrayOf(0, 0, 0, 1)))
        assertEquals(0xDEADBEEFL, Codec.be32(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())))
        assertEquals(0xFFFF_FFFFL, Codec.be32(byteArrayOf(-1, -1, -1, -1)))
    }

    @Test
    fun `be32 honours the offset`() {
        val buf = byteArrayOf(0, 0, 0x12, 0x34)
        assertEquals(0x1234L, Codec.be32(buf, 0))
    }

    @Test
    fun `be32 rejects short buffers`() {
        assertThrows(IllegalArgumentException::class.java) { Codec.be32(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { Codec.be32(byteArrayOf(1, 2, 3, 4), 1) }
    }

    // ─── cbcDecrypt ──────────────────────────────────────────────────────────

    private val key = SecretKeySpec(ByteArray(16) { (it + 3).toByte() }, "AES")
    private val iv = IvParameterSpec(ByteArray(16) { (it * 7).toByte() })

    private fun encryptCbc(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, iv)
        return c.doFinal(plain)
    }

    @Test
    fun `cbcDecrypt round-trips whole blocks`() {
        val plain = ByteArray(48) { (it * 13).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        assertArrayEquals(plain, Codec.cbcDecrypt(cipher, key, iv, encryptCbc(plain)))
    }

    @Test
    fun `cbcDecrypt leaves a trailing sub-block remainder as cleartext`() {
        // RAOP sends whole blocks encrypted plus up to 15 bytes of cleartext tail.
        val blocks = encryptCbc(ByteArray(32) { (it + 1).toByte() })
        val tail = byteArrayOf(0x55, 0x66, 0x77)
        val payload = blocks + tail

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val out = Codec.cbcDecrypt(cipher, key, iv, payload)

        assertArrayEquals(ByteArray(32) { (it + 1).toByte() }, out.copyOfRange(0, 32))
        assertArrayEquals(tail, out.copyOfRange(32, 35))
        assertEquals(35, out.size)
    }

    @Test
    fun `cbcDecrypt passes through data shorter than one block`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        assertArrayEquals(payload, Codec.cbcDecrypt(cipher, key, iv, payload))
    }

    @Test
    fun `each call re-applies the IV — no chaining across packets`() {
        // Two identical packets must decrypt to identical plaintext. If the cipher
        // state chained across calls, the second decryption would be garbage.
        val plain = ByteArray(32) { (it * 5).toByte() }
        val encrypted = encryptCbc(plain)

        val out1 = Codec.cbcDecrypt(Cipher.getInstance("AES/CBC/NoPadding"), key, iv, encrypted)
        val out2 = Codec.cbcDecrypt(Cipher.getInstance("AES/CBC/NoPadding"), key, iv, encrypted)

        assertArrayEquals(plain, out1)
        assertArrayEquals(plain, out2)
    }
}
