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
import tv.opentvcast.core.net.ReceiverEnvironment
import tv.opentvcast.core.surface.SurfaceSink

/**
 * A receiver for one casting protocol (AirPlay, DLNA, ...).
 *
 * Lifecycle: construct -> [start] -> zero or more sessions -> [stop].
 * Implementations are owned by [ReceiverRegistry] and must be safe to
 * [start]/[stop] repeatedly.
 *
 * Two rules that exist because violating them caused real bugs upstream:
 *
 * 1. Implementations must not touch `WifiManager`, `ConnectivityManager` or any
 *    multicast/socket plumbing directly. Everything they need arrives through
 *    [ReceiverEnvironment]. Upstream called these from inside the protocol
 *    classes, which is why multicast locks were never acquired and network
 *    changes were never observed.
 * 2. Implementations must not hold a reference to an Activity or a View. A
 *    rendering target is requested from [SurfaceSink] and released through the
 *    lease; the upstream static `PlayerActivity.mirrorSurface` leaked the
 *    Activity and kept pointing at a destroyed Surface after rotation.
 */
interface ProtocolReceiver {

    /** Static description of what this receiver supports. */
    val capabilities: ProtocolCapabilities

    /** Current lifecycle state. Implementations expose a `MutableStateFlow` as this. */
    val state: StateFlow<ProtocolState>

    /** The live session, or `null` when idle. */
    val session: StateFlow<CastSession?>

    /**
     * Starts advertising and begins accepting connections.
     *
     * Must be idempotent: calling it on an already-started receiver is a no-op.
     *
     * @throws tv.opentvcast.core.net.PortBindException when a required port
     *         cannot be bound even after fallbacks. The registry decides the
     *         retry policy, not the receiver.
     */
    suspend fun start(environment: ReceiverEnvironment)

    /**
     * Stops advertising, terminates the active session if any, and releases
     * every socket, port and decoder. Must leave no lingering state, so that a
     * following [start] behaves like a cold start.
     */
    suspend fun stop()

    /**
     * Called when the device's network identity changed (new IP, Wi-Fi to
     * Ethernet, interface flap).
     *
     * The default is a full restart, which loses an in-flight session. Protocols
     * that can migrate an established connection should override this — for
     * AirPlay that means re-registering mDNS and leaving the RTSP session alone
     * when the socket is still bound to a wildcard address.
     */
    suspend fun onNetworkChanged(environment: ReceiverEnvironment) {
        stop()
        start(environment)
    }
}
