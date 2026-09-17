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

import kotlinx.coroutines.flow.StateFlow

/**
 * A single casting session: one sender, from negotiation to teardown.
 *
 * Created and driven by the protocol module; the service and UI only read it.
 * Both AirPlay mirroring and a DLNA push map onto this same shape, which is
 * what lets the UI render "now playing" without knowing which protocol is
 * talking.
 */
interface CastSession {

    val id: SessionId

    val kind: ProtocolKind

    /** Who is connected. */
    val sender: StateFlow<SenderInfo>

    /** Where in the lifecycle this session is. */
    val phase: StateFlow<SessionPhase>

    /** Playback state, or `null` before media starts. */
    val media: StateFlow<MediaState?>

    /** Decryption context, or `null` for an unencrypted session. */
    val crypto: SessionCrypto?

    /**
     * Ends the session.
     *
     * @param reason why it ended, surfaced to the UI for user-facing messaging.
     */
    suspend fun terminate(reason: TerminationReason)
}

@JvmInline
value class SessionId(val value: String)

enum class SessionPhase {
    /** Handshake in progress. */
    CONNECTING,

    /** Media is flowing. */
    STREAMING,

    /** Teardown requested; resources being released. */
    ENDING,

    /** Terminal. The session object must not be reused. */
    ENDED,
}

enum class TerminationReason {
    SENDER_DISCONNECTED,
    SENDER_REQUESTED,
    USER_STOPPED,
    SERVICE_STOPPING,
    PREEMPTED_BY_NEWER_SENDER,
    PROTOCOL_ERROR,
    NETWORK_CHANGED,
}

/** Who is sending to us. */
data class SenderInfo(
    /** Human-readable name reported by the sender ("Alex's iPhone"). */
    val name: String,
    /** Device model or product string, when the protocol provides one. */
    val model: String? = null,
    /** Source address, for preemption matching and diagnostics. */
    val address: String? = null,
)

/**
 * Decryption context for an encrypted stream.
 *
 * An interface rather than a data class because the two protocols that need it
 * use different constructions: AirPlay mirroring uses AES-128-CTR with a
 * per-stream key, while the legacy RAOP path uses AES-CBC with a fixed IV.
 */
interface SessionCrypto {

    /** e.g. `"AES-128-CTR"`, `"AES-128-CBC"`. Diagnostics and debug overlay. */
    val cipherSuite: String

    /**
     * Decrypts [length] bytes of [payload] starting at [offset].
     *
     * Implementations may keep cipher state across calls (CTR keystream, CBC
     * chaining) and therefore are NOT required to be thread-safe. Callers must
     * serialise access — the AirPlay path does so by confining decryption to a
     * single coroutine.
     */
    fun decrypt(payload: ByteArray, offset: Int, length: Int): ByteArray
}
