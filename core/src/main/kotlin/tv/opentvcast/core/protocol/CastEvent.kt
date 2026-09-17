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

import kotlinx.coroutines.flow.SharedFlow

/**
 * A one-shot occurrence that the UI should react to exactly once.
 *
 * These are deliberately NOT modelled as state. Upstream carried the pairing PIN
 * as a `StateFlow`, so every time the Activity was recreated — which on TV
 * happens on screensaver, focus loss, or rotation — the PIN dialog reappeared.
 * StateFlow's contract is "here is the latest value", which is exactly right for
 * `state` and exactly wrong for "show this once".
 *
 * Consequently [CastEventBus.events] uses a `SharedFlow` with no replay: a
 * subscriber that attaches after an event was emitted simply does not see it.
 */
sealed interface CastEvent {

    /** A sender is asking to connect. Used for preemption prompts. */
    data class SessionRequested(val kind: ProtocolKind, val sender: SenderInfo) : CastEvent

    /** A session reached the streaming phase. */
    data class SessionEstablished(val id: SessionId, val kind: ProtocolKind) : CastEvent

    /** A session ended. */
    data class SessionTerminated(val id: SessionId, val reason: TerminationReason) : CastEvent

    /** A PIN must be shown to the user so they can type it on the sender. */
    data class PinRequested(val pin: String) : CastEvent

    /** The PIN is no longer needed (paired, cancelled, or sender gone). */
    data object PinDismissed : CastEvent

    /** A still image arrived over AirPlay `/photo`. */
    data class PhotoReceived(val bytes: ByteArray, val mime: String) : CastEvent

    /** The sender cleared the displayed photo. */
    data object PhotoCleared : CastEvent

    /** A recoverable failure worth surfacing. */
    data class Error(val kind: ProtocolKind?, val code: ErrorCode, val detail: String) : CastEvent
}

/** Coarse error classes. The UI picks wording; it never formats raw exceptions. */
enum class ErrorCode {
    /** Could not bind a required port. */
    PORT_UNAVAILABLE,

    /** Discovery (mDNS/SSDP) failed to start. */
    DISCOVERY_FAILED,

    /** Handshake or pairing failed. */
    HANDSHAKE_FAILED,

    /** A decoder could not be created, or the format is unsupported. */
    UNSUPPORTED_MEDIA,

    /** The connection broke mid-stream. */
    STREAM_INTERRUPTED,

    /** Missing a runtime permission the protocol needs. */
    PERMISSION_MISSING,

    /** Anything else. */
    UNKNOWN,
}

/**
 * Delivery channel for [CastEvent].
 */
interface CastEventBus {

    /** Non-replaying stream, so events are consumed once. */
    val events: SharedFlow<CastEvent>

    suspend fun emit(event: CastEvent)
}
