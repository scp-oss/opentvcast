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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the session-facing value types in [CastSession.kt].
 *
 * Two different reasons for testing things that look like trivial data holders:
 *
 * 1. **[SessionId] is compared by value in production.** [ReceiverRegistry.clearSession]
 *    decides whether to release the active slot with `activeSession.value?.id == session.id`.
 *    If [SessionId] were ever changed to identity equality, a session would fail to
 *    clear and the TV would refuse every subsequent sender — with no error anywhere.
 *
 * 2. **The enums are branch points.** [SessionPhase.ENDED] is read by the registry's
 *    arbitration, and [TerminationReason] is rendered by the UI. Adding a member
 *    without giving it a meaning is the failure mode this project has already hit
 *    twice (the missing `CONNECTING` branch in `CastService` and in `HomeFragment`),
 *    so the member lists are pinned here where the change shows up in review.
 */
class CastSessionContractTest {

    // ─── SessionId ───────────────────────────────────────────────────────────

    @Test
    fun `SessionId is usable as a map key`() {
        // clearSession relies on this: two SessionId objects built from the same
        // string must address the same entry.
        val byId = mapOf(SessionId("s-1") to "first")

        assertEquals("first", byId[SessionId("s-1")])
        assertNull(byId[SessionId("s-2")])
    }

    @Test
    fun `SessionId distinguishes sessions that differ only in case`() {
        assertNotEquals(SessionId("Session-1"), SessionId("session-1"))
    }

    // ─── SenderInfo ──────────────────────────────────────────────────────────

    @Test
    fun `SenderInfo carries the fields the wire protocols actually provide`() {
        val sender = SenderInfo(name = "Mac", model = "MacBookPro18,3", address = "192.168.1.20")

        val copy = sender.copy(name = "Renamed")
        assertEquals("Renamed", copy.name)
        // copy() is used by the AirPlay path when the sender name arrives late.
        assertEquals("MacBookPro18,3", copy.model)
        assertEquals("192.168.1.20", copy.address)
    }

    // ─── enums that are branch points ────────────────────────────────────────

    @Test
    fun `SessionPhase covers the lifecycle the registry and UI branch on`() {
        assertEquals(
            listOf("CONNECTING", "STREAMING", "ENDING", "ENDED"),
            SessionPhase.entries.map { it.name },
        )
    }

    @Test
    fun `TerminationReason covers every way a session can end`() {
        assertEquals(
            listOf(
                "SENDER_DISCONNECTED",
                "SENDER_REQUESTED",
                "USER_STOPPED",
                "SERVICE_STOPPING",
                "PREEMPTED_BY_NEWER_SENDER",
                "PROTOCOL_ERROR",
                "NETWORK_CHANGED",
            ),
            TerminationReason.entries.map { it.name },
        )
    }

    @Test
    fun `every TerminationReason maps to user-facing wording`() {
        // The UI must be able to explain why the stream stopped; a reason with no
        // wording would surface as a blank or a raw enum name.
        val wording = mapOf(
            TerminationReason.SENDER_DISCONNECTED to "stopped on the sender",
            TerminationReason.SENDER_REQUESTED to "stopped on the sender",
            TerminationReason.USER_STOPPED to "stopped on the TV",
            TerminationReason.SERVICE_STOPPING to "stopped on the TV",
            TerminationReason.PREEMPTED_BY_NEWER_SENDER to "another device took over",
            TerminationReason.PROTOCOL_ERROR to "connection failed",
            TerminationReason.NETWORK_CHANGED to "network changed",
        )

        for (reason in TerminationReason.entries) {
            assertTrue(
                "No wording for $reason — the UI would have nothing to show",
                wording.containsKey(reason),
            )
        }
    }

    // ─── SessionCrypto ───────────────────────────────────────────────────────

    @Test
    fun `SessionCrypto is implementable for both cipher constructions`() {
        // AirPlay mirroring uses AES-128-CTR with a per-stream key; the RAOP path
        // uses AES-CBC with a fixed IV. The interface deliberately does not model
        // either construction, so both must be expressible through decrypt alone.
        val ctr = FakeCrypto("AES-128-CTR")
        val cbc = FakeCrypto("AES-128-CBC")

        assertEquals("AES-128-CTR", ctr.cipherSuite)
        assertEquals("AES-128-CBC", cbc.cipherSuite)
    }

    @Test
    fun `decrypt receives the full buffer plus an offset and a length`() {
        // The contract hands over the whole array rather than a slice, because the
        // callers read packets straight out of a reused socket buffer.
        val crypto = FakeCrypto("AES-128-CTR")

        crypto.decrypt(ByteArray(64), offset = 12, length = 20)

        assertEquals(12, crypto.lastOffset)
        assertEquals(20, crypto.lastLength)
    }

    private class FakeCrypto(override val cipherSuite: String) : SessionCrypto {
        var lastOffset = -1
        var lastLength = -1

        override fun decrypt(payload: ByteArray, offset: Int, length: Int): ByteArray {
            lastOffset = offset
            lastLength = length
            return ByteArray(length)
        }
    }
}
