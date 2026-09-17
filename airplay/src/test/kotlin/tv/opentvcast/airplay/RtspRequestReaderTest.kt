package tv.opentvcast.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Tests for [RtspRequestReader], the parser for the AirPlay control socket.
 *
 * This class had no tests at all, which is uncomfortable for the component that
 * every RTSP request passes through — a mis-parse here surfaces as an unhandled
 * method several layers away, or as a hung session.
 *
 * The property worth protecting above all others is the one the class exists for:
 * **the reader must not consume more bytes than the request it returns.** After
 * `RECORD`, the same socket switches to binary interleaved RTP frames, and any
 * buffering here would silently eat the first video frames. Most of the tests
 * below are therefore about byte-exactness and about what is left in the stream
 * afterwards.
 */
class RtspRequestReaderTest {

    /** Generous limits; individual tests tighten them to exercise the guards. */
    private fun reader(maxMessage: Int = 65_536, maxPhoto: Int = 1_048_576) =
        RtspRequestReader(maxMessageBytes = maxMessage, maxPhotoBytes = maxPhoto)

    private fun stream(vararg chunks: ByteArray): InputStream =
        ByteArrayInputStream(chunks.reduce { a, b -> a + b })

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    // ─── the happy path ──────────────────────────────────────────────────────

    @Test
    fun `parses method, uri, protocol and headers`() {
        val input = stream(
            bytes(
                "GET /info RTSP/1.0\r\n" +
                    "CSeq: 1\r\n" +
                    "User-Agent: AirPlay/220.68\r\n" +
                    "\r\n"
            )
        )

        val request = reader().read(input)

        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("/info", request.uri)
        assertEquals("RTSP/1.0", request.protocol)
        assertEquals("1", request.headers["CSeq"])
        assertEquals("AirPlay/220.68", request.headers["User-Agent"])
        assertEquals("", request.body)
        assertEquals(0, request.bodyBytes.size)
    }

    @Test
    fun `tolerates bare LF line endings`() {
        // Some senders use LF alone. The reader strips CR and splits on LF, so both
        // conventions must work; only accepting CRLF would break those senders.
        val input = stream(bytes("OPTIONS * RTSP/1.0\nCSeq: 2\n\n"))

        val request = reader().read(input)

        assertNotNull(request)
        assertEquals("OPTIONS", request!!.method)
        assertEquals("2", request.headers["CSeq"])
    }

    @Test
    fun `reads a text body sized by Content-Length`() {
        val sdp = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\n"
        val input = stream(
            bytes(
                "ANNOUNCE rtsp://192.168.1.1/opentvcast RTSP/1.0\r\n" +
                    "CSeq: 3\r\n" +
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: ${sdp.toByteArray().size}\r\n" +
                    "\r\n" +
                    sdp
            )
        )

        val request = reader().read(input)

        assertNotNull(request)
        assertEquals(sdp, request!!.body)
        assertArrayEquals(sdp.toByteArray(), request.bodyBytes)
    }

    // ─── byte-exactness: the reason this class exists ─────────────────────────

    @Test
    fun `leaves the bytes after a body untouched in the stream`() {
        // The post-RECORD interleaved RTP frames follow immediately. If the reader
        // buffered ahead, these bytes would be lost and the first frames of every
        // mirroring session would be missing.
        val body = "abc"
        val trailing = byteArrayOf(0x24, 0x00, 0x01, 0x02) // '$' — interleaved RTP magic
        val input = stream(
            bytes(
                "RECORD rtsp://192.168.1.1/opentvcast RTSP/1.0\r\n" +
                    "Content-Length: ${body.length}\r\n\r\n" + body
            ),
            trailing
        )

        val request = reader().read(input)

        assertNotNull(request)
        assertEquals(body, request!!.body)

        val remaining = input.readBytes()
        assertArrayEquals(
            "The reader consumed bytes it should have left for the RTP decoder",
            trailing,
            remaining,
        )
    }

    @Test
    fun `reads two consecutive requests from one stream`() {
        val input = stream(
            bytes("GET /info RTSP/1.0\r\nCSeq: 1\r\n\r\n" + "SETUP rtsp://x/y RTSP/1.0\r\nCSeq: 2\r\n\r\n")
        )

        val first = reader().read(input)
        val second = reader().read(input)

        assertEquals("GET", first!!.method)
        assertEquals("/info", first.uri)
        assertEquals("SETUP", second!!.method)
        assertEquals("2", second.headers["CSeq"])
        assertNull("a third read must hit clean EOF, not loop", reader().read(input))
    }

    @Test
    fun `preserves binary bodies exactly`() {
        // FairPlay key material and encrypted plists contain NULs and 0xFF bytes.
        // The body is also exposed as UTF-8 text, which mangles such bytes — so
        // bodyBytes is the authoritative wire form and must be byte-identical.
        val binary = byteArrayOf(0x00, 0xFF.toByte(), 0x0A, 0x0D, 0x80.toByte(), 0x01)
        val input = stream(
            bytes("POST /fp-setup RTSP/1.0\r\nContent-Length: ${binary.size}\r\n\r\n"),
            binary
        )

        val request = reader().read(input)

        assertNotNull(request)
        assertArrayEquals(
            "bodyBytes must be the exact wire bytes, not a re-encoding of them",
            binary,
            request!!.bodyBytes,
        )
    }

    // ─── header handling ─────────────────────────────────────────────────────

    @Test
    fun `trims whitespace around header names and values`() {
        val input = stream(bytes("GET /info RTSP/1.0\r\n   CSeq   :   17   \r\n\r\n"))

        val request = reader().read(input)

        assertEquals("17", request!!.headers["CSeq"])
    }

    @Test
    fun `splits a header on the first colon only`() {
        // Content-Location is a full URL: "rtsp://host/path". Splitting on the last
        // colon would mangle it into something the handler cannot route.
        val input = stream(
            bytes("SET_PARAMETER rtsp://x/y RTSP/1.0\r\nContent-Location: rtsp://192.168.1.1/1234\r\n\r\n")
        )

        val request = reader().read(input)

        assertEquals("rtsp://192.168.1.1/1234", request!!.headers["Content-Location"])
    }

    @Test
    fun `ignores a header line with no colon`() {
        val input = stream(bytes("GET /info RTSP/1.0\r\nnonsense-line\r\nCSeq: 4\r\n\r\n"))

        val request = reader().read(input)

        assertNotNull("A junk header must not abort the whole request", request)
        assertEquals("4", request!!.headers["CSeq"])
        assertTrue("the malformed line must not become a header", request.headers.keys.none { it.contains("nonsense") })
    }

    @Test
    fun `accepts a request with headers only and no body`() {
        val input = stream(bytes("GET /info RTSP/1.0\r\nCSeq: 9\r\n\r\n"))

        val request = reader().read(input)

        assertEquals("9", request!!.headers["CSeq"])
        assertEquals(0, request.bodyBytes.size)
    }

    // ─── the body limit, and why /photo is special ────────────────────────────

    @Test
    fun `rejects a body over the message limit`() {
        val input = stream(bytes("ANNOUNCE rtsp://x/y RTSP/1.0\r\nContent-Length: 100\r\n\r\n" + "z".repeat(100)))

        assertNull(reader(maxMessage = 64).read(input))
    }

    @Test
    fun `allows a photo body up to the photo limit`() {
        // A JPEG is far larger than an RTSP message, so PUT /photo is measured
        // against its own ceiling. Getting this wrong rejects every photo.
        val photo = "j".repeat(100)
        val input = stream(bytes("PUT /photo RTSP/1.0\r\nContent-Length: ${photo.length}\r\n\r\n$photo"))

        val request = reader(maxMessage = 64, maxPhoto = 4096).read(input)

        assertNotNull("A photo body over the message limit must still be accepted", request)
        assertEquals(photo, request!!.body)
    }

    @Test
    fun `applies the photo limit to a photo URI carrying a query string`() {
        // The check compares the path only, so /photo?something must not fall back
        // to the much smaller message limit.
        val photo = "k".repeat(100)
        val input = stream(bytes("PUT /photo?asset=1 RTSP/1.0\r\nContent-Length: ${photo.length}\r\n\r\n$photo"))

        val request = reader(maxMessage = 64, maxPhoto = 4096).read(input)

        assertNotNull(request)
        assertEquals(photo, request!!.body)
    }

    @Test
    fun `rejects a photo over the photo limit`() {
        val input = stream(bytes("PUT /photo RTSP/1.0\r\nContent-Length: 5000\r\n\r\n" + "p".repeat(5000)))

        assertNull(reader(maxMessage = 64, maxPhoto = 4096).read(input))
    }

    @Test
    fun `rejects a body over the message limit even when it is not a photo`() {
        // PUT to some other path must not borrow the photo allowance.
        val input = stream(bytes("PUT /other RTSP/1.0\r\nContent-Length: 100\r\n\r\n" + "q".repeat(100)))

        assertNull(reader(maxMessage = 64, maxPhoto = 4096).read(input))
    }

    // ─── malformed and truncated input ───────────────────────────────────────

    @Test
    fun `returns null at clean EOF`() {
        assertNull(reader().read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `returns null for a request line with fewer than three parts`() {
        assertNull(reader().read(stream(bytes("GARBAGE\r\n\r\n"))))
        assertNull(reader().read(stream(bytes("GET /info\r\n\r\n"))))
    }

    @Test
    fun `returns null when the header block is truncated`() {
        // Connection dropped mid-request: no terminating blank line ever arrives.
        assertNull(reader().read(stream(bytes("GET /info RTSP/1.0\r\nCSeq: 1\r\n"))))
    }

    @Test
    fun `returns null when the body is truncated`() {
        val input = stream(bytes("ANNOUNCE rtsp://x/y RTSP/1.0\r\nContent-Length: 50\r\n\r\nshort"))

        assertNull("A short body must not be handed on as if complete", reader().read(input))
    }

    @Test
    fun `treats a missing Content-Length as an empty body`() {
        val input = stream(bytes("GET /info RTSP/1.0\r\nCSeq: 1\r\n\r\n"))

        val request = reader().read(input)

        assertEquals(0, request!!.bodyBytes.size)
    }

    @Test
    fun `treats a non-numeric Content-Length as zero`() {
        // A malformed length must not throw; the request is still routable and the
        // handler will reject it on its own terms if a body was required.
        val input = stream(bytes("GET /info RTSP/1.0\r\nContent-Length: abc\r\n\r\n"))

        val request = reader().read(input)

        assertNotNull(request)
        assertEquals(0, request!!.bodyBytes.size)
    }

    @Test
    fun `treats a negative Content-Length as zero`() {
        val input = stream(bytes("GET /info RTSP/1.0\r\nContent-Length: -5\r\n\r\n"))

        val request = reader().read(input)

        assertNotNull(request)
        assertEquals(0, request!!.bodyBytes.size)
    }

    @Test
    fun `rejects a header block larger than the message limit`() {
        // Guards against an unbounded header flood inflating the parser's buffer.
        val padding = "X-Pad: " + "a".repeat(200) + "\r\n"
        val input = stream(bytes("GET /info RTSP/1.0\r\n$padding\r\n"))

        assertNull(reader(maxMessage = 128).read(input))
    }

    @Test
    fun `rejects a single line longer than the message limit`() {
        // No newline ever arrives, so only the per-line guard stops this.
        val input = stream(bytes("GET " + "u".repeat(300)))

        assertNull(reader(maxMessage = 128).read(input))
    }

    @Test
    fun `skips leading blank lines before the request line`() {
        // A stray CRLF between pipelined requests must not be read as an empty
        // request; the reader skips it and continues scanning for a real line.
        val input = stream(bytes("\r\n\r\nGET /info RTSP/1.0\r\nCSeq: 6\r\n\r\n"))

        val request = reader().read(input)

        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("6", request.headers["CSeq"])
    }

    /** Runs [block] on a thread with a deliberately small stack (128 KB). */
    private fun onSmallStack(block: () -> Unit): Throwable? {
        var failure: Throwable? = null
        val t = Thread(null, {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }, "blank-line-flood", 128 * 1024)
        t.start()
        t.join()
        return failure
    }

    @Test
    fun `survives a blank-line flood without overflowing the stack`() {
        // An attacker can send CRLF pairs forever before a real request; the
        // skip-ahead must be iterative, not recursive, or the session thread
        // dies with StackOverflowError (remote DoS on the control socket).
        val flood = "\r\n".repeat(200_000)
        val input = stream(bytes(flood + "OPTIONS * RTSP/1.0\r\n\r\n"))

        val failure = onSmallStack {
            val request = reader().read(input)
            assertNotNull(request)
            assertEquals("OPTIONS", request!!.method)
        }

        assertNull("blank-line flood must not overflow the stack", failure)
    }

    @Test
    fun `returns null when blank lines are followed by clean EOF`() {
        val input = stream(bytes("\r\n\r\n\r\n"))

        val failure = onSmallStack {
            assertNull(reader().read(input))
        }

        assertNull(failure)
    }
}
