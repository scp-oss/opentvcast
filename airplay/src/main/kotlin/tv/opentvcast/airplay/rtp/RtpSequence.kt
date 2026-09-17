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
 * 16-bit RTP sequence arithmetic.
 *
 * Sequence numbers wrap every 65 536 packets — at mirroring rates, every few
 * minutes — so every comparison has to be a *signed distance* rather than a
 * subtraction. `0 - 65535` is `+1`, not `-65535`; getting that backwards makes
 * audio reorder or stall briefly after each wrap, nowhere near the cause.
 *
 * Extracted from `AudioStreamServer`, where it and the reorder buffer were
 * private and therefore untestable — the one part of that file most likely to
 * break subtly and least likely to be noticed.
 */
object RtpSequence {

    /** The sequence space: 0 … 65535. */
    const val MODULO = 0x10000

    /**
     * Signed distance `a - b`, normalised into `(-32768, 32767]`.
     *
     * Positive means `a` is ahead of `b` in stream order; the result is what
     * "most recent" means for wraparound comparisons.
     */
    fun diff(a: Int, b: Int): Int = (((a - b) and 0xFFFF) xor 0x8000) - 0x8000

    /** The next sequence number, wrapping to 0 at the end of the space. */
    fun next(seq: Int): Int = (seq + 1) and 0xFFFF
}

/**
 * Remembers recently seen sequence numbers so a retransmission is not played
 * twice — senders resend both on their own initiative and in answer to our
 * resend requests, and a duplicate frame is an audible click.
 *
 * The window is fixed rather than unbounded: memory is bounded, and a sequence
 * arriving again after this many newer packets is treated as new. That is the
 * behaviour the inline version had, kept deliberately.
 */
class RtpDuplicateWindow(private val window: Int = DEFAULT_WINDOW) {

    private val order = java.util.ArrayDeque<Int>()
    private val seen = HashSet<Int>()

    /** Entries currently remembered. */
    val size: Int get() = seen.size

    /** `true` when [seq] was already processed; records it otherwise. */
    fun isDuplicate(seq: Int): Boolean {
        if (!seen.add(seq)) return true
        order.addLast(seq)
        if (order.size > window) seen.remove(order.removeFirst())
        return false
    }

    companion object {
        /**
         * Matches the value the inline implementation shipped, kept exactly: at
         * ~92 packets/s this is ~11 s — far longer than any retransmit gap, far
         * shorter than the 65 536-packet (~12 min) wrap, so no false duplicates
         * from wraparound.
         *
         * Changing it is a deliberate memory/robustness trade, not a tweak: the
         * test pins the number so the change has to be argued for.
         */
        const val DEFAULT_WINDOW = 1024
    }
}
