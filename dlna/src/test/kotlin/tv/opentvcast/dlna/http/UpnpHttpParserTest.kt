package tv.opentvcast.dlna.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [UpnpHttpParser] — turning bytes off a socket into a request.
 *
 * WHY: this is the one socket-adjacent piece worth unit-testing, because its
 * failure mode is a control point that connects, sends, and gets nothing back —
 * with no exception anywhere. Two things go wrong in practice: headers are read
 * assuming a fixed order (they are not), and the body is read assuming it fits
 * in one read (it does not, for a SOAP envelope with DIDL metadata).
 */
class UpnpHttpParserTest {

    private fun bytes(text: String) = text.toByteArray()

    @Test
    fun `a GET request line yields method and path`() {
        val request = UpnpHttpParser.parse(bytes("GET /description.xml HTTP/1.1\r\nHOST: 1.2.3.4:80\r\n\r\n"))!!

        assertEquals("GET", request.method)
        assertEquals("/description.xml", request.path)
        assertEquals("1.2.3.4:80", request.header("HOST"))
    }

    @Test
    fun `the query string is not part of the path`() {
        val request = UpnpHttpParser.parse(bytes("GET /ctl/AVTransport?x=1 HTTP/1.1\r\n\r\n"))!!

        assertEquals("/ctl/AVTransport", request.path)
    }

    @Test
    fun `a POST body is read by Content-Length, not by how much arrived`() {
        val body = "<s:Envelope>hello</s:Envelope>"
        val raw = "POST /ctl/AVTransport HTTP/1.1\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "SOAPACTION: \"urn:schemas-upnp-org:service:AVTransport:1#Play\"\r\n\r\n" + body

        val request = UpnpHttpParser.parse(bytes(raw))!!

        assertEquals(body, request.body)
        // Quoted SOAPACTION values must be unquoted before use.
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1#Play", request.header("SOAPACTION"))
    }

    @Test
    fun `a body shorter than Content-Length is incomplete rather than accepted`() {
        val raw = "POST /ctl/AVTransport HTTP/1.1\r\nContent-Length: 100\r\n\r\nshort"

        assertNull("a truncated body must not be dispatched", UpnpHttpParser.parse(bytes(raw)))
    }

    @Test
    fun `header names are matched case-insensitively`() {
        val request = UpnpHttpParser.parse(bytes("SUBSCRIBE /evt/x HTTP/1.1\r\ncallback: <http://a>\r\n\r\n"))!!

        assertEquals("<http://a>", request.header("CALLBACK"))
    }

    @Test
    fun `a request with no blank line ending the headers is rejected`() {
        assertNull(UpnpHttpParser.parse(bytes("GET / HTTP/1.1\r\nHOST: x")))
        assertNull(UpnpHttpParser.parse(bytes("")))
        assertNull(UpnpHttpParser.parse(bytes("nonsense")))
    }

    @Test
    fun `a bare CRLF-only keep-alive probe is rejected`() {
        // Some control points open a connection and send nothing useful first.
        assertNull(UpnpHttpParser.parse(bytes("\r\n\r\n")))
    }
}
