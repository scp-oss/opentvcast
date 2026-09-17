/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.dlna.ssdp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.opentvcast.util.Logger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Answers SSDP `M-SEARCH` requests and announces `ssdp:alive` / `ssdp:byebye`
 * (FR-32).
 *
 * What to answer and when to answer it are decided by [SsdpSearchMatcher] and
 * unit-tested there; this class owns the socket.
 *
 * Two things matter and are easy to get wrong:
 *
 * - **The reply goes to the sender's address, not to the group.** Answering into
 *   the multicast group makes every renderer on the network see every reply.
 * - **`LOCATION` must carry the granted port** — hence [descriptionUrl] is
 *   computed from the port the allocator returned, never from a constant.
 */
class SsdpResponder(
    private val scope: CoroutineScope,
    private val udn: String,
    private val descriptionUrl: String,
    private val maxAgeSeconds: Int = DEFAULT_MAX_AGE_SECONDS,
    private val server: String = SERVER_ID,
    /**
     * Opens the multicast socket. Injected so the receive loop can be driven by
     * a fake: joining a real multicast group needs a network that supports it,
     * which CI does not reliably have — but the *policy* (what to answer, to
     * whom, and when) is pure and worth testing.
     */
    private val socketProvider: () -> MulticastSocket = { MulticastSocket(SsdpMessage.MULTICAST_PORT).apply { reuseAddress = true } },
    /** Join/leave of the multicast group; a fake may make this a no-op. */
    private val groupJoin: (MulticastSocket) -> Unit = { socket ->
        socket.joinGroup(InetAddress.getByName(SsdpMessage.MULTICAST_GROUP))
    },
) {

    @Volatile
    private var socket: MulticastSocket? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            val created = try {
                socketProvider().also { groupJoin(it) }
            } catch (e: Exception) {
                Logger.e("DLNA SSDP cannot join ${SsdpMessage.MULTICAST_GROUP}:1900 — discovery will not work", e)
                return@launch
            }
            socket = created
            Logger.i("DLNA SSDP listening on port 1900")

            // Announce twice: control points that miss the first NOTIFY still
            // find us on the second, and the cost is two datagrams.
            announce(alive = true)
            launch {
                delay(ANNOUNCE_REPEAT_MS)
                if (isActive) announce(alive = true)
            }

            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    created.receive(packet)
                } catch (e: Exception) {
                    if (created.isClosed || !isActive) {
                        Logger.d("DLNA SSDP receive loop ended")
                    } else {
                        Logger.e("DLNA SSDP receive failed", e)
                    }
                    return@launch
                }
                handle(packet)
            }
        }
    }

    private fun handle(packet: DatagramPacket) {
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val message = SsdpMessage.parse(text) ?: return
        val targets = SsdpSearchMatcher.matches(message, SsdpTarget.ALL, udn)
        if (targets.isEmpty()) return

        // Spread replies across the sender's MX window so twenty renderers do
        // not answer the same millisecond.
        val delayMs = SsdpSearchMatcher.responseDelayMs(message.mx, Math.random())
        scope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            for (target in targets) {
                val reply = SsdpMessage.searchResponse(
                    location = descriptionUrl,
                    searchTarget = target.nt,
                    usn = target.usn(udn),
                    maxAgeSeconds = maxAgeSeconds,
                    server = server,
                )
                val bytes = reply.toByteArray()
                val out = DatagramPacket(bytes, bytes.size, packet.address, packet.port)
                runCatching { socket?.send(out) }
                    .onFailure { Logger.d("DLNA SSDP reply failed: ${it.message}") }
            }
        }
    }

    /** Sends `ssdp:alive` (or `ssdp:byebye` when stopping) for every target. */
    private fun announce(alive: Boolean) {
        for (target in SsdpTarget.ALL) {
            val notification = SsdpMessage.notify(
                notificationType = target.nt,
                usn = target.usn(udn),
                location = descriptionUrl,
                alive = alive,
                maxAgeSeconds = maxAgeSeconds,
                server = server,
            )
            val bytes = notification.toByteArray()
            val packet = DatagramPacket(
                bytes, bytes.size,
                InetAddress.getByName(SsdpMessage.MULTICAST_GROUP), SsdpMessage.MULTICAST_PORT,
            )
            runCatching { socket?.send(packet) }
                .onFailure { Logger.d("DLNA SSDP announce failed: ${it.message}") }
        }
    }

    fun stop() {
        try {
            announce(alive = false)
            socket?.leaveGroup(InetAddress.getByName(SsdpMessage.MULTICAST_GROUP))
        } catch (e: Exception) {
            Logger.d("DLNA SSDP byebye failed (non-fatal): ${e.message}")
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            Logger.d("DLNA SSDP close failed (non-fatal): ${e.message}")
        }
        socket = null
    }

    companion object {
        const val DEFAULT_MAX_AGE_SECONDS = 1800
        const val SERVER_ID = "opentvcast/1.0 UPnP/1.1"
        private const val MAX_DATAGRAM_BYTES = 2048
        private const val ANNOUNCE_REPEAT_MS = 500L

        /** Interface a multicast socket should bind to, or `null` for the default. */
        fun defaultInterface(): NetworkInterface? = null
    }
}
