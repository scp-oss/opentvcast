package tv.opentvcast.dlna.soap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SOAP request parsing and response building.
 *
 * WHY: SOAP is the entire control surface of a DLNA renderer — everything the
 * sender does after discovery goes through one of these envelopes. The failure
 * mode of getting it wrong is a control point that simply stops talking to us,
 * which looks identical to "the TV disappeared" from the user's side.
 *
 * The XML that arrives is not always what the spec shows: control points omit
 * the XML declaration, use their own namespace prefixes, and occasionally send
 * an empty body. Parsing must survive all three, and a fault reply must still
 * be a well-formed envelope — a control point that cannot parse the *error*
 * has nothing to show the user.
 */
class SoapMessageTest {

    private val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>http://192.168.1.9:8200/media/movie.mp4</CurrentURI>
      <CurrentURIMetaData></CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""

    // ─── parsing ─────────────────────────────────────────────────────────────

    @Test
    fun `a control envelope yields the action and its arguments`() {
        val request = SoapRequest.parse(envelope)!!

        assertEquals("SetAVTransportURI", request.action)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", request.serviceType)
        assertEquals("0", request.argument("InstanceID"))
        assertEquals("http://192.168.1.9:8200/media/movie.mp4", request.argument("CurrentURI"))
    }

    @Test
    fun `an empty argument is preserved as empty, not treated as missing`() {
        // DIDL metadata is legitimately empty for a bare URL push.
        val request = SoapRequest.parse(envelope)!!

        assertEquals("", request.argument("CurrentURIMetaData"))
        assertTrue(request.argument("NotThere") == null)
    }

    @Test
    fun `a different namespace prefix still resolves the action`() {
        // Control points pick their own prefixes; only the namespace URIs are
        // fixed. (A first draft of this test string-replaced the prefixes and
        // mangled `xmlns:s` into `xmlnsoapenv:s` — the bug was in the test.)
        val other = """<?xml version="1.0"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
  <soapenv:Body>
    <ns:SetAVTransportURI xmlns:ns="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>http://192.168.1.9:8200/media/movie.mp4</CurrentURI>
    </ns:SetAVTransportURI>
  </soapenv:Body>
</soapenv:Envelope>"""

        val request = SoapRequest.parse(other)!!
        assertEquals("SetAVTransportURI", request.action)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", request.serviceType)
    }

    @Test
    fun `a body with no action is rejected`() {
        assertNull(SoapRequest.parse(
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body/></s:Envelope>""",
        ))
        assertNull(SoapRequest.parse("not xml"))
        assertNull(SoapRequest.parse(""))
    }

    // ─── building ────────────────────────────────────────────────────────────

    @Test
    fun `a success reply is an ActionNameResponse carrying the out arguments`() {
        val xml = SoapResponse.success(
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "GetPositionInfo",
            arguments = mapOf("Track" to "1", "RelTime" to "00:01:23", "TrackDuration" to "00:42:00"),
        )

        assertTrue(xml.contains("GetPositionInfoResponse"))
        assertTrue(xml.contains("<RelTime>00:01:23</RelTime>"))
        assertTrue(xml.contains("urn:schemas-upnp-org:service:AVTransport:1"))
        assertTrue(xml.endsWith("</s:Envelope>"))
    }

    @Test
    fun `out argument values are XML-escaped`() {
        val xml = SoapResponse.success(
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "GetMediaInfo",
            arguments = mapOf("CurrentURI" to "http://h/a.mp4?a=1&b=2"),
        )

        assertTrue(xml.contains("a=1&amp;b=2"))
    }

    @Test
    fun `a fault reply is well-formed and carries the UPnP error code`() {
        val xml = SoapResponse.fault(
            code = SoapResponse.ERROR_TRANSITION_NOT_AVAILABLE,
            description = "Transition not available",
        )

        assertTrue(xml.contains("<errorCode>701</errorCode>"))
        assertTrue(xml.contains("<errorDescription>Transition not available</errorDescription>"))
        assertTrue(xml.contains("UPnPError"))
    }

    @Test
    fun `known error codes are the ones control points understand`() {
        assertEquals(401, SoapResponse.ERROR_INVALID_ACTION)
        assertEquals(402, SoapResponse.ERROR_INVALID_ARGS)
        assertEquals(501, SoapResponse.ERROR_ACTION_FAILED)
        assertEquals(701, SoapResponse.ERROR_TRANSITION_NOT_AVAILABLE)
    }
}
