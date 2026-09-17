package tv.opentvcast.dlna.ssdp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * Tests for [SsdpResponder]'s receive loop, driven by a **fake multicast
 * socket** — no device, no emulator, no real network.
 *
 * WHY: SSDP runs over UDP multicast, and multicast is exactly the kind of thing
 * CI cannot rely on (containers and sandboxes often refuse the group join). The
 * answer is not "wait for a device": it is to inject the socket and test the
 * policy — what we answer, to which address, and with which header values.
 *
 * The property worth pinning is the one that silently breaks discovery: the
 * reply must go **back to the sender's address**, not into the multicast group.
 * Answering into the group makes every renderer on the LAN see every reply, and
 * some control points then give up.
 */
class SsdpResponderTest {

    /** A MulticastSocket that hands out queued datagrams instead of reading the network. */
    private class FakeMulticastSocket : MulticastSocket(null) {
        private val queue = java.util.concurrent.LinkedBlockingQueue<DatagramPacket>()
        val sent = java.util.concurrent.CopyOnWriteArrayList<DatagramPacket>()
        @Volatile var closed = false
        @Volatile var onReceive: (() -> DatagramPacket?)? = null

        fun enqueue(packet: DatagramPacket) = queue.add(packet)

        override fun receive(p: DatagramPacket) {
            val next = queue.poll() ?: onReceive?.invoke()
                ?: throw java.net.SocketTimeoutException("no more datagrams")
            p.data = next.data
            p.length = next.length
            p.address = next.address
            p.port = next.port
        }

        override fun send(p: DatagramPacket) { sent.add(p) }

        override fun close() { closed = true; super.close() }

        override fun isClosed(): Boolean = closed
    }

    private val udn = "2f402f80-da50-11e1-9b23-0017882a4a01"
    private val sender = InetAddress.getByName("192.168.1.9")

    private fun search(st: String): DatagramPacket {
        val body = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
            // MX: 0 — the responder may otherwise wait up to MX seconds before
            // replying, which would make this test depend on timing.
            "MAN: \"ssdp:discover\"\r\nMX: 0\r\nST: $st\r\n\r\n"
        return DatagramPacket(body.toByteArray(), body.toByteArray().size, sender, 50000)
    }

    private fun responderWith(fake: FakeMulticastSocket): Pair<SsdpResponder, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.IO)
        val responder = SsdpResponder(
            scope = scope,
            udn = udn,
            descriptionUrl = "http://192.168.1.7:49153/description.xml",
            maxAgeSeconds = 1800,
            socketProvider = { fake },
            groupJoin = { /* fake: joining a real group is not available in CI */ },
        )
        return responder to scope
    }

    @Test
    fun `an M-SEARCH is answered unicast to the sender, not into the group`() {
        val fake = FakeMulticastSocket()
        val (responder, scope) = responderWith(fake)
        try {
            fake.enqueue(search("ssdp:all"))
            // Loop ends once the queue drains and the socket reports closed.
            fake.onReceive = { null }
            responder.start()
            Thread.sleep(400)

            val replies = fake.sent.filter { String(it.data, 0, it.length).startsWith("HTTP/1.1 200 OK") }
            assertTrue("the search must be answered", replies.isNotEmpty())
            assertTrue("the reply must go back to the sender", replies.all { it.address == sender })
            assertTrue("the reply must not go into the group", replies.none { it.address.hostAddress == "239.255.255.250" })
        } finally {
            responder.stop()
            scope.cancel()
        }
    }

    @Test
    fun `the reply carries the description URL and a USN built from our UDN`() {
        val fake = FakeMulticastSocket()
        val (responder, scope) = responderWith(fake)
        try {
            fake.enqueue(search("urn:schemas-upnp-org:service:AVTransport:1"))
            fake.onReceive = { null }
            responder.start()
            Thread.sleep(400)

            val reply = fake.sent.map { String(it.data, 0, it.length) }
                .firstOrNull { it.startsWith("HTTP/1.1 200 OK") }

            assertTrue(reply != null)
            assertTrue(reply!!.contains("LOCATION: http://192.168.1.7:49153/description.xml"))
            assertTrue(reply.contains("ST: urn:schemas-upnp-org:service:AVTransport:1"))
            assertTrue("USN must identify this renderer", reply.contains("USN: uuid:$udn::"))
        } finally {
            responder.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a traffic that is not a search is not answered`() {
        val fake = FakeMulticastSocket()
        val (responder, scope) = responderWith(fake)
        try {
            val notSearch = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNTS: ssdp:alive\r\n\r\n"
            fake.enqueue(
                DatagramPacket(notSearch.toByteArray(), notSearch.toByteArray().size, sender, 50000),
            )
            fake.onReceive = { null }
            responder.start()
            Thread.sleep(400)

            val unicastReplies = fake.sent.filter { it.address == sender }
                .map { String(it.data, 0, it.length) }
                .filter { it.startsWith("HTTP/1.1 200 OK") }
            assertEquals("a NOTIFY must not be answered like a search", 0, unicastReplies.size)
        } finally {
            responder.stop()
            scope.cancel()
        }
    }
}
