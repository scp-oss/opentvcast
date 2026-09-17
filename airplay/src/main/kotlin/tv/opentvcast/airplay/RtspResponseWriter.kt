/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.airplay

import java.io.OutputStream
import java.net.Socket
import tv.opentvcast.airplay.handshake.AppleIdentity
import tv.opentvcast.util.Logger

/**
 * Serialises [RtspResponse]s to the wire — the single place a response becomes
 * bytes. Extracted from [RtspHandler] so the framing rules have byte-level
 * tests instead of living inside a 1000-line class.
 *
 * The two rules that matter, both pinned by [tv.opentvcast.airplay.RtspResponseWriterTest]:
 *
 * - **Content-Length is a BYTE count**, not a character count. Binary plists,
 *   FairPlay payloads, and encrypted bodies all flow through here; a wrong
 *   length makes the sender read garbage or block forever.
 * - **CSeq echoes the request it answers** on RTSP connections. HTTP-style
 *   AirPlay 2 requests (GET/POST over the same socket) have no CSeq and must
 *   not get one — macOS tolerates the extra header, but cleanliness here keeps
 *   the two dialects visibly distinct.
 */
internal object RtspResponseWriter {

    /** Serialises [response] with the request's [cSeq] (echoed only for RTSP protocols). */
    fun write(outputStream: OutputStream, response: RtspResponse, cSeq: Int) {
        val wire = response.wireBody()
        val head = StringBuilder()
        head.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        if (response.protocol.startsWith("RTSP")) {
            head.append("CSeq: $cSeq\r\n")
        }
        head.append("Server: ${AppleIdentity.SERVER_HEADER}\r\n")
        response.contentType?.let { head.append("Content-Type: $it\r\n") }
        response.headers.forEach { (key, value) ->
            head.append("$key: $value\r\n")
        }
        if (wire.isNotEmpty()) {
            head.append("Content-Length: ${wire.size}\r\n")
        }
        head.append("\r\n")
        outputStream.write(head.toString().toByteArray(Charsets.US_ASCII))
        if (wire.isNotEmpty()) {
            outputStream.write(wire)
        }
        outputStream.flush()
    }

    /**
     * Best-effort 503 to an incoming connection that cannot be served (the RTSP
     * port is still held by a previous instance). Runs while the real error is
     * unwinding, so every failure here is swallowed.
     */
    fun writeServiceUnavailable(socket: Socket) {
        try {
            val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
            socket.outputStream.write(response.toByteArray())
            socket.outputStream.flush()
        } catch (e: Exception) {
            Logger.e("Error sending 503 response", e)
        }
    }
}
