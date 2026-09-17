package tv.opentvcast.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure parse/inspect seams of [MirrorStreamServer]: the avcC
 * (SPS/PPS) config parse and the Annex-B keyframe sniff. Both sit directly on
 * the wire format macOS sends; a parse regression means "no picture" or
 * "decodes garbage after a dropped frame", neither of which surfaces as an
 * error anywhere.
 */
class MirrorStreamServerTest {

    /** Byte literal helper — keeps the arrays readable without .toByte() noise. */
    private fun b(v: Int): Byte = v.toByte()

    // ─── avcC (payload type 1) parsing ───────────────────────────────────────

    /** Builds the avcC layout MirrorStreamServer.parseConfig expects. */
    private fun avcC(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteArray(6))                                    // [0..5] ignored
        out.write((sps.size shr 8) and 0xFF)                       // [6]   SPS length hi
        out.write(sps.size and 0xFF)                               // [7]   SPS length lo
        out.write(sps)                                             // [8..] SPS
        out.write(1)                                               // PPS count (1)
        out.write((pps.size shr 8) and 0xFF)                       // PPS length hi
        out.write(pps.size and 0xFF)                               // PPS length lo
        out.write(pps)
        return out.toByteArray()
    }

    @Test
    fun `parseConfig extracts SPS and PPS from a well-formed avcC payload`() {
        val sps = byteArrayOf(b(0x67), b(0x64), 0x00, b(0x1F), b(0xAC), b(0xD9))
        val pps = byteArrayOf(b(0x68), b(0xEB), b(0xEC), b(0xB2))

        val config = server().parseConfig(avcC(sps, pps))

        assertTrue(config != null)
        assertArrayEquals(sps, config!!.sps)
        assertArrayEquals(pps, config.pps)
    }

    @Test
    fun `parseConfig returns null for a truncated payload`() {
        assertNull(server().parseConfig(ByteArray(4)))
        // SPS length claims 200 bytes but the payload ends long before.
        val lying = avcC(ByteArray(200), ByteArray(2)).copyOfRange(0, 12)
        assertNull(server().parseConfig(lying))
    }

    // ─── Annex-B keyframe sniff ──────────────────────────────────────────────

    private fun server() = MirrorStreamServer(aesKey(), ecdh(), 1L, { null })

    @Test
    fun `IDR after a 4-byte start code is a keyframe`() {
        val annexB = byteArrayOf(0, 0, 0, 1, b(0x65), b(0x88), b(0x84), b(0x21), b(0xA0))
        assertTrue(server().isKeyframe(annexB))
    }

    @Test
    fun `IDR after a 3-byte start code is a keyframe`() {
        val annexB = byteArrayOf(0, 0, 1, b(0x65), b(0x12), b(0x34))
        assertTrue(server().isKeyframe(annexB))
    }

    @Test
    fun `predicted slices are not keyframes`() {
        // 0x41 = non-IDR slice, 0x01 = P slice, 0x06 = SEI — none may resync.
        for (nalType in listOf(b(0x41), b(0x01), b(0x06))) {
            val annexB = byteArrayOf(0, 0, 0, 1, nalType, b(0x11), b(0x22))
            assertFalse("NAL type ${nalType.toString(16)} must not be a keyframe", server().isKeyframe(annexB))
        }
    }

    @Test
    fun `SEI followed by an IDR is still a keyframe`() {
        // Real streams put AUD/SEI NALs in front of the IDR.
        val annexB = byteArrayOf(
            0, 0, 0, 1, b(0x06), 0x01,          // SEI
            0, 0, 0, 1, b(0x65), b(0x90),       // IDR slice
        )
        assertTrue(server().isKeyframe(annexB))
    }

    @Test
    fun `empty and start-code-only frames are not keyframes`() {
        assertFalse(server().isKeyframe(ByteArray(0)))
        assertFalse(server().isKeyframe(byteArrayOf(0, 0, 0, 1)))
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun aesKey(): ByteArray = ByteArray(16) { (it + 1).toByte() }
    private fun ecdh(): ByteArray = ByteArray(32) { (it * 3).toByte() }
}
