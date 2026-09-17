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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ProtocolKind], [ProtocolCapabilities], [CastEvent] and the
 * [TransportState] mapping surface.
 *
 * Small types, but they are the vocabulary every protocol module and the UI
 * share. A silent change to `ProtocolKind.AIRPLAY.id` would break settings keys
 * and the event stream without a compile error anywhere.
 */
class ProtocolKindTest {

    @Test
    fun `value class equality is by id, not identity`() {
        assertEquals(ProtocolKind("airplay"), ProtocolKind("airplay"))
        assertNotEquals(ProtocolKind("airplay"), ProtocolKind("dlna"))
    }

    @Test
    fun `well-known constants carry their expected ids`() {
        assertEquals("airplay", ProtocolKind.AIRPLAY.id)
        assertEquals("dlna", ProtocolKind.DLNA.id)
    }

    @Test
    fun `toString exposes the raw id for logs and settings keys`() {
        assertEquals("airplay", ProtocolKind.AIRPLAY.toString())
    }

    @Test
    fun `an unknown protocol is representable without touching this module`() {
        // The whole point of not using an enum: a new protocol needs no edit here.
        val future = ProtocolKind("cast")
        assertEquals("cast", future.id)
        assertNotEquals(ProtocolKind.AIRPLAY, future)
    }

    @Test
    fun `capabilities default to no optional features`() {
        val caps = ProtocolCapabilities(
            kind = ProtocolKind.AIRPLAY,
            displayName = "AirPlay",
            supportsVideo = true,
            supportsAudio = true,
            supportsPhoto = true,
            supportsRemoteControl = true,
        )
        assertEquals(false, caps.supportsPinAuth)
        assertEquals(false, caps.supportsResolutionPreference)
        assertTrue(caps.preferredPorts.isEmpty())
    }

    @Test
    fun `capabilities carry preferred ports as a request, not a guarantee`() {
        val caps = ProtocolCapabilities(
            kind = ProtocolKind.AIRPLAY,
            displayName = "AirPlay",
            supportsVideo = true,
            supportsAudio = true,
            supportsPhoto = true,
            supportsRemoteControl = true,
            preferredPorts = setOf(7000, 6001, 6002),
        )
        assertEquals(setOf(7000, 6001, 6002), caps.preferredPorts)
    }

    @Test
    fun `ProtocolState covers the states the service aggregates`() {
        assertEquals(
            listOf("DISABLED", "ADVERTISING", "CONNECTING", "CONNECTED", "ERROR"),
            ProtocolState.entries.map { it.name },
        )
    }

    @Test
    fun `TransportState is the shared playback vocabulary`() {
        assertEquals(
            listOf("IDLE", "BUFFERING", "PLAYING", "PAUSED", "STOPPED", "ENDED"),
            TransportState.entries.map { it.name },
        )
    }

    @Test
    fun `SessionPhase covers negotiation through teardown`() {
        assertEquals(
            listOf("CONNECTING", "STREAMING", "ENDING", "ENDED"),
            SessionPhase.entries.map { it.name },
        )
    }

    @Test
    fun `TerminationReason distinguishes user action from preemption`() {
        // The UI words these differently, so they must stay distinct.
        assertNotEquals(TerminationReason.USER_STOPPED, TerminationReason.PREEMPTED_BY_NEWER_SENDER)
        assertNotEquals(TerminationReason.SENDER_DISCONNECTED, TerminationReason.NETWORK_CHANGED)
    }

    @Test
    fun `ErrorCode enumerates the failure classes the UI must word`() {
        assertEquals(
            listOf(
                "PORT_UNAVAILABLE", "DISCOVERY_FAILED", "HANDSHAKE_FAILED",
                "UNSUPPORTED_MEDIA", "STREAM_INTERRUPTED", "PERMISSION_MISSING", "UNKNOWN",
            ),
            ErrorCode.entries.map { it.name },
        )
    }
}
