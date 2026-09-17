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

import tv.opentvcast.airplay.handshake.AppleIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Byte-level tests for [RtspResponseWriter] — the single place a response
 * becomes wire bytes. Framing errors here are invisible at every higher
 * level (a wrong Content-Length means a sender reads garbage or blocks), so
 * the contract is pinned exactly, including binary bodies.
 */
class RtspResponseWriterTest {

    private fun writeOf(
        response: RtspResponse,
        cSeq: Int = 7,
    ): String {
        val out = ByteArrayOutputStream()
        RtspResponseWriter.write(out, response, cSeq)
        return out.toString("US-ASCII")
    }

    // ─── Status line + framing ────────────────────────────────────────────────

    @Test
    fun `a minimal RTSP response has a status line and no Content-Length when empty`() {
        val wire = writeOf(RtspResponse(200, "OK"))
        assertTrue(wire.startsWith("RTSP/1.0 200 OK\r\n"))
        assertTrue(wire.endsWith("\r\n\r\n"))
        assertFalse("no Content-Length for an empty body", wire.contains("Content-Length:"))
    }

    @Test
    fun `a body produces an exact byte-count Content-Length and the raw bytes`() {
        val body = "SDP BODY"
        val out = ByteArrayOutputStream()
        RtspResponseWriter.write(
            out,
            RtspResponse(200, "OK", body = body),
            cSeq = 7,
        )
        val text = out.toString("US-ASCII")
        assertTrue(text.contains("Content-Length: 8\r\n"))
        assertTrue(text.endsWith("\r\n\r\nSDP BODY"))
    }

    @Test
    fun `binary bodies keep Content-Length as byte length not character count`() {
        // 4 chars in the header string view, 6 actual bytes — the historical bug.
        val bytes = byteArrayOf(0xC3.toByte(), 0xA9.toByte(), 0x00, 0xFF.toByte(), 0x01, 0x02)
        val out = ByteArrayOutputStream()
        RtspResponseWriter.write(
            out,
            RtspResponse(200, "OK", bodyBytes = bytes),
            cSeq = 1,
        )
        val text = out.toString("ISO-8859-1")   // byte-faithful view
        assertTrue(text.contains("Content-Length: 6\r\n"))
        // And the bytes after the blank line are exactly the payload.
        val sep = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val idx = indexOf(out.toByteArray(), sep)
        val payload = out.toByteArray().copyOfRange(idx + sep.size, out.size())
        assertTrue(payload.contentEquals(bytes))
    }

    // ─── Headers ──────────────────────────────────────────────────────────────

    @Test
    fun `RTSP responses carry the CSeq and the Server header`() {
        val wire = writeOf(RtspResponse(200, "OK"), cSeq = 42)
        assertTrue(wire.contains("CSeq: 42\r\n"))
        assertTrue(wire.contains("Server: ${AppleIdentity.SERVER_HEADER}\r\n"))
    }

    @Test
    fun `HTTP-style responses omit CSeq but keep Server`() {
        val wire = writeOf(
            RtspResponse(200, "OK", protocol = "HTTP/1.1"),
            cSeq = 42,
        )
        assertFalse(wire.contains("CSeq:"))
        assertTrue(wire.contains("Server: ${AppleIdentity.SERVER_HEADER}\r\n"))
    }

    @Test
    fun `contentType and extra headers are written before the blank line`() {
        val wire = writeOf(
            RtspResponse(
                200, "OK",
                headers = mapOf("Session" to "OpenTvCastSession", "Transport" to "RTP/AVP/TCP"),
                contentType = "application/x-apple-binary-plist",
            ),
        )
        assertTrue(wire.contains("Content-Type: application/x-apple-binary-plist\r\n"))
        assertTrue(wire.contains("Session: OpenTvCastSession\r\n"))
        assertTrue(wire.contains("Transport: RTP/AVP/TCP\r\n"))
        // All headers precede the blank separator line.
        assertTrue(wire.indexOf("Session:") < wire.indexOf("\r\n\r\n"))
    }

    // ─── 503 path ─────────────────────────────────────────────────────────────

    @Test
    fun `serviceUnavailable writes the fixed 503 line with CSeq zero`() {
        val socket = FakeSocket()
        RtspResponseWriter.writeServiceUnavailable(socket)
        assertEquals(
            "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n",
            socket.written.toString("US-ASCII"),
        )
    }

    @Test
    fun `serviceUnavailable swallows a broken socket`() {
        // A socket whose write throws must not propagate — the 503 path already
        // runs while the real error is unwinding.
        val socket = FakeSocket(throwOnWrite = true)
        RtspResponseWriter.writeServiceUnavailable(socket)   // must not throw
    }

    // ─── Fakes ────────────────────────────────────────────────────────────────

    private class FakeSocket(private val throwOnWrite: Boolean = false) : java.net.Socket() {
        val written = ByteArrayOutputStream()
        private val out = object : java.io.OutputStream() {
            override fun write(b: Int) {
                if (throwOnWrite) throw java.io.IOException("boom")
                written.write(b)
            }
        }
        override fun getOutputStream(): java.io.OutputStream = out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        error("needle not found")
    }
}
