package tv.opentvcast.airplay.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the RTP sequence-number arithmetic shared by the audio paths.
 *
 * WHY THESE THREE CLASSES EXIST AT ALL: sequence handling was private inside
 * `AudioStreamServer`, which made it untestable — and it is the part most likely
 * to break subtly. A 16-bit sequence wraps every ~65 536 packets, which at
 * mirroring rates is every few minutes; get the wraparound wrong and audio
 * reorders or stalls a little after each wrap, far from the cause.
 */
class RtpSequenceTest {

    @Test
    fun `a plain forward difference is the arithmetic difference`() {
        assertEquals(1, RtpSequence.diff(11, 10))
        assertEquals(10, RtpSequence.diff(20, 10))
        assertEquals(0, RtpSequence.diff(7, 7))
    }

    @Test
    fun `a plain backward difference is negative`() {
        assertEquals(-1, RtpSequence.diff(10, 11))
        assertEquals(-10, RtpSequence.diff(10, 20))
    }

    @Test
    fun `the wrap forward reads as one step`() {
        // 65 535 → 0 is the common case, and it must not look like -65535.
        assertEquals(1, RtpSequence.diff(0, 65535))
        assertEquals(2, RtpSequence.diff(1, 65535))
    }

    @Test
    fun `the wrap backward reads as one step back`() {
        assertEquals(-1, RtpSequence.diff(65535, 0))
    }

    @Test
    fun `the extremes of the representable window are exact`() {
        assertEquals(32767, RtpSequence.diff(32767, 0))
        assertEquals(-32768, RtpSequence.diff(32768, 0))
        // Beyond half the space the sign flips: that is the definition of the
        // "most recent" ordering, not a bug.
        assertEquals(-32767, RtpSequence.diff(32769, 0))
    }

    @Test
    fun `next advances and wraps`() {
        assertEquals(1, RtpSequence.next(0))
        assertEquals(0, RtpSequence.next(65535))
    }

    @Test
    fun `next then diff is always one step forward, at every boundary`() {
        for (seq in listOf(0, 1, 32767, 32768, 65534, 65535)) {
            assertTrue("next($seq) must be ahead of $seq", RtpSequence.diff(RtpSequence.next(seq), seq) == 1)
        }
    }
}

/**
 * Tests for the duplicate-suppression window.
 *
 * Senders retransmit on their own initiative (and in response to our resend
 * requests), so the same sequence number legitimately arrives twice. Playing it
 * twice produces an audible click; suppressing it wrongly drops audio.
 */
class RtpDuplicateWindowTest {

    @Test
    fun `the first sight of a sequence is not a duplicate`() {
        val window = RtpDuplicateWindow(window = 8)

        assertFalse(window.isDuplicate(100))
        assertFalse(window.isDuplicate(101))
    }

    @Test
    fun `a repeat inside the window is a duplicate`() {
        val window = RtpDuplicateWindow(window = 8)
        window.isDuplicate(100)

        assertTrue(window.isDuplicate(100))
        assertTrue("still remembered on a third arrival", window.isDuplicate(100))
    }

    @Test
    fun `a repeat across the wrap is still a duplicate`() {
        val window = RtpDuplicateWindow(window = 8)
        window.isDuplicate(65535)
        window.isDuplicate(0)

        assertTrue(window.isDuplicate(65535))
    }

    @Test
    fun `the window forgets the oldest entry once it is full`() {
        // Deliberate: a fixed window bounds memory. An ancient sequence arriving
        // again after this many packets is treated as new, which is the same
        // behaviour the inline version had.
        val window = RtpDuplicateWindow(window = 4)
        window.isDuplicate(1)
        for (seq in 2..5) window.isDuplicate(seq)

        assertFalse("evicted after four newer packets", window.isDuplicate(1))
        assertTrue("the most recent one is still remembered", window.isDuplicate(5))
    }

    @Test
    fun `the default window is the one the shipped implementation used`() {
        // Pinned on purpose: this value is a memory/robustness trade-off (~11 s of
        // packets), so changing it should require updating this test and saying why.
        assertEquals(1024, RtpDuplicateWindow.DEFAULT_WINDOW)
    }

    @Test
    fun `the default window absorbs a duplicate arriving across a long resend gap`() {
        val window = RtpDuplicateWindow()
        window.isDuplicate(1000)
        for (seq in 1001..1500) window.isDuplicate(seq)

        assertTrue("a retransmit 500 packets later is still the same packet", window.isDuplicate(1000))
    }

    @Test
    fun `the window never grows past its capacity`() {
        val window = RtpDuplicateWindow(window = 4)
        for (seq in 1..100) window.isDuplicate(seq)

        assertEquals(4, window.size)
    }
}

/**
 * Tests for the reorder buffer.
 *
 * UDP reorders freely, so packets are held until they are contiguous and only
 * then handed to the decoder — but not forever: a hole that never fills has to
 * be skipped, because a brief glitch beats indefinite silence. Both halves of
 * that policy are asserted here.
 */
class RtpReorderBufferTest {

    private fun buffer(maxHold: Int = RtpReorderBuffer.DEFAULT_MAX_HOLD) = RtpReorderBuffer(maxHold)

    @Test
    fun `in-order packets are released immediately`() {
        val b = buffer()

        val first = b.offer(1000, "a".toByteArray())

        assertEquals(listOf("a"), first.released.map { String(it) })
        assertEquals(null, first.resend)
    }

    @Test
    fun `the first packet anchors the stream whatever its sequence`() {
        // A sender may start mid-stream; that must not look like a huge gap.
        val b = buffer()

        val result = b.offer(54321, "a".toByteArray())

        assertEquals(1, result.released.size)
        assertNull("no resend for the anchor packet", result.resend)
    }

    @Test
    fun `a gap holds the packet and asks for the missing range`() {
        val b = buffer()
        b.offer(1000, "a".toByteArray())

        val result = b.offer(1003, "d".toByteArray())

        assertTrue("nothing may be released while a hole is open", result.released.isEmpty())
        assertEquals(RtpReorderBuffer.Resend(startSeq = 1001, count = 2), result.resend)
    }

    @Test
    fun `filling the hole releases everything in order`() {
        val b = buffer()
        b.offer(1000, "a".toByteArray())
        b.offer(1003, "d".toByteArray())

        val after = b.offer(1001, "b".toByteArray()).released.map { String(it) }
        val result = b.offer(1002, "c".toByteArray()).released.map { String(it) }

        assertEquals("1001 closes the first half of the gap", listOf("b"), after)
        assertEquals("1002 closes the rest and flushes the held packet", listOf("c", "d"), result)
    }

    @Test
    fun `a packet older than what was already released is ignored`() {
        val b = buffer()
        b.offer(1000, "a".toByteArray())
        b.offer(1001, "b".toByteArray())

        val late = b.offer(1000, "a".toByteArray())

        assertTrue(late.released.isEmpty())
        assertEquals("a late resend we already gave up on must not reopen the stream", null, late.resend)
    }

    @Test
    fun `reordering across the wrap works`() {
        val b = buffer()
        b.offer(65535, "a".toByteArray())

        val held = b.offer(1, "c".toByteArray())   // 0 is missing
        assertEquals(RtpReorderBuffer.Resend(startSeq = 0, count = 1), held.resend)

        val filled = b.offer(0, "b".toByteArray())
        assertEquals(listOf("b", "c"), filled.released.map { String(it) })
    }

    @Test
    fun `a hole that never fills is eventually skipped so playback does not stall`() {
        // maxHold = 3: once the stream is three ahead of the missing packet, give up.
        val b = buffer(maxHold = 3)
        b.offer(1000, "a".toByteArray())

        b.offer(1002, "c".toByteArray())   // 1001 missing
        b.offer(1003, "d".toByteArray())
        b.offer(1004, "e".toByteArray())
        val result = b.offer(1005, "f".toByteArray())

        assertEquals(
            "the stuck hole is skipped and everything after it flows",
            listOf("c", "d", "e", "f"),
            result.released.map { String(it) },
        )
    }

    @Test
    fun `released packets are always in sequence order`() {
        val b = buffer()
        val order = mutableListOf<Int>()
        // Deliver a shuffled burst and record what actually reaches the decoder.
        order += b.offer(1002, byteArrayOf(2)).released.map { it[0].toInt() }
        order += b.offer(1003, byteArrayOf(3)).released.map { it[0].toInt() }
        order += b.offer(1005, byteArrayOf(5)).released.map { it[0].toInt() }
        order += b.offer(1004, byteArrayOf(4)).released.map { it[0].toInt() }   // fills the hole
        order += b.offer(1006, byteArrayOf(6)).released.map { it[0].toInt() }

        assertEquals("packets must reach the decoder in sequence order", listOf(2, 3, 4, 5, 6), order)
    }

    @Test
    fun `clear drops held packets and re-anchors the stream`() {
        val b = buffer()
        b.offer(5000, byteArrayOf(1))
        b.offer(5002, byteArrayOf(3))   // held: 5001 missing

        b.clear()

        assertEquals(0, b.pendingCount)
        // A new session starting far away must anchor, not look like a 30 000-gap.
        val fresh = b.offer(12, byteArrayOf(9))
        assertEquals(listOf(9), fresh.released.map { it[0].toInt() })
        assertNull("a re-anchored stream asks for nothing", fresh.resend)
    }

    @Test
    fun `nothing stays pending once the stream is contiguous`() {
        val b = buffer()
        b.offer(10, byteArrayOf(1))
        b.offer(12, byteArrayOf(3))
        b.offer(11, byteArrayOf(2))

        assertEquals(0, b.pendingCount)
    }
}
