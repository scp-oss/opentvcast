package tv.opentvcast.dlna.ssdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SsdpSearchMatcher] — the decision of *what* to answer to an
 * M-SEARCH, and *when*.
 *
 * WHY: answering every search with every target is the classic mistake. It
 * floods the network, and control points that deduplicate by USN then behave
 * inconsistently. `MAN` must be verified too — without it we would answer
 * traffic that was never a discovery request.
 */
class SsdpSearchMatcherTest {

    private val udn = SsdpTestConstants.UDN

    private val targets = listOf(
        SsdpTarget.ROOT_DEVICE,
        SsdpTarget.MEDIA_RENDERER,
        SsdpTarget.AV_TRANSPORT,
        SsdpTarget.RENDERING_CONTROL,
        SsdpTarget.CONNECTION_MANAGER,
    )

    private fun search(st: String, man: String = "\"ssdp:discover\"", mx: Int = 3): SsdpMessage {
        val body = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: $man\r\n")
            append("MX: $mx\r\n")
            append("ST: $st\r\n\r\n")
        }
        return SsdpMessage.parse(body)!!
    }

    @Test
    fun `ssdp_all matches every advertised target`() {
        assertEquals(targets, SsdpSearchMatcher.matches(search("ssdp:all"), targets, udn))
    }

    @Test
    fun `upnp_rootdevice matches only the root device`() {
        assertEquals(
            listOf(SsdpTarget.ROOT_DEVICE),
            SsdpSearchMatcher.matches(search("upnp:rootdevice"), targets, udn),
        )
    }

    @Test
    fun `searching the device UUID matches the root device`() {
        assertEquals(
            listOf(SsdpTarget.ROOT_DEVICE),
            SsdpSearchMatcher.matches(search("uuid:$udn"), targets, udn),
        )
    }

    @Test
    fun `searching a service type matches that service alone`() {
        assertEquals(
            listOf(SsdpTarget.AV_TRANSPORT),
            SsdpSearchMatcher.matches(search("urn:schemas-upnp-org:service:AVTransport:1"), targets, udn),
        )
    }

    @Test
    fun `searching the device type matches the device target`() {
        assertEquals(
            listOf(SsdpTarget.MEDIA_RENDERER),
            SsdpSearchMatcher.matches(search("urn:schemas-upnp-org:device:MediaRenderer:1"), targets, udn),
        )
    }

    @Test
    fun `an unknown search target matches nothing`() {
        assertTrue(
            SsdpSearchMatcher.matches(search("urn:schemas-upnp-org:service:Foo:1"), targets, udn).isEmpty(),
        )
    }

    @Test
    fun `a message that is not a discovery request is ignored`() {
        // MAN is the field that makes an M-SEARCH a discovery request.
        assertTrue(
            SsdpSearchMatcher.matches(search("ssdp:all", man = "\"ssdp:ignore\""), targets, udn).isEmpty(),
        )
    }

    @Test
    fun `a notification is not answered as if it were a search`() {
        val notify = SsdpMessage.parse(
            "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNTS: ssdp:alive\r\n\r\n",
        )!!

        assertTrue(SsdpSearchMatcher.matches(notify, targets, udn).isEmpty())
    }

    // ─── response spacing ────────────────────────────────────────────────────

    @Test
    fun `the reply delay stays inside the MX window`() {
        assertEquals(0L, SsdpSearchMatcher.responseDelayMs(mxSeconds = 3, random01 = 0.0))
        assertEquals(3000L, SsdpSearchMatcher.responseDelayMs(mxSeconds = 3, random01 = 1.0))
        assertEquals(1500L, SsdpSearchMatcher.responseDelayMs(mxSeconds = 3, random01 = 0.5))
    }

    @Test
    fun `a nonsense MX cannot produce a delay outside the window`() {
        assertEquals(0L, SsdpSearchMatcher.responseDelayMs(mxSeconds = -5, random01 = 0.9))
        assertEquals(0L, SsdpSearchMatcher.responseDelayMs(mxSeconds = 0, random01 = 0.9))
        // Senders have been seen advertising MX: 120; waiting two minutes to be
        // discovered is worse than answering immediately.
        assertEquals(
            SsdpSearchMatcher.MAX_DELAY_MS,
            SsdpSearchMatcher.responseDelayMs(mxSeconds = 120, random01 = 1.0),
        )
    }
}

/** UDN shared by the SSDP tests — a renderer's identity must be stable. */
object SsdpTestConstants {
    const val UDN = "2f402f80-da50-11e1-9b23-0017882a4a01"
}
