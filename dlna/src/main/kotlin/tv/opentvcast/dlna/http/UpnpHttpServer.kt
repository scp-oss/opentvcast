/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.dlna.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.opentvcast.dlna.ssdp.DlnaServices
import tv.opentvcast.util.Logger
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Serves the UPnP HTTP endpoints on one port: description, SCPDs, SOAP control
 * and GENA subscribe/unsubscribe.
 *
 * All decisions live in [UpnpRouter]; this class is only the socket loop. That
 * split is deliberate — the router is unit-tested exhaustively, and this class
 * is the part that needs a device.
 *
 * @param notifier delivers GENA `NOTIFY` bodies to subscribers; injected so the
 *   socket loop does not own delivery policy.
 */
class UpnpHttpServer(
    private val scope: CoroutineScope,
    private val port: Int,
    private val router: UpnpRouter,
    private val notifier: (serviceId: String, variables: Map<String, String>) -> Unit = { _, _ -> },
) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    /**
     * The port actually bound — which is not [port] when the caller asked for 0
     * (ephemeral) or the requested one was taken. Callers must advertise this
     * value, the same rule as [tv.opentvcast.core.net.PortAllocator].
     */
    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun start() {
        scope.launch(Dispatchers.IO) {
            val socket = try {
                ServerSocket(port)
            } catch (e: Exception) {
                // A bind failure here is not "expected shutdown noise": the
                // renderer is up, discoverable, and completely unusable.
                Logger.e("DLNA HTTP cannot bind port $port — control will not work", e)
                return@launch
            }
            serverSocket = socket
            Logger.i("DLNA HTTP server listening on port ${socket.localPort}")

            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (socket.isClosed || !isActive) {
                        Logger.d("DLNA HTTP accept loop ended")
                    } else {
                        Logger.e("DLNA HTTP accept failed", e)
                    }
                    return@launch
                }
                launch { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                val request = UpnpHttpParser.parse(readRequest(socket)) ?: run {
                    Logger.d("DLNA HTTP: unparseable request from ${socket.inetAddress}")
                    return
                }
                val response = router.handle(request)
                socket.getOutputStream().use { it.write(response.encode()) }

                // A control call that changed transport state or volume must be
                // pushed to subscribers — that is the whole point of eventing.
                if (request.method == "POST") {
                    eventVariablesFor(request.path)?.let { (serviceId, _) ->
                        if (response.statusCode == 200) notifier(serviceId, emptyMap())
                    }
                }
            }
        } catch (e: Exception) {
            Logger.d("DLNA HTTP client handling failed: ${e.message}")
        }
    }

    /** Reads until the headers end, then the declared body length. */
    private fun readRequest(socket: Socket): ByteArray {
        val input = DataInputStream(socket.getInputStream())
        val buffer = mutableListOf<Byte>()
        var headerBytes = ByteArray(0)
        while (true) {
            val b = input.read()
            if (b == -1) break
            buffer.add(b.toByte())
            headerBytes = buffer.toByteArray()
            if (headerBytes.size >= 4 &&
                headerBytes[headerBytes.size - 4] == '\r'.code.toByte() &&
                headerBytes[headerBytes.size - 3] == '\n'.code.toByte() &&
                headerBytes[headerBytes.size - 2] == '\r'.code.toByte() &&
                headerBytes[headerBytes.size - 1] == '\n'.code.toByte()
            ) break
        }
        val declared = UpnpHttpParser.contentLength(headerBytes)
        if (declared <= 0) return headerBytes
        val body = ByteArray(declared)
        var read = 0
        while (read < declared) {
            val n = input.read(body, read, declared - read)
            if (n == -1) break
            read += n
        }
        return headerBytes + body.copyOf(read)
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.d("DLNA HTTP close failed (non-fatal): ${e.message}")
        }
        serverSocket = null
    }

    private fun eventVariablesFor(path: String): Pair<String, String>? =
        DlnaServices.all.firstOrNull { it.controlPath == path }?.let { it.serviceId to it.serviceType }

    companion object {
        private const val READ_TIMEOUT_MS = 10_000
    }
}
