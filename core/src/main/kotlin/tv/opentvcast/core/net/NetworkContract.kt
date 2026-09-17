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

/**
 * Reference-counted handle on the platform multicast lock.
 *
 * Android filters inbound multicast by default. Without a lock, SSDP M-SEARCH
 * and mDNS queries silently never arrive, and the user sees the app as
 * "not discoverable" — a failure mode that is nearly impossible to attribute
 * without already knowing this. Upstream declared
 * `CHANGE_WIFI_MULTICAST_STATE` in the manifest and then never acquired a lock.
 *
 * Counting matters because both discovery mechanisms need it: if AirPlay
 * released the lock while DLNA was still advertising, DLNA would break. The
 * lock is only truly released when the last holder lets go.
 */
interface MulticastLockHandle {

    /** Acquires one reference. Idempotent per holder tag. */
    fun acquire(owner: String)

    /** Releases one reference for [owner]. No-op if not held. */
    fun release(owner: String)

    /** Whether any reference is currently held. */
    val isHeld: Boolean
}

/**
 * Allocates network ports so that two components — or two receiver apps on the
 * same LAN — cannot silently fight over the same one.
 *
 * Upstream hardcoded 6001/6002 in three separate files and crashed on
 * `BindException` instead of falling back. Here a preferred port is a *request*:
 * if it is taken we hand back an ephemeral port, and the caller is responsible
 * for publishing the real value in its advertisement (mDNS TXT, SSDP LOCATION).
 */
interface PortAllocator {

    /**
     * Reserves a port.
     *
     * @param preferred port to try first, or `null` for a purely dynamic one.
     * @param owner identifying tag, used for diagnostics and leak detection.
     * @return the port that was actually reserved.
     * @throws PortBindException if nothing could be bound.
     */
    fun allocate(preferred: Int? = null, owner: String): Int

    /** Returns a port to the pool. */
    fun release(port: Int, owner: String)

    /** Ports currently reserved, mapped to their owner. Diagnostics only. */
    fun snapshot(): Map<Int, String>
}

/** Thrown when a bindable port cannot be obtained. */
class PortBindException(
    val port: Int?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Chooses which local address the receiver advertises.
 *
 * Televisions frequently have Ethernet and Wi-Fi up at the same time, and
 * Android also exposes virtual interfaces. Upstream simply took the first
 * non-loopback interface, which on some devices selected a container or VPN
 * interface and produced an address no sender could reach.
 */
interface InterfaceSelector {

    /**
     * The address to advertise. `null` when there is no usable network.
     *
     * Preference order: Ethernet over Wi-Fi (wired is more stable), then
     * site-local over global, then IPv4 over IPv6 — IPv4 first because a large
     * installed base of senders still fails to resolve or route `.local` over
     * IPv6, and a receiver that advertises only IPv6 becomes unreachable to them.
     */
    fun selectAddress(): java.net.InetAddress?

    /** Every usable address, IPv4 and IPv6, for dual-stack advertisement. */
    fun allAddresses(): List<java.net.InetAddress>
}

/**
 * Watches for changes in the device's network identity and reports them
 * coalesced.
 *
 * Without this, an IP change leaves mDNS advertising a stale address and SSDP
 * publishing a dead LOCATION: senders still see the device but every connection
 * attempt fails, and only an app restart clears it. Upstream had no network
 * callback at all.
 */
interface NetworkMonitor {

    /** Emits after each coalesced network change. */
    fun start(onChanged: suspend () -> Unit)

    fun stop()
}
