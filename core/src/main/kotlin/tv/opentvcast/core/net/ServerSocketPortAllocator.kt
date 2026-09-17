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

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers "can this port be bound, and if not, what else is free?".
 *
 * Split out from [ServerSocketPortAllocator] so the allocator's *policy* — who
 * owns what, when to substitute, when to give up — can be tested deterministically
 * while the *mechanism* stays a real socket bind. Tests that need to control
 * availability inject a probe; the shipped probe ([SystemPortProbe]) does the
 * real thing.
 */
fun interface PortProbe {

    /**
     * @param preferred the port to test, or `null` to ask for any free port.
     * @return [preferred] when it can be bound, a free port when [preferred] is
     *         `null`, or `null` when nothing could be bound.
     */
    fun probe(preferred: Int?): Int?
}

/**
 * Binds a real `ServerSocket` to find out.
 *
 * The socket is closed again immediately: the caller is the component that will
 * actually listen, so holding it here would make the port permanently unusable.
 * That leaves a window between the probe and the caller's own bind, which is
 * documented on [ServerSocketPortAllocator.allocate] rather than papered over —
 * the interface returns a port number, not a socket, so no implementation can
 * close that window.
 */
object SystemPortProbe : PortProbe {

    override fun probe(preferred: Int?): Int? {
        if (preferred != null && preferred !in VALID_PORT_RANGE) return null
        return try {
            ServerSocket().use { socket ->
                // backlog 1: we only want to know whether the bind succeeds.
                socket.bind(InetSocketAddress(preferred ?: 0), 1)
                socket.localPort
            }
        } catch (_: Exception) {
            // BindException when taken; SecurityException under a restrictive
            // policy; IOException for a bad address. All mean "not available".
            null
        }
    }

    /** 0 is excluded: it means "any port" to the OS and is not a port we can hand out. */
    private val VALID_PORT_RANGE = 1..65535
}

/**
 * [PortAllocator] that honours a preferred port when it is free and substitutes a
 * dynamic one when it is not.
 *
 * WHY THIS EXISTS: upstream hardcoded 6001 and 6002 in three separate files and
 * let `BindException` escape as a raw `IOException`. Two consequences. A second
 * receiver on the same LAN — or the user's own previous instance, still shutting
 * down — killed the protocol outright instead of negotiating. And because the
 * advertised value was the *requested* port rather than the granted one, a
 * substituted port would have been advertised incorrectly anyway.
 *
 * The contract is explicit that a preferred port is a **request**: callers must
 * publish what [allocate] returned, not what they asked for. mDNS TXT records and
 * SSDP `LOCATION` headers are where that matters.
 *
 * ### The probe window
 *
 * [PortProbe] binds and closes, so the port is briefly unheld between the probe
 * and the caller's own `bind`. This is inherent to an interface whose `allocate`
 * returns an `Int` rather than a socket. In practice the caller binds within
 * microseconds and the alternative — holding the socket — would make the port
 * unusable by its own owner. If a future protocol needs a hard guarantee, the
 * right change is a variant that returns a bound socket, not a stricter probe.
 */
class ServerSocketPortAllocator(
    private val probe: PortProbe = SystemPortProbe,
) : PortAllocator {

    /** Port -> owner. Concurrent because allocation can be driven from several receivers. */
    private val reservations = ConcurrentHashMap<Int, String>()

    /** Serialises the check-then-record sequence, which the map alone does not. */
    private val guard = Any()

    /**
     * @throws PortBindException when neither [preferred] nor any dynamic port could
     *         be bound. The exception carries [preferred] — the port the caller
     *         wanted — because that is the useful thing to report, not the
     *         dynamic port that also failed.
     */
    override fun allocate(preferred: Int?, owner: String): Int {
        synchronized(guard) {
            if (preferred != null && reservations[preferred] == owner) {
                // Idempotent for the same owner: a receiver restarting with the same
                // preferred port gets the same answer rather than a substitute.
                return preferred
            }

            // A port already reserved by someone else is treated as unavailable even
            // before probing: the other owner may not have bound it yet, so the probe
            // would call it free.
            //
            // containsKey, not `in`: on ConcurrentHashMap Kotlin resolves `in` to
            // `contains`, which is containsValue — it would test owners, not ports.
            val preferredIsFree = preferred != null && !reservations.containsKey(preferred)

            if (preferred != null && preferredIsFree) {
                val granted = probe.probe(preferred)
                if (granted != null) {
                    reservations[granted] = owner
                    return granted
                }
            }

            val dynamic = probe.probe(null)
                ?: throw PortBindException(
                    port = preferred,
                    message = if (preferred == null) {
                        "no dynamic port could be bound"
                    } else {
                        "port $preferred is unavailable and no dynamic port could be bound"
                    },
                )

            if (reservations.containsKey(dynamic)) {
                throw PortBindException(
                    port = preferred,
                    message = "probe returned port $dynamic, which is already reserved by " +
                        "${reservations[dynamic]}",
                )
            }

            reservations[dynamic] = owner
            return dynamic
        }
    }

    /**
     * Returns [port] to the pool.
     *
     * Only the owner that reserved it may release it. Letting any caller release
     * any port would mean a protocol shutting down could free a port a sibling is
     * still using — the same class of bug the multicast lock counts references to
     * avoid.
     */
    override fun release(port: Int, owner: String) {
        synchronized(guard) {
            if (reservations[port] == owner) reservations.remove(port)
        }
    }

    override fun snapshot(): Map<Int, String> = reservations.toMap()
}
