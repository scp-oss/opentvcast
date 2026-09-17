package tv.opentvcast.dlna.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [GenaSubscriptionManager] — UPnP eventing (FR-35).
 *
 * WHY: eventing is how a control point learns that playback stopped or the
 * volume changed without polling. Get it wrong and the sender's UI freezes on
 * a stale state — it still plays, but the remote shows the wrong thing and the
 * user concludes the TV is not responding.
 *
 * Nothing here touches a socket: subscriptions are state, and the decisions
 * (renew, expire, unsubscribe) are what matter. Delivery is someone else's job.
 */
class GenaSubscriptionManagerTest {

    private fun manager(
        ids: Iterator<String> = listOf("sid-1", "sid-2", "sid-3").iterator(),
        clock: () -> Long = { 0L },
    ) = GenaSubscriptionManager(
        idSource = { if (ids.hasNext()) ids.next() else "sid-extra" },
        clock = clock,
    )

    @Test
    fun `subscribing registers a callback and returns a SID`() {
        val manager = manager()

        val sid = manager.subscribe(
            serviceId = "urn:upnp-org:serviceId:AVTransport",
            callbackUrl = "http://192.168.1.9:9000/event",
            timeoutSeconds = 1800,
        )

        assertTrue(sid.isNotBlank())
        assertEquals(1, manager.subscribers("urn:upnp-org:serviceId:AVTransport").size)
        assertEquals(1800, manager.subscribers("urn:upnp-org:serviceId:AVTransport").first().timeoutSeconds)
    }

    @Test
    fun `subscriptions are per service`() {
        val manager = manager()
        manager.subscribe("urn:upnp-org:serviceId:AVTransport", "http://a", 1800)
        manager.subscribe("urn:upnp-org:serviceId:RenderingControl", "http://b", 1800)

        assertEquals(1, manager.subscribers("urn:upnp-org:serviceId:AVTransport").size)
        assertEquals(1, manager.subscribers("urn:upnp-org:serviceId:RenderingControl").size)
    }

    @Test
    fun `each subscriber gets its own SID`() {
        val manager = manager()

        val a = manager.subscribe("urn:upnp-org:serviceId:AVTransport", "http://a", 1800)
        val b = manager.subscribe("urn:upnp-org:serviceId:AVTransport", "http://b", 1800)

        assertTrue("SIDs must be unique", a != b)
    }

    @Test
    fun `renewing extends the timeout and unknown SIDs are refused`() {
        val manager = manager()
        val sid = manager.subscribe("urn:upnp-org:serviceId:AVTransport", "http://a", 1800)

        assertTrue(manager.renew(sid, 300))
        assertEquals(300, manager.subscribers("urn:upnp-org:serviceId:AVTransport").first().timeoutSeconds)
        assertFalse(manager.renew("uuid:not-a-real-sid", 300))
    }

    @Test
    fun `a second renewal reuses the same subscription rather than adding one`() {
        val manager = manager()
        val sid = manager.subscribe("urn:upnp-org:serviceId:AVTransport", "http://a", 1800)

        manager.renew(sid, 600)
        manager.renew(sid, 900)

        val subscribers = manager.subscribers("urn:upnp-org:serviceId:AVTransport")
        assertEquals(1, subscribers.size)
        assertEquals(900, subscribers.first().timeoutSeconds)
    }

    @Test
    fun `unsubscribing removes it and unknown SIDs are refused`() {
        val manager = manager()
        val sid = manager.subscribe("urn:upnp-org:serviceId:AVTransport", "http://a", 1800)

        assertTrue(manager.unsubscribe(sid))
        assertTrue(manager.subscribers("urn:upnp-org:serviceId:AVTransport").isEmpty())
        assertFalse(manager.unsubscribe(sid))
    }

    @Test
    fun `an Infinite timeout never expires`() {
        val manager = manager(clock = { 0L })
        manager.subscribe("urn:upnp-org:serviceId:AVTransport", "http://a", GenaSubscriptionManager.INFINITE)

        val expired = manager.expire(nowMs = Long.MAX_VALUE / 4)

        assertTrue("an INFINITE subscription must not expire", expired.isEmpty())
    }

    @Test
    fun `expired subscriptions are reported and dropped`() {
        // Clock starts at 0, so a 300 s subscription is gone by 301 000 ms and a
        // 1800 s one is not. (The first draft used a wall-clock manager and a
        // 1970 timestamp, which expired nothing.)
        val manager = manager(clock = { 0L })
        val sid = manager.subscribe("urn:upnp-org:serviceId:AVTransport", "http://a", 300)
        manager.subscribe("urn:upnp-org:serviceId:AVTransport", "http://b", 1800)

        val expired = manager.expire(nowMs = 301_000L)

        assertEquals(listOf(sid), expired)
        assertEquals(1, manager.subscribers("urn:upnp-org:serviceId:AVTransport").size)
    }

    // ─── NOTIFY bodies ───────────────────────────────────────────────────────

    @Test
    fun `a NOTIFY body is the LastChange property set a control point expects`() {
        val xml = GenaSubscriptionManager.notifyBody(
            "urn:upnp-org:serviceId:AVTransport",
            mapOf("TransportState" to "PLAYING"),
        )

        assertTrue(xml.contains("<e:propertyset"))
        assertTrue(xml.contains("<e:property>"))
        assertTrue(xml.contains("<LastChange>"))
        assertTrue(xml.contains("TransportState"))
    }

    @Test
    fun `values inside a NOTIFY body are XML-escaped`() {
        // A DIDL metadata blob is XML already; emitting it unescaped produces a
        // body the control point cannot parse.
        val xml = GenaSubscriptionManager.notifyBody(
            "urn:upnp-org:serviceId:AVTransport",
            mapOf("CurrentTrackMetaData" to "<DIDL-Lite>"),
        )

        assertTrue(xml.contains("&lt;DIDL-Lite&gt;"))
    }
}
