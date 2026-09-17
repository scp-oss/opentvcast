/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.airplay.rtp

/**
 * Reorders UDP audio packets into stream order and reports the gaps worth
 * asking the sender to resend.
 *
 * WHY: RTP runs over UDP, which reorders and drops. Handing packets straight to
 * the decoder produces clicks and dropouts; holding them forever produces
 * silence. The policy is therefore two-sided, and both halves matter:
 *
 * - hold a packet until everything before it has arrived, so the decoder sees a
 *   contiguous stream;
 * - but if a hole does not fill within [maxHold] packets, **skip past it** — a
 *   brief glitch beats indefinite silence.
 *
 * Extracted from `AudioStreamServer` where this was private state guarded by a
 * lock, and therefore untestable. The class is deliberately not thread-safe: the
 * caller owns the lock, exactly as before, and the tests can then drive it
 * without concurrency.
 */
class RtpReorderBuffer(private val maxHold: Int = DEFAULT_MAX_HOLD) {

    /** A range to ask the sender for: [count] packets starting at [startSeq]. */
    data class Resend(val startSeq: Int, val count: Int)

    /** What one [offer] produced. */
    data class Offer(
        /** Packets that became contiguous, in stream order. */
        val released: List<ByteArray>,
        /** A gap worth requesting, or `null`. */
        val resend: Resend?,
    )

    private val pending = HashMap<Int, ByteArray>()

    /** Sequence the stream continues from; negative until the first packet anchors it. */
    private var nextSeq = -1

    /** Highest sequence seen so far; negative until the first packet. */
    private var maxSeq = -1

    /** Number of packets waiting for a hole to be filled. */
    val pendingCount: Int get() = pending.size

    /**
     * Adds one packet.
     *
     * The first packet anchors the stream at its own sequence — a sender may
     * start mid-stream, and that must not look like a 32 000-packet gap.
     */
    fun offer(seq: Int, payload: ByteArray): Offer {
        if (nextSeq < 0) {
            nextSeq = seq
            maxSeq = seq
        }
        // Older than what was already released: a late resend we stopped waiting
        // for. Reopening the stream for it would replay audio.
        if (RtpSequence.diff(seq, nextSeq) < 0) return Offer(emptyList(), null)

        pending[seq] = payload

        // A new forward gap: ask for everything between the old high-water mark
        // and here. Computed before maxSeq moves.
        val resend = if (maxSeq >= 0 && RtpSequence.diff(seq, maxSeq) > 1) {
            Resend(startSeq = RtpSequence.next(maxSeq), count = RtpSequence.diff(seq, maxSeq) - 1)
        } else {
            null
        }
        if (RtpSequence.diff(seq, maxSeq) > 0) maxSeq = seq

        var released = drainContiguous()

        // Give up on a hole that has been open too long: advance past it and
        // release whatever is on the other side.
        if (pending.isNotEmpty() && RtpSequence.diff(maxSeq, nextSeq) > maxHold) {
            while (RtpSequence.diff(maxSeq, nextSeq) > maxHold && !pending.containsKey(nextSeq)) {
                nextSeq = RtpSequence.next(nextSeq)
            }
            released += drainContiguous()
        }

        return Offer(released, resend)
    }

    /**
     * Drops all held packets and forgets the stream position.
     *
     * Called when the server stops: a restarted stream re-anchors on its first
     * packet rather than being compared against the previous session's sequence,
     * which would look like a 30 000-packet gap.
     */
    fun clear() {
        pending.clear()
        nextSeq = -1
        maxSeq = -1
    }

    /** Releases every packet contiguous from [nextSeq], advancing the cursor. */
    private fun drainContiguous(): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        while (true) {
            val payload = pending.remove(nextSeq) ?: break
            out += payload
            nextSeq = RtpSequence.next(nextSeq)
        }
        return out
    }

    companion object {
        /**
         * How far ahead the stream may run before a hole is abandoned.
         *
         * Small enough that a stall is a fraction of a second, large enough to
         * cover a normal reorder — and it also bounds how long the buffer can
         * hold memory hostage to one missing packet.
         */
        const val DEFAULT_MAX_HOLD = 32
    }
}
