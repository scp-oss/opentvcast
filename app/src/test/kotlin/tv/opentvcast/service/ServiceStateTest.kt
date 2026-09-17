/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ServiceState] and [ActiveConnection] that assert something the
 * compiler does not already guarantee.
 *
 * WHAT THIS FILE USED TO BE, AND WHY IT SHRANK: it carried ~25 assertions of the
 * form "`ServiceState.Running` is a `ServiceState.Running`", "a data class holds
 * the value it was given", and `ProtocolState.valueOf("DISABLED") ==
 * ProtocolState.DISABLED`. None of those can fail unless the code stops
 * compiling, so passing them proved nothing while making the suite look bigger
 * than it is. What remains is the part with consequences — and one new test, for
 * a defect this review turned up.
 */
class ServiceStateTest {

    // ─── Protocol roster ─────────────────────────────────────────────────────

    @Test
    fun `Protocol exposes exactly the protocols v1 ships`() {
        // ADR-001: AirPlay, plus DLNA. Miracast and Cast are out of scope rather
        // than merely disabled, so re-adding one should have to break a test.
        assertEquals(listOf(Protocol.AIRPLAY, Protocol.DLNA), Protocol.entries.toList())
    }

    // ─── ActiveConnection.durationSeconds ────────────────────────────────────

    @Test
    fun `durationSeconds counts from the connection's start`() {
        val conn = ActiveConnection(
            senderName = "MacBook",
            protocol = Protocol.AIRPLAY,
            startedAt = System.currentTimeMillis() - 5_000L,
        )

        assertTrue("~5 s elapsed, got ${conn.durationSeconds}", conn.durationSeconds >= 4L)
    }

    @Test
    fun `durationSeconds is zero for a connection that just started`() {
        val conn = ActiveConnection(
            senderName = "MacBook",
            protocol = Protocol.AIRPLAY,
            startedAt = System.currentTimeMillis(),
        )

        assertTrue(conn.durationSeconds >= 0L)
    }

    @Test
    fun `durationSeconds never goes negative when the wall clock jumps backwards`() {
        // A TV that has been asleep gets its clock corrected (NTP, or the user
        // fixing it), and `System.currentTimeMillis()` can move backwards. The
        // start timestamp then lies in the future, and the naive subtraction
        // reports a negative age — which the notification and the home-screen
        // card would happily display.
        val conn = ActiveConnection(
            senderName = "MacBook",
            protocol = Protocol.AIRPLAY,
            startedAt = System.currentTimeMillis() + 60_000L,
        )

        assertEquals(0L, conn.durationSeconds)
    }
}
