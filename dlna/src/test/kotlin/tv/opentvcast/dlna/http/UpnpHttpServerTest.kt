package tv.opentvcast.dlna.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.opentvcast.dlna.soap.DlnaControlDispatcher
import tv.opentvcast.dlna.soap.DlnaPlayerPort
import tv.opentvcast.dlna.soap.DlnaRendererState
import tv.opentvcast.dlna.ssdp.DlnaServices
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tests for [UpnpHttpServer] against a **real socket on the loopback
 * interface** — no device, no emulator, no Robolectric.
 *
 * WHY THIS TEST EXISTS: the socket loop is the layer everyone assumes needs a
 * device, and therefore never tests. It does not: TCP and UDP are plain
 * `java.net` on the JVM, so a server bound to 127.0.0.1 behaves exactly as it
 * does on a TV. What genuinely needs hardware is different — hardware codecs,
 * Wi-Fi multicast filtering, and talking to a real Apple sender.
 *
 * Two rules make this possible:
 * - **Bind port 0** and read [UpnpHttpServer.boundPort]: the test must never
 *   depend on a fixed port, and the server must publish the port it got.
 * - **Close the scope** in every test, so a leaked accept loop cannot keep a
 *   port occupied for the next one.
 */
class UpnpHttpServerTest {

    private class FakePlayer : DlnaPlayerPort {
        override var positionMs: Long = 0L
        override var durationMs: Long = -1L
        override fun play(uri: String) {}
        override fun resume() {}
        override fun pause() {}
        override fun stop() {}
        override fun seek(targetMs: Long) {}
        override fun release() {}
    }

    /** Boots a real server on an ephemeral port and waits until it is bound. */
    private fun withServer(block: (port: Int) -> Unit) {
        val scope = CoroutineScope(Dispatchers.IO)
        val router = UpnpRouter(
            udn = "2f402f80-da50-11e1-9b23-0017882a4a01",
            friendlyName = "Living Room TV",
            baseUrl = "http://127.0.0.1",
            dispatcher = DlnaControlDispatcher(DlnaRendererState(), FakePlayer()),
            subscriptions = GenaSubscriptionManager(),
        )
        val server = UpnpHttpServer(scope, 0, router)
        try {
            server.start()
            val deadline = System.currentTimeMillis() + 5_000
            while (server.boundPort == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertTrue("server never bound", server.boundPort > 0)
            block(server.boundPort)
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val code = connection.responseCode
            val body = (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.readText() ?: ""
            code to body
        } finally {
            connection.disconnect()
        }
    }

    private fun post(port: Int, path: String, soapAction: String, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", soapAction)
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val text = (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.readText() ?: ""
            code to text
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `the device description is served over a real socket`() {
        withServer { port ->
            val (code, body) = get(port, "/description.xml")

            assertEquals(200, code)
            assertTrue(body.contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
            assertTrue(body.contains("<friendlyName>Living Room TV</friendlyName>"))
        }
    }

    @Test
    fun `each SCPD is served over a real socket`() {
        withServer { port ->
            for (service in DlnaServices.all) {
                val (code, body) = get(port, service.scpdPath)

                assertEquals("${service.scpdPath}", 200, code)
                assertTrue(body.contains("<scpd"))
            }
        }
    }

    @Test
    fun `an unknown path is a 404 over a real socket`() {
        withServer { port ->
            assertEquals(404, get(port, "/nope").first)
        }
    }

    private val setUriBody = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>http://127.0.0.1:9/movie.mp4</CurrentURI>
      <CurrentURIMetaData></CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""

    @Test
    fun `a SOAP control call round-trips over a real socket`() {
        withServer { port ->
            val (code, body) = post(
                port,
                "/ctl/AVTransport",
                "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
                setUriBody,
            )

            assertEquals(200, code)
            assertTrue(body.contains("SetAVTransportURIResponse"))
        }
    }

    @Test
    fun `a rejected action comes back as 500 with a fault body`() {
        withServer { port ->
            val (code, body) = post(
                port,
                "/ctl/AVTransport",
                "urn:schemas-upnp-org:service:AVTransport:1#Explode",
                setUriBody,
            )

            assertEquals(500, code)
            assertTrue(body.contains("<errorCode>401</errorCode>"))
        }
    }
}
