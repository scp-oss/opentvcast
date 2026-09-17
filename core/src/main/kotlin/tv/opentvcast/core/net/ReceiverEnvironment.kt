/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import tv.opentvcast.core.protocol.CastEventBus
import tv.opentvcast.core.surface.SurfaceSink

/**
 * Everything a [tv.opentvcast.core.protocol.ProtocolReceiver] is allowed to
 * know about the platform it runs on.
 *
 * This interface is the single choke point for compatibility behaviour. It
 * exists because upstream protocol classes reached directly for
 * `WifiManager`, `ConnectivityManager` and raw `DatagramSocket` construction,
 * which is precisely how the multicast lock, network-change re-registration and
 * dual-stack support all came to be missing.
 *
 * Instances are supplied by the foreground service; protocol modules never
 * build one themselves.
 */
interface ReceiverEnvironment {

    /**
     * Structured-concurrency scope tied to the foreground service.
     *
     * Carries a `SupervisorJob` so that one protocol crashing does not cancel
     * its siblings.
     */
    val scope: CoroutineScope

    /** Address to advertise, or `null` while offline. */
    val localAddress: StateFlow<java.net.InetAddress?>

    /** Every usable local address, for dual-stack advertisement. */
    val allAddresses: StateFlow<List<java.net.InetAddress>>

    /** Name shown in sender pickers. Already resolved against user settings. */
    val displayName: String

    /** Where a protocol asks for a rendering target. */
    val surfaceSink: SurfaceSink

    /** Reference-counted multicast lock. */
    val multicastLock: MulticastLockHandle

    /** One-shot events that the UI should consume exactly once. */
    val eventBus: CastEventBus

    /**
     * Reserves a port, falling back to an ephemeral one when [preferred] is
     * taken. The returned value — not the requested one — is what must be
     * advertised.
     */
    fun allocatePort(preferred: Int? = null): Int

    /** Returns a port obtained from [allocatePort]. */
    fun releasePort(port: Int)
}
