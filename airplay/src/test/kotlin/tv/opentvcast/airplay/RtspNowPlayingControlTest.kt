/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RtspNowPlayingControl] — the SET_PARAMETER/GET_PARAMETER pair that
 * carries volume, album artwork and DMAP now-playing metadata during an
 * audio-only RAOP session.
 */
class RtspNowPlayingControlTest {

    private class Harness {
        val volumes = mutableListOf<Float>()
        val artworks = mutableListOf<ByteArray>()
        val metadata = mutableListOf<Triple<String?, String?, String?>>()

        val control = RtspNowPlayingControl(
            onVolume = { volumes.add(it) },
            onArtwork = { artworks.add(it) },
            onNowPlayingMetadata = { t, a, al -> metadata.add(Triple(t, a, al)) },
        )
    }

    private fun parameter(body: String, contentType: String? = null) = RtspRequest(
        method = "SET_PARAMETER",
        uri = "*",
        headers = if (contentType == null) emptyMap() else mapOf("Content-Type" to contentType),
        body = body,
    )

    // ─── Volume ───────────────────────────────────────────────────────────────

    @Test
    fun `a volume SET_PARAMETER reaches the player and is remembered`() {
        val h = Harness()
        val response = h.control.setParameter(parameter("volume: -11.5"))

        assertEquals(200, response.statusCode)
        assertEquals(listOf(-11.5f), h.volumes)
    }

    @Test
    fun `GET_PARAMETER volume echoes the last value the sender set`() {
        val h = Harness()
        h.control.setParameter(parameter("volume: -3.25"))

        val response = h.control.getParameter(
            RtspRequest("GET_PARAMETER", "*", emptyMap(), "volume")
        )

        assertEquals("text/parameters", response.contentType)
        assertTrue(response.body.contains("volume: -3.250000"))
    }

    @Test
    fun `a malformed volume value is ignored without failing the request`() {
        val h = Harness()
        val response = h.control.setParameter(parameter("volume: not-a-number"))

        assertEquals(200, response.statusCode)
        assertTrue(h.volumes.isEmpty())
    }

    @Test
    fun `GET_PARAMETER for anything other than volume is an empty 200`() {
        val h = Harness()
        val response = h.control.getParameter(
            RtspRequest("GET_PARAMETER", "*", emptyMap(), "progress")
        )

        assertEquals(200, response.statusCode)
        assertTrue(response.body.isEmpty())
    }

    // ─── Artwork ──────────────────────────────────────────────────────────────

    @Test
    fun `an image body is forwarded as artwork`() {
        val h = Harness()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val response = h.control.setParameter(
            RtspRequest("SET_PARAMETER", "*", mapOf("Content-Type" to "image/jpeg"), "", bytes)
        )

        assertEquals(200, response.statusCode)
        assertEquals(1, h.artworks.size)
        assertTrue(h.artworks[0].contentEquals(bytes))
    }

    // ─── DMAP metadata ────────────────────────────────────────────────────────

    @Test
    fun `a DMAP body reaches the metadata callback even if fields are absent`() {
        val h = Harness()
        val body = "mlit" + ByteArray(8)   // mlit container tag + junk: the heuristic only inspects the tag
        val response = h.control.setParameter(
            RtspRequest("SET_PARAMETER", "*", emptyMap(), "", body.toByteArray(Charsets.ISO_8859_1))
        )

        assertEquals(200, response.statusCode)
        assertEquals(1, h.metadata.size)
    }
}
