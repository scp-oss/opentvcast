package tv.opentvcast.airplay

/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

/**
 * The three UDP/TCP ports the AirPlay protocol expects. Previously defined
 * twice each in [RtspHandler] and [MdnsService]/[AirPlayReceiver] — a drift
 * would advertise a port nobody listens on. Phase B routes the preferred
 * values through the `PortAllocator`; this object stays the source of truth
 * for *which* ports AirPlay wants.
 *
 * Public because the *service* layer owns the allocator and passes the granted
 * values back in — the protocol module consumes ports, it does not discover them.
 */
object AirPlayPorts {
    /** TCP port the RTSP listener binds and mDNS advertises. */
    const val RTSP = 7000

    /** UDP port for RAOP audio RTP. */
    const val AUDIO_RTP = 6001

    /** UDP port for NTP timing. */
    const val TIMING = 6002
}
