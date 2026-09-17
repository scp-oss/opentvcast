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

import tv.opentvcast.airplay.handshake.LegacyPairSetupPin
import tv.opentvcast.airplay.handshake.PairingSession
import tv.opentvcast.airplay.handshake.PlistCodec
import tv.opentvcast.util.Logger
import java.security.SecureRandom

/**
 * The pairing / PIN state machine behind the AirPlay 2 handshake endpoints:
 * `/pair-setup`, `/pair-verify`, `/pair-pin-start`, `/pair-setup-pin`.
 *
 * Extracted from [RtspHandler] so the decision table — what is answered, what
 * is refused, when the lockout bites — has direct tests
 * ([tv.opentvcast.airplay.RtspPairingTest]). The cryptographic exchanges
 * themselves stay in [PairingSession] and [LegacyPairSetupPin], which have
 * their own byte-level tests.
 *
 * ### State lifetime
 *
 * `legacyPin` (the primed SRP session) and `pinPaired` deliberately survive
 * connection changes: macOS runs the PIN handshake across SEPARATE TCP
 * connections (`/pair-pin-start` on one, `/pair-setup-pin` on the next), so
 * both the PIN/verifier and the "paired" flag must survive a reconnect. This
 * object therefore lives for the receiver's lifetime, not the connection's.
 *
 * ### The lockout
 *
 * A 4-digit PIN is low-entropy; the load-bearing defense is a hard cap on
 * failed attempts, tracked through [PairAttemptLimiter]. Production wires
 * this to the persistent [tv.opentvcast.airplay.handshake.PairingStore] so a
 * restart does not reset the counter.
 */
internal class RtspPairing(
    private val pinAuthEnabled: Boolean,
    private val attempts: PairAttemptLimiter?,
    private val onShowPin: (String?) -> Unit,
    /** The connection-level Ed25519/SRP session; created fresh per control connection. */
    private val sessionProvider: () -> PairingSession,
    private val serverEdPublic: () -> ByteArray,
) {

    /** Narrow view of the persistent attempt counter — keeps this class JVM-testable. */
    interface PairAttemptLimiter {
        fun failedAttempts(): Int
        fun recordFailedAttempt(): Int
        fun resetFailedAttempts()
    }

    /**
     * True once a controller has completed SRP PIN pairing. Until then, with PIN
     * auth on, pair-verify is rejected — which is what makes macOS fall back to
     * the /pair-pin-start + /pair-setup-pin flow. `internal var` as a test seam;
     * production code only ever sets it from [pairSetupPin].
     */
    internal var pinPaired = false

    private var legacyPin: LegacyPairSetupPin? = null

    /**
     * POST /pair-setup. With PIN auth off (default) this is the anonymous Ed25519
     * exchange. With PIN auth on, the PIN layer runs on /pair-setup-pin — this
     * endpoint stays the anonymous key exchange either way.
     */
    fun pairSetup(request: RtspRequest): RtspResponse = try {
        val body = sessionProvider().pairSetup(request.bodyBytes)
        Logger.i("pair-setup OK (returned ${body.size}-byte public key)")
        RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
    } catch (e: Exception) {
        Logger.e("pair-setup failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /**
     * POST /pair-verify — the anonymous ECDH handshake. With PIN auth on this is
     * refused until the controller has PIN-paired; macOS responds to the 470 by
     * starting the PIN flow (/pair-pin-start → /pair-setup-pin).
     */
    fun pairVerify(request: RtspRequest): RtspResponse {
        if (pinAuthEnabled && !pinPaired) {
            Logger.i("pair-verify rejected — PIN pairing required first (triggers /pair-pin-start)")
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        return try {
            val body = sessionProvider().pairVerify(request.bodyBytes)
            Logger.i("pair-verify ${if (request.bodyBytes.firstOrNull()?.toInt() == 1) "M1" else "M2"} OK (returned ${body.size} bytes)")
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            Logger.e("pair-verify failed", e)
            RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
    }

    /**
     * POST /pair-pin-start — macOS asks the receiver to begin PIN pairing and
     * display the code. Generates + shows the PIN, primes the SRP session, and
     * replies 200; the SRP exchange follows on /pair-setup-pin.
     */
    fun pairPinStart(request: RtspRequest): RtspResponse {
        if (!pinAuthEnabled) return unimplemented(request)
        if (locked()) return refusal(request)
        newSrpSession()
        Logger.i("pair-pin-start — PIN shown, SRP session primed")
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * POST /pair-setup-pin — the legacy AirPlay plist SRP exchange. Step 1
     * ({method,user}) returns {pk,salt}; step 2 ({pk,proof}) verifies the PIN and
     * returns {proof}; step 3 completes. On success the controller is allowed past
     * pair-verify. Bounded by the failed-attempt lockout.
     */
    fun pairSetupPin(request: RtspRequest): RtspResponse {
        if (!pinAuthEnabled) return unimplemented(request)
        if (locked()) {
            onShowPin(null)
            return refusal(request)
        }
        return try {
            val plist = PlistCodec.decode(request.bodyBytes)
            if (legacyPin == null) newSrpSession()   // step 1 may arrive without a prior /pair-pin-start
            val result = legacyPin!!.handle(plist)
            if (result.failed) {
                val n = attempts?.recordFailedAttempt()
                Logger.w("pair-setup-pin attempt failed ($n/$MAX_PAIR_ATTEMPTS)")
                onShowPin(null); legacyPin = null
                return refusal(request)
            }
            if (result.complete) {
                attempts?.resetFailedAttempts()   // legitimate pairing clears the lockout counter
                pinPaired = true                  // now allow pair-verify → streaming proceeds
                onShowPin(null); legacyPin = null
                Logger.i("PIN pairing complete — pair-verify now permitted")
            }
            RtspResponse(
                200, "OK",
                bodyBytes = PlistCodec.encode(result.reply!!),
                contentType = "application/x-apple-binary-plist",
                protocol = request.responseProtocol()
            )
        } catch (e: Exception) {
            Logger.e("pair-setup-pin failed", e)
            onShowPin(null); legacyPin = null
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun locked(): Boolean =
        (attempts?.failedAttempts() ?: 0) >= MAX_PAIR_ATTEMPTS

    private fun refusal(request: RtspRequest): RtspResponse {
        Logger.w("pairing refused — PIN auth locked ($MAX_PAIR_ATTEMPTS failed attempts)")
        return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
    }

    private fun unimplemented(request: RtspRequest): RtspResponse {
        Logger.w("PIN endpoint ignored — PIN auth is disabled (${request.method} ${request.uri})")
        return RtspResponse(501, "Not Implemented", protocol = request.responseProtocol())
    }

    /** Generates a fresh uniform 4-digit PIN, shows it on the TV, and primes the legacy SRP session. */
    private fun newSrpSession() {
        val pin = "%0${PIN_DIGITS}d".format(SecureRandom().nextInt(PIN_SPACE))
        onShowPin(pin)
        legacyPin = LegacyPairSetupPin(pin, serverEdPublic())
    }

    companion object {
        // macOS's AirPlay code-entry field is exactly 4 digits, so the PIN must be
        // 4 digits to be enterable. A 4-digit space is low-entropy, so the
        // load-bearing defense is the lockout (uniform random + hard attempt cap,
        // NOT length). The PIN is still uniformly random — no biased truncation.
        const val PIN_DIGITS = 4
        const val PIN_SPACE = 10_000            // 10^PIN_DIGITS
        const val MAX_PAIR_ATTEMPTS = 10

        private const val OCTET_STREAM = "application/octet-stream"
    }
}
