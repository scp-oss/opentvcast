package tv.opentvcast.dlna.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.opentvcast.dlna.soap.DlnaControlDispatcher
import tv.opentvcast.dlna.soap.DlnaPlayerPort
import tv.opentvcast.dlna.soap.DlnaRendererState
import tv.opentvcast.dlna.ssdp.DlnaServices

/**
 * Tests for [UpnpRouter] — the HTTP surface a control point talks to: the
 * device description, the SCPDs, SOAP control, and GENA subscription requests.
 *
 * WHY: every failure here is invisible from the TV. A wrong status code or a
 * missing header does not crash anything; the control point just stops
 * talking, and the user sees a renderer that "stopped working".
 *
 * The router is a pure function of (request, renderer state) — no sockets —
 * which is what lets these tests pin the exact status codes and headers.
 */
class UpnpRouterTest {

    private class FakePlayer : DlnaPlayerPort {
        override var positionMs: Long = 0L
        override var durationMs: Long = 600_000L
        override fun play(uri: String) {}
        override fun resume() {}
        override fun pause() {}
        override fun stop() {}
        override fun seek(targetMs: Long) { positionMs = targetMs }
        override fun release() {}
    }

    private fun router(): UpnpRouter {
        val state = DlnaRendererState()
        val dispatcher = DlnaControlDispatcher(state, FakePlayer())
        return UpnpRouter(
            udn = "2f402f80-da50-11e1-9b23-0017882a4a01",
            friendlyName = "Living Room TV",
            baseUrl = "http://192.168.1.7:49153",
            dispatcher = dispatcher,
            subscriptions = GenaSubscriptionManager(idSource = { "sid-1" }),
        )
    }

    private fun get(path: String) = UpnpHttpRequest("GET", path, emptyMap(), "")
    private fun post(path: String, soapAction: String?, body: String) =
        UpnpHttpRequest("POST", path, if (soapAction == null) emptyMap() else mapOf("SOAPACTION" to soapAction), body)

    // ─── documents ───────────────────────────────────────────────────────────

    @Test
    fun `the description document is served as XML`() {
        val response = router().handle(get("/description.xml"))

        assertEquals(200, response.statusCode)
        assertTrue(response.contentType.contains("xml"))
        assertTrue(String(response.body).contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
    }

    @Test
    fun `each SCPD is served on its declared path`() {
        val router = router()

        for (service in DlnaServices.all) {
            val response = router.handle(get(service.scpdPath))
            assertEquals("${service.controlPath} SCPD", 200, response.statusCode)
            assertTrue(String(response.body).contains("<scpd"))
        }
    }

    @Test
    fun `an unknown path is a 404, not a crash or a 500`() {
        assertEquals(404, router().handle(get("/nope")).statusCode)
        assertEquals(404, router().handle(get("/")).statusCode)
    }

    // ─── SOAP control ────────────────────────────────────────────────────────

    private val setUriBody = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>http://192.168.1.9:8200/media/movie.mp4</CurrentURI>
      <CurrentURIMetaData></CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""

    @Test
    fun `a control POST is routed by the SOAPACTION header`() {
        val response = router().handle(
            post(
                "/ctl/AVTransport",
                "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
                setUriBody,
            ),
        )

        assertEquals(200, response.statusCode)
        assertTrue(String(response.body).contains("SetAVTransportURIResponse"))
    }

    @Test
    fun `a rejected action is answered with HTTP 500 and a fault body`() {
        // UPnP carries SOAP faults on a 500; answering 200 here makes control
        // points believe the call succeeded.
        val response = router().handle(
            post("/ctl/AVTransport", "urn:schemas-upnp-org:service:AVTransport:1#Explode", setUriBody),
        )

        assertEquals(500, response.statusCode)
        assertTrue(String(response.body).contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun `a control POST without SOAPACTION is a 400 rather than a guess`() {
        assertEquals(400, router().handle(post("/ctl/AVTransport", null, setUriBody)).statusCode)
    }

    @Test
    fun `a control POST to an unknown service is a 404`() {
        assertEquals(
            404,
            router().handle(post("/ctl/Nope", "urn:x:service:Nope:1#Play", setUriBody)).statusCode,
        )
    }

    @Test
    fun `a SOAPACTION naming a different service than the path is a 400`() {
        // Dispatching on the path alone would let a stale SOAPACTION invoke an
        // action the caller never intended.
        assertEquals(
            400,
            router().handle(
                post("/ctl/AVTransport", "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume", setUriBody),
            ).statusCode,
        )
    }

    @Test
    fun `an unparseable SOAP body is a 400 rather than an exception`() {
        assertEquals(
            400,
            router().handle(
                post("/ctl/AVTransport", "urn:schemas-upnp-org:service:AVTransport:1#Play", "not xml"),
            ).statusCode,
        )
    }

    // ─── GENA ────────────────────────────────────────────────────────────────

    @Test
    fun `SUBSCRIBE registers a callback and returns a SID and timeout`() {
        val response = router().handle(
            UpnpHttpRequest(
                "SUBSCRIBE",
                "/evt/AVTransport",
                mapOf("CALLBACK" to "<http://192.168.1.9:9000/event>", "NT" to "upnp:event", "TIMEOUT" to "Second-1800"),
                "",
            ),
        )

        assertEquals(200, response.statusCode)
        assertEquals("sid-1", response.headers["SID"])
        assertEquals("Second-1800", response.headers["TIMEOUT"])
    }

    @Test
    fun `SUBSCRIBE without a callback is a 400`() {
        val response = router().handle(
            UpnpHttpRequest("SUBSCRIBE", "/evt/AVTransport", mapOf("NT" to "upnp:event"), ""),
        )

        assertEquals(400, response.statusCode)
    }

    @Test
    fun `UNSUBSCRIBE with a known SID succeeds and an unknown one is a 412`() {
        val router = router()
        val sid = router.handle(
            UpnpHttpRequest(
                "SUBSCRIBE",
                "/evt/AVTransport",
                mapOf("CALLBACK" to "<http://a>", "NT" to "upnp:event"),
                "",
            ),
        ).headers["SID"]!!

        assertEquals(
            200,
            router.handle(UpnpHttpRequest("UNSUBSCRIBE", "/evt/AVTransport", mapOf("SID" to sid), "")).statusCode,
        )
        assertEquals(
            412,
            router.handle(
                UpnpHttpRequest("UNSUBSCRIBE", "/evt/AVTransport", mapOf("SID" to "uuid:ghost"), ""),
            ).statusCode,
        )
    }
}
