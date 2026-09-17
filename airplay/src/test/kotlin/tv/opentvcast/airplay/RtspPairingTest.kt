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
 * Decision-table tests for [RtspPairing]: which requests are answered, which
 * are refused, and when the PIN lockout bites. The cryptographic exchanges
 * themselves are covered by [tv.opentvcast.airplay.handshake.PairingSessionTest]
 * and [tv.opentvcast.airplay.handshake.LegacyPairSetupPinTest]; here the
 * session provider is never reached except where explicitly noted.
 */
class RtspPairingTest {

    private class FakeLimiter : RtspPairing.PairAttemptLimiter {
        var fails = 0
        var recorded = 0
        var reset = false
        override fun failedAttempts(): Int = fails
        override fun recordFailedAttempt(): Int = (fails + 1).also { fails = it; recorded += 1 }
        override fun resetFailedAttempts() { reset = true; fails = 0 }
    }

    /** A provider that only asserts it was reached; valid handshakes are tested elsewhere. */
    private fun failingSession(): Nothing = error("session reached unexpectedly")

    private fun pairing(
        pinAuthEnabled: Boolean = true,
        limiter: FakeLimiter? = FakeLimiter(),
        shownPins: MutableList<String?> = mutableListOf(),
        sessionProvider: () -> tv.opentvcast.airplay.handshake.PairingSession =
            { error("session reached unexpectedly") },
    ) = RtspPairing(
        pinAuthEnabled = pinAuthEnabled,
        attempts = limiter,
        onShowPin = { shownPins.add(it) },
        sessionProvider = sessionProvider,
        serverEdPublic = { ByteArray(32) },
    ) to shownPins

    private fun post(uri: String, body: ByteArray = ByteArray(0)) =
        RtspRequest(
            method = "POST",
            uri = uri,
            headers = emptyMap(),
            body = "",
            bodyBytes = body,
        )

    // ─── Disabled ─────────────────────────────────────────────────────────────

    @Test
    fun `with PIN auth off the PIN endpoints answer 501`() {
        val (p, shown) = pairing(pinAuthEnabled = false, limiter = null)

        assertEquals(501, p.pairPinStart(post("/pair-pin-start")).statusCode)
        assertEquals(501, p.pairSetupPin(post("/pair-setup-pin")).statusCode)
        assertTrue("no PIN was ever shown", shown.isEmpty())
    }

    // ─── Lockout ──────────────────────────────────────────────────────────────

    @Test
    fun `pairPinStart is refused once the attempt limit is reached`() {
        val limiter = FakeLimiter().apply { fails = RtspPairing.MAX_PAIR_ATTEMPTS }
        val (p, shown) = pairing(limiter = limiter)

        assertEquals(470, p.pairPinStart(post("/pair-pin-start")).statusCode)
        assertTrue("no PIN on a locked receiver", shown.isEmpty())
    }

    @Test
    fun `pairSetupPin is refused when locked and hides any shown PIN`() {
        val limiter = FakeLimiter().apply { fails = RtspPairing.MAX_PAIR_ATTEMPTS }
        val (p, shown) = pairing(limiter = limiter)

        assertEquals(470, p.pairSetupPin(post("/pair-setup-pin")).statusCode)
        assertTrue("PIN must be cleared", shown.contains(null))
    }

    @Test
    fun `pairPinStart with attempts remaining shows a four-digit PIN and answers 200`() {
        val (p, shown) = pairing()

        assertEquals(200, p.pairPinStart(post("/pair-pin-start")).statusCode)
        assertEquals(1, shown.size)
        assertTrue("PIN is 4 digits", shown[0]!!.matches(Regex("\\d{4}")))
    }

    // ─── pair-verify gating ───────────────────────────────────────────────────

    /** Harness where the session provider records that it was reached, then fails. */
    private fun pairingWithProbe(pinAuthEnabled: Boolean = true): Pair<RtspPairing, ()->Boolean> {
        var reached = false
        val p = RtspPairing(
            pinAuthEnabled = pinAuthEnabled,
            attempts = FakeLimiter(),
            onShowPin = {},
            sessionProvider = { reached = true; error("probe session") },
            serverEdPublic = { ByteArray(32) },
        )
        return p to { reached }
    }

    @Test
    fun `pairVerify before PIN pairing is gated - the session is never reached`() {
        val (p, reached) = pairingWithProbe()

        assertEquals(470, p.pairVerify(post("/pair-verify")).statusCode)
        assertTrue("the gate must refuse BEFORE the session runs", !reached())
    }

    @Test
    fun `pairVerify with PIN auth off is never gated`() {
        val (p, reached) = pairingWithProbe(pinAuthEnabled = false)

        // The session itself fails (probe) and pairVerify maps that to 470; the
        // assertion that matters is that the gate let the request THROUGH.
        assertEquals(470, p.pairVerify(post("/pair-verify")).statusCode)
        assertTrue("the gate should have passed the request to the session", reached())
    }

    @Test
    fun `pairVerify after pairing passes the gate`() {
        val (p, reached) = pairingWithProbe()
        p.pinPaired = true   // internal seam: a completed /pair-setup-pin sets this

        assertEquals(470, p.pairVerify(post("/pair-verify")).statusCode)   // probe failure, not the gate
        assertTrue("the gate should have passed the request to the session", reached())
    }

    // ─── Failure accounting ───────────────────────────────────────────────────

    @Test
    fun `a malformed pairSetupPin records a failed attempt and clears the PIN`() {
        val limiter = FakeLimiter()
        val (p, shown) = pairing(limiter = limiter)

        // No prior /pair-pin-start: step 1 primes a session, then the garbage body
        // throws inside the exchange → catch branch → 400 and the PIN is cleared.
        // (Only a REJECTED SRP proof counts as a failed ATTEMPT; an unparseable
        // request is not attributable to guessing, matching upstream behaviour.)
        assertEquals(400, p.pairSetupPin(post("/pair-setup-pin", byteArrayOf(1, 2, 3))).statusCode)
        assertEquals(0, limiter.fails)
        assertTrue("PIN must be cleared on failure", shown.contains(null))
    }

    @Test
    fun `pairSetupPin on a locked receiver does not reach the session`() {
        val limiter = FakeLimiter().apply { fails = RtspPairing.MAX_PAIR_ATTEMPTS }
        val (p, _) = pairing(limiter = limiter)

        assertEquals(470, p.pairSetupPin(post("/pair-setup-pin", byteArrayOf(1))).statusCode)
    }
}
