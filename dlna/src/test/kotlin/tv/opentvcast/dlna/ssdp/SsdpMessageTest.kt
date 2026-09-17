package tv.opentvcast.dlna.ssdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SsdpMessage] — the parse/serialise layer of SSDP.
 *
 * WHY: SSDP is the only reason a DLNA control point can find this TV at all.
 * A malformed answer is silently ignored by the sender, so the failure mode is
 * "the renderer never appears in the picker" with nothing in any log. Every
 * header here is load-bearing: `LOCATION` must carry the port actually granted
 * (FR-20/FR-33), `ST` must match what was searched for, and `USN` must be
 * unique per target or some control points discard duplicates.
 */
class SsdpMessageTest {

    private val search = """
        M-SEARCH * HTTP/1.1
        HOST: 239.255.255.250:1900
        MAN: "ssdp:discover"
        MX: 3
        ST: upnp:rootdevice

    """.trimIndent().lines().joinToString("\r\n") + "\r\n\r\n"

    private val notify = """
        NOTIFY * HTTP/1.1
        HOST: 239.255.255.250:1900
        CACHE-CONTROL: max-age=1800
        LOCATION: http://192.168.1.5:8200/description.xml
        NT: upnp:rootdevice
        NTS: ssdp:alive
        SERVER: opentvcast/1.0 UPnP/1.1
        USN: uuid:2f402f80-da50-11e1-9b23-0017882a4a01::upnp:rootdevice

    """.trimIndent().lines().joinToString("\r\n") + "\r\n\r\n"

    private val response = """
        HTTP/1.1 200 OK
        CACHE-CONTROL: max-age=1800
        EXT:
        LOCATION: http://192.168.1.5:8200/description.xml
        SERVER: opentvcast/1.0 UPnP/1.1
        ST: upnp:rootdevice
        USN: uuid:2f402f80-da50-11e1-9b23-0017882a4a01::upnp:rootdevice

    """.trimIndent().lines().joinToString("\r\n") + "\r\n\r\n"

    // ─── parsing ─────────────────────────────────────────────────────────────

    @Test
    fun `M-SEARCH is parsed as a search request with its headers`() {
        val m = SsdpMessage.parse(search)!!

        assertTrue(m.isSearch)
        assertEquals("M-SEARCH", m.method)
        assertEquals("ssdp:discover", m.man)
        assertEquals(3, m.mx)
        assertEquals("upnp:rootdevice", m.header("ST"))
    }

    @Test
    fun `NOTIFY is parsed as a notification carrying NTS`() {
        val m = SsdpMessage.parse(notify)!!

        assertTrue(m.isNotify)
        assertEquals("ssdp:alive", m.header("NTS"))
        assertEquals("upnp:rootdevice", m.header("NT"))
        assertNull("a NOTIFY has no ST", m.header("ST"))
    }

    @Test
    fun `a 200 OK response is parsed with its status code`() {
        val m = SsdpMessage.parse(response)!!

        assertFalse(m.isSearch)
        assertEquals(200, m.statusCode)
        assertEquals("http://192.168.1.5:8200/description.xml", m.header("LOCATION"))
    }

    @Test
    fun `header lookup is case-insensitive`() {
        // Real control points spell these every which way; UDP does not care
        // about your capitalisation and neither can we.
        val m = SsdpMessage.parse(search)!!

        assertEquals("upnp:rootdevice", m.header("st"))
        assertEquals("upnp:rootdevice", m.header("St"))
    }

    @Test
    fun `a datagram without a terminating blank line still parses`() {
        // Some senders omit the trailing CRLFCRLF. Losing them silently would
        // mean "works with Windows, invisible to Android".
        val m = SsdpMessage.parse("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\nMAN: \"ssdp:discover\"")!!

        assertEquals("ssdp:all", m.header("ST"))
    }

    @Test
    fun `garbage is rejected rather than parsed into something plausible`() {
        assertNull(SsdpMessage.parse(""))
        assertNull(SsdpMessage.parse("not http at all"))
    }

    // ─── building ────────────────────────────────────────────────────────────

    @Test
    fun `a search response round-trips through encode and parse`() {
        val built = SsdpMessage.searchResponse(
            location = "http://192.168.1.5:8200/description.xml",
            searchTarget = "upnp:rootdevice",
            usn = "uuid:abc::upnp:rootdevice",
            maxAgeSeconds = 1800,
            server = "opentvcast/1.0 UPnP/1.1",
        )

        val parsed = SsdpMessage.parse(built)!!

        assertEquals(200, parsed.statusCode)
        assertEquals("http://192.168.1.5:8200/description.xml", parsed.header("LOCATION"))
        assertEquals("upnp:rootdevice", parsed.header("ST"))
        assertEquals("uuid:abc::upnp:rootdevice", parsed.header("USN"))
        assertEquals("max-age=1800", parsed.header("CACHE-CONTROL"))
        assertTrue("EXT must be present (some control points require it)", parsed.header("EXT") != null)
    }

    @Test
    fun `an alive notification is addressed to the multicast group`() {
        val built = SsdpMessage.notify(
            notificationType = "upnp:rootdevice",
            usn = "uuid:abc::upnp:rootdevice",
            location = "http://192.168.1.5:8200/description.xml",
            alive = true,
            maxAgeSeconds = 1800,
            server = "opentvcast/1.0 UPnP/1.1",
        )

        val parsed = SsdpMessage.parse(built)!!

        assertTrue(parsed.isNotify)
        assertEquals("ssdp:alive", parsed.header("NTS"))
        assertEquals("239.255.255.250:1900", parsed.header("HOST"))
    }

    @Test
    fun `a byebye notification says so`() {
        val built = SsdpMessage.notify(
            notificationType = "upnp:rootdevice",
            usn = "uuid:abc::upnp:rootdevice",
            location = "http://192.168.1.5:8200/description.xml",
            alive = false,
            maxAgeSeconds = 1800,
            server = "opentvcast/1.0 UPnP/1.1",
        )

        assertEquals("ssdp:byebye", SsdpMessage.parse(built)!!.header("NTS"))
    }

    @Test
    fun `every encoded message ends with a blank line`() {
        val built = SsdpMessage.searchResponse(
            location = "http://x/d.xml",
            searchTarget = "ssdp:all",
            usn = "uuid:a::ssdp:all",
            maxAgeSeconds = 60,
            server = "s",
        )
        assertTrue(built.endsWith("\r\n\r\n"))
    }
}
