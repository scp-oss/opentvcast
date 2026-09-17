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

/**
 * Identifies a casting protocol.
 *
 * Deliberately a value class rather than an `enum` or a `sealed` hierarchy:
 * either of those would weld the list of supported protocols into `:core`, so
 * that adding a protocol means editing this file. The upstream project this is
 * derived from used `enum Protocol { AIRPLAY, MIRACAST, CAST }`, which is
 * exactly why adding a receiver there meant touching the service, the enum and
 * the UI card every single time.
 *
 * Well-known kinds are declared as constants for convenience only — a protocol
 * module is free to define its own.
 */
@JvmInline
value class ProtocolKind(val id: String) {

    companion object {
        val AIRPLAY: ProtocolKind = ProtocolKind("airplay")
        val DLNA: ProtocolKind = ProtocolKind("dlna")
    }

    override fun toString(): String = id
}

/**
 * What a protocol can do, as reported to the UI.
 *
 * The home screen renders protocol cards and the settings screen renders
 * enable/disable switches purely from this data, so neither screen needs a
 * `when (kind)` branch. That is what keeps "add a protocol" from becoming
 * "edit every screen".
 */
data class ProtocolCapabilities(
    val kind: ProtocolKind,
    /** Stable, non-localised label; the UI resolves a string resource by kind. */
    val displayName: String,
    val supportsVideo: Boolean,
    val supportsAudio: Boolean,
    val supportsPhoto: Boolean,
    /** Whether the TV remote can drive the sender's playback (AirPlay DACP). */
    val supportsRemoteControl: Boolean,
    /** Whether the protocol can require a PIN before a session is accepted. */
    val supportsPinAuth: Boolean = false,
    /** Whether the protocol can require a resolution preference (mirroring). */
    val supportsResolutionPreference: Boolean = false,
    /**
     * Ports this protocol would like to own. They are *requests*, not
     * guarantees: [tv.opentvcast.core.net.PortAllocator] may substitute a
     * dynamic port when one is taken, and the implementation must publish
     * whatever it actually got.
     */
    val preferredPorts: Set<Int> = emptySet(),
)

/** Lifecycle state of a [ProtocolReceiver]. */
enum class ProtocolState {
    /** Turned off in settings, or never started. */
    DISABLED,

    /** Advertising and waiting for a sender. */
    ADVERTISING,

    /** A sender is negotiating; not yet streaming. */
    CONNECTING,

    /** A session is live. */
    CONNECTED,

    /** Failed to bind, advertise, or crashed. Carries no message; see [CastEvent.Error]. */
    ERROR,
}
