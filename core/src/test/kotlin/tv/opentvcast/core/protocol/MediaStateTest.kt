/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for [MediaState] and [MediaMeta].
 *
 * Both carry hand-written invariants — `require` bounds and a manual
 * `equals`/`hashCode` over a `ByteArray` field — which are exactly the places
 * where a silent mistake turns into a wrong UI value or a broken collection
 * lookup rather than a compile error.
 */
class MediaStateTest {

    private fun state(
        transport: TransportState = TransportState.PLAYING,
        positionMs: Long = 0L,
        durationMs: Long = -1L,
        volume: Float = 1f,
        muted: Boolean = false,
    ) = MediaState(
        transport = transport,
        positionMs = positionMs,
        durationMs = durationMs,
        volume = volume,
        muted = muted,
    )

    // ─── durationMs: -1 means "unknown", anything else must be non-negative ───

    @Test
    fun `accepts -1 as unknown duration`() {
        assertEquals(-1L, state(durationMs = -1L).durationMs)
    }

    @Test
    fun `accepts zero duration`() {
        assertEquals(0L, state(durationMs = 0L).durationMs)
    }

    @Test
    fun `accepts positive duration`() {
        assertEquals(3_600_000L, state(durationMs = 3_600_000L).durationMs)
    }

    @Test
    fun `rejects negative duration other than -1`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            state(durationMs = -2L)
        }
        assertEquals(true, thrown.message!!.contains("durationMs"))
    }

    // ─── volume must stay inside 0..1 ────────────────────────────────────────

    @Test
    fun `accepts volume at both bounds`() {
        assertEquals(0f, state(volume = 0f).volume)
        assertEquals(1f, state(volume = 1f).volume)
    }

    @Test
    fun `accepts mid volume`() {
        assertEquals(0.42f, state(volume = 0.42f).volume)
    }

    @Test
    fun `rejects volume below zero`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            state(volume = -0.01f)
        }
        assertEquals(true, thrown.message!!.contains("volume"))
    }

    @Test
    fun `rejects volume above one`() {
        assertThrows(IllegalArgumentException::class.java) {
            state(volume = 1.01f)
        }
    }

    // ─── defaults ───────────────────────────────────────────────────────────

    @Test
    fun `defaults are audio-only and not seekable`() {
        val s = state()
        assertEquals(null, s.videoSize)
        assertEquals(null, s.meta)
        assertEquals(false, s.seekable)
    }

    // ─── MediaMeta equality over a ByteArray field ──────────────────────────

    @Test
    fun `MediaMeta equality compares artwork by content, not identity`() {
        val a = MediaMeta(title = "t", artwork = byteArrayOf(1, 2, 3))
        val b = MediaMeta(title = "t", artwork = byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `MediaMeta inequality detects differing artwork bytes`() {
        val a = MediaMeta(title = "t", artwork = byteArrayOf(1, 2, 3))
        val b = MediaMeta(title = "t", artwork = byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `MediaMeta treats null and absent artwork as equal`() {
        assertEquals(MediaMeta(title = "t"), MediaMeta(title = "t", artwork = null))
    }

    @Test
    fun `MediaMeta compares null artwork against populated artwork as unequal`() {
        assertNotEquals(MediaMeta(title = "t"), MediaMeta(title = "t", artwork = byteArrayOf(9)))
    }

    @Test
    fun `MediaMeta distinguishes metadata fields`() {
        val base = MediaMeta(title = "t", artist = "a", album = "al")
        assertNotEquals(base, base.copy(artist = "other"))
        assertNotEquals(base, base.copy(title = "other"))
        assertNotEquals(base, base.copy(album = "other"))
    }

    @Test
    fun `MediaMeta hash is stable for equal values`() {
        val meta = MediaMeta(title = "t", artist = "a", album = "al", artwork = byteArrayOf(7))
        assertEquals(meta.hashCode(), meta.copy().hashCode())
    }

    // ─── VideoSize ──────────────────────────────────────────────────────────

    @Test
    fun `VideoSize carries dimensions`() {
        val size = VideoSize(1920, 1080)
        assertEquals(1920, size.width)
        assertEquals(1080, size.height)
    }
}
