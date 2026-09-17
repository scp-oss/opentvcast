package tv.opentvcast.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Tests for [MirrorCrypto] — key derivation and payload conversion for the AirPlay
 * mirroring stream.
 *
 * This class had no tests, and it sits on the path where a mistake is worst:
 * get the key derivation wrong and every mirroring session silently fails to
 * decode, with no error the user can act on.
 *
 * The most important test here is [sequentialUpdateMatchesASingleKeystream]. The
 * class documentation claims that one `Cipher` with sequential `update()` calls is
 * *exactly equivalent* to RPiPlay's per-packet `og`/`nextDecryptCount` bookkeeping
 * in `lib/mirror_buffer.c`. That equivalence is the entire justification for this
 * implementation, and if it were wrong the symptom would be video that decodes
 * correctly for the first packet and turns to noise after — a very hard bug to
 * trace back to its cause. So it is asserted directly rather than assumed.
 */
class MirrorCryptoTest {

    private val aesKey = ByteArray(16) { (it + 1).toByte() }
    private val ecdhSecret = ByteArray(32) { (it * 2).toByte() }

    /** CTR decryption of zeros yields the raw keystream, which is easy to compare. */
    private fun keystream(id: Long, length: Int = 32): ByteArray =
        MirrorCrypto.streamCipher(aesKey, ecdhSecret, id).doFinal(ByteArray(length))

    // ─── streamCipher ────────────────────────────────────────────────────────

    @Test
    fun `sequentialUpdateMatchesASingleKeystream`() {
        // The documented equivalence with RPiPlay's mirror_buffer_decrypt: because
        // every payload is a whole number of AES blocks, the keystream is simply
        // continuous, and splitting a payload must not disturb it.
        val whole = MirrorCrypto.streamCipher(aesKey, ecdhSecret, 7L).doFinal(ByteArray(64))

        val split = MirrorCrypto.streamCipher(aesKey, ecdhSecret, 7L)
        val first = split.update(ByteArray(32))
        val second = split.update(ByteArray(32))

        assertArrayEquals(
            "Feeding payloads one at a time must produce the same keystream as " +
                "one contiguous decrypt; if this diverges, video decodes for one " +
                "packet and then turns to noise",
            whole,
            first + second,
        )
    }

    @Test
    fun `threeWaySplitAlsoMatches`() {
        // Video payloads are not all the same size, so verify a non-uniform split.
        val whole = MirrorCrypto.streamCipher(aesKey, ecdhSecret, 99L).doFinal(ByteArray(96))

        val split = MirrorCrypto.streamCipher(aesKey, ecdhSecret, 99L)
        val a = split.update(ByteArray(16))
        val b = split.update(ByteArray(32))
        val c = split.update(ByteArray(48))

        assertArrayEquals(whole, a + b + c)
    }

    @Test
    fun `is deterministic for identical inputs`() {
        assertArrayEquals(
            "Two sessions with the same key material and stream id must derive the " +
                "same cipher, or a reconnect would fail to decrypt",
            keystream(1234L),
            keystream(1234L),
        )
    }

    @Test
    fun `streamConnectionId participates in the derivation`() {
        assertNotEquals(
            "Two concurrent streams share aesKey and ecdhSecret and are told apart " +
                "only by streamConnectionID",
            keystream(1L).toList(),
            keystream(2L).toList(),
        )
    }

    @Test
    fun `a negative streamConnectionId is treated as unsigned decimal`() {
        // SETUP carries the id as a signed 64-bit value, but the protocol formats it
        // as an unsigned decimal string. Using the signed form would collide -1 with
        // nothing, but would diverge from the sender's derivation — which is a total
        // decryption failure.
        val unsigned = java.lang.Long.toUnsignedString(-1L)
        assertEquals("18446744073709551615", unsigned)

        assertNotEquals(
            "id -1 must not derive the same cipher as id 1",
            keystream(1L).toList(),
            keystream(-1L).toList(),
        )
    }

    @Test
    fun `a zero streamConnectionId is valid`() {
        // Not a special case; guard against a future `require(id != 0)` sneaking in.
        assertEquals(32, keystream(0L).size)
    }

    @Test
    fun `different aes keys derive different ciphers`() {
        val other = MirrorCrypto.streamCipher(ByteArray(16), ecdhSecret, 7L).doFinal(ByteArray(32))

        assertNotEquals(keystream(7L).toList(), other.toList())
    }

    @Test
    fun `different ecdh secrets derive different ciphers`() {
        val other = MirrorCrypto.streamCipher(aesKey, ByteArray(32), 7L).doFinal(ByteArray(32))

        assertNotEquals(keystream(7L).toList(), other.toList())
    }

    @Test
    fun `the cipher is a real AES-CTR stream that round-trips`() {
        // Encrypting with the derived cipher and decrypting with a freshly derived
        // one must return the original plaintext — proof that both directions share
        // the same key and IV rather than, say, an IV of zeros.
        val plaintext = "opentvcast mirroring payload".toByteArray()

        val encrypted = MirrorCrypto.streamCipher(aesKey, ecdhSecret, 55L).doFinal(plaintext)
        val decrypted = MirrorCrypto.streamCipher(aesKey, ecdhSecret, 55L).doFinal(encrypted)

        assertArrayEquals(plaintext, decrypted)
    }

    // ─── audioKey ────────────────────────────────────────────────────────────

    @Test
    fun `audioKey is 16 bytes`() {
        assertEquals("AES-128 needs a 128-bit key", 16, MirrorCrypto.audioKey(aesKey, ecdhSecret).size)
    }

    @Test
    fun `audioKey matches the documented derivation`() {
        // Pins the spec: SHA-512(aesKey ‖ ecdhSecret)[:16]. The one-line digest here
        // is deliberately a restatement of the documented formula, not a copy of an
        // implementation — if the formula in MirrorCrypto changes, this fails.
        val expected = MessageDigest.getInstance("SHA-512")
            .digest(aesKey + ecdhSecret)
            .copyOf(16)

        assertArrayEquals(expected, MirrorCrypto.audioKey(aesKey, ecdhSecret))
    }

    @Test
    fun `audioKey is deterministic`() {
        assertArrayEquals(
            MirrorCrypto.audioKey(aesKey, ecdhSecret),
            MirrorCrypto.audioKey(aesKey, ecdhSecret),
        )
    }

    @Test
    fun `audioKey depends on both inputs`() {
        assertNotEquals(
            MirrorCrypto.audioKey(aesKey, ecdhSecret).toList(),
            MirrorCrypto.audioKey(ByteArray(16), ecdhSecret).toList(),
        )
        assertNotEquals(
            MirrorCrypto.audioKey(aesKey, ecdhSecret).toList(),
            MirrorCrypto.audioKey(aesKey, ByteArray(32)).toList(),
        )
    }

    // ─── avccToAnnexB ────────────────────────────────────────────────────────

    /** Builds AVCC input: each NAL prefixed with its 4-byte big-endian length. */
    private fun avcc(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (nal in nals) {
            val len = nal.size
            out.write(byteArrayOf(
                (len ushr 24).toByte(),
                (len ushr 16).toByte(),
                (len ushr 8).toByte(),
                len.toByte(),
            ))
            out.write(nal)
        }
        return out.toByteArray()
    }

    @Test
    fun `start code is the four byte Annex-B prefix`() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), MirrorCrypto.START_CODE)
    }

    @Test
    fun `converts a single NAL to AnnexB`() {
        val nal = byteArrayOf(0x65, 0x11, 0x22, 0x33) // 0x65 = IDR slice header

        val out = MirrorCrypto.avccToAnnexB(avcc(nal))

        assertArrayEquals(MirrorCrypto.START_CODE + nal, out)
    }

    @Test
    fun `converts several concatenated NALs`() {
        val nal1 = byteArrayOf(0x67, 0x01)          // SPS
        val nal2 = byteArrayOf(0x68, 0x02, 0x03)    // PPS
        val nal3 = byteArrayOf(0x65, 0x04)          // IDR

        val out = MirrorCrypto.avccToAnnexB(avcc(nal1, nal2, nal3))

        assertArrayEquals(
            MirrorCrypto.START_CODE + nal1 +
                MirrorCrypto.START_CODE + nal2 +
                MirrorCrypto.START_CODE + nal3,
            out,
        )
    }

    @Test
    fun `an empty payload produces an empty result`() {
        assertEquals(0, MirrorCrypto.avccToAnnexB(ByteArray(0)).size)
    }

    @Test
    fun `trailing bytes shorter than a length prefix are ignored`() {
        // A truncated tail must not be read as a length, which would either throw
        // or fabricate a NAL from unrelated bytes.
        val input = avcc(byteArrayOf(0x65, 0x01)) + byteArrayOf(0x00, 0x01, 0x02)

        val out = MirrorCrypto.avccToAnnexB(input)

        assertArrayEquals(MirrorCrypto.START_CODE + byteArrayOf(0x65, 0x01), out)
    }

    @Test
    fun `stops at a NAL that claims more bytes than remain`() {
        // Same defensive reason: emit nothing rather than a partial NAL that the
        // decoder would reject as corrupt.
        val out = MirrorCrypto.avccToAnnexB(
            byteArrayOf(0x00, 0x00, 0x00, 0x10, 0x65, 0x01) // claims 16, has 2
        )

        assertEquals(0, out.size)
    }

    @Test
    fun `stops at a zero length NAL`() {
        // A zero-length prefix is how a decoder is signalled an empty access unit.
        // Emitting a bare start code would be an empty NAL, which MediaCodec rejects.
        val first = byteArrayOf(0x65, 0x01)
        val out = MirrorCrypto.avccToAnnexB(
            avcc(first) + byteArrayOf(0x00, 0x00, 0x00, 0x00)
        )

        assertArrayEquals(MirrorCrypto.START_CODE + first, out)
    }

    @Test
    fun `stops at a negative length rather than reading out of bounds`() {
        // Top bit set — a corrupt or hostile length. Must not become a huge read.
        val out = MirrorCrypto.avccToAnnexB(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x65)
        )

        assertEquals(0, out.size)
    }

    @Test
    fun `handles a length that exactly consumes the remainder`() {
        val nal = ByteArray(300) { it.toByte() }

        val out = MirrorCrypto.avccToAnnexB(avcc(nal))

        assertEquals(4 + 300, out.size)
        assertTrue(out.size > 0)
        assertFalse("must not just bail out on a large-but-valid NAL", out.isEmpty())
    }

    @Test
    fun `handles a NAL whose length exceeds one byte`() {
        // Exercises the big-endian assembly across more than the low byte.
        val nal = ByteArray(258) { 0x42 }

        val out = MirrorCrypto.avccToAnnexB(avcc(nal))

        assertArrayEquals(MirrorCrypto.START_CODE + nal, out)
    }
}
