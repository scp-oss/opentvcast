package tv.opentvcast.core.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.opentvcast.core.protocol.RegistryEventBus
import tv.opentvcast.core.protocol.ReceiverRegistry
import tv.opentvcast.core.surface.SimpleSurfaceSink
import java.net.InetAddress

/**
 * Tests for [DefaultReceiverEnvironment] — the composition root that turns the
 * four network contracts into one object a [tv.opentvcast.core.protocol.ProtocolReceiver]
 * consumes. The composition itself is the tested behaviour: an environment must
 * forward port requests to its allocator, multicast acquisitions to the
 * reference-counted lock, and address reads to its selector — and nothing more.
 */
class DefaultReceiverEnvironmentTest {

    private class FakePlatformLock : PlatformMulticastLock {
        var acquired = 0
        var released = 0
        override fun acquire() { acquired++ }
        override fun release() { released++ }
    }

    private class FakeAllocator : PortAllocator {
        val reserved = LinkedHashMap<Int, String>()
        var nextDynamic = 40_000
        val released = mutableListOf<Pair<Int, String>>()
        override fun allocate(preferred: Int?, owner: String): Int {
            val port = preferred ?: nextDynamic++
            reserved[port] = owner
            return port
        }
        override fun release(port: Int, owner: String) {
            released.add(port to owner)
            reserved.remove(port)
        }
        override fun snapshot(): Map<Int, String> = reserved.toMap()
    }

    private class FakeSelector(
        private var primary: InetAddress?,
        private var all: List<InetAddress> = listOfNotNull(primary),
    ) : InterfaceSelector {
        var readCount = 0
        override fun selectAddress(): InetAddress? { readCount++; return primary }
        override fun allAddresses(): List<InetAddress> { return all }
        fun changeTo(newPrimary: InetAddress?, newAll: List<InetAddress>) {
            primary = newPrimary; all = newAll
        }
    }

    private fun local(addr: String): InetAddress = InetAddress.getByName(addr)

    // ─── composition ─────────────────────────────────────────────────────────

    @Test
    fun `allocatePort reserves through the allocator and releasePort returns it`() = runTest {
        val allocator = FakeAllocator()
        val env = DefaultReceiverEnvironment(
            scope = backgroundScope,
            displayName = "Living Room TV",
            platformLock = FakePlatformLock(),
            addressSource = FakeSelector(local("192.168.1.10")),
            allocator = allocator,
        )

        val port = env.allocatePort(7000)
        assertEquals(7000, port)
        assertEquals("receiver", allocator.reserved[7000])

        env.releasePort(port)
        assertEquals(listOf(7000 to "receiver"), allocator.released)
    }

    @Test
    fun `a null preferred port yields a dynamic one`() = runTest {
        val env = DefaultReceiverEnvironment(
            scope = backgroundScope,
            displayName = "TV",
            platformLock = FakePlatformLock(),
            addressSource = FakeSelector(local("192.168.1.10")),
            allocator = FakeAllocator(),
        )

        val port = env.allocatePort(null)
        assertEquals(40_000, port)
    }

    @Test
    fun `the multicast lock is reference-counted over the platform lock`() = runTest {
        val platform = FakePlatformLock()
        val env = DefaultReceiverEnvironment(
            scope = backgroundScope,
            displayName = "TV",
            platformLock = platform,
            addressSource = FakeSelector(local("192.168.1.10")),
        )

        env.multicastLock.acquire("airplay")
        env.multicastLock.acquire("dlna")
        assertEquals("still one platform acquisition while two owners hold it", 1, platform.acquired)

        env.multicastLock.release("airplay")
        assertEquals("the platform lock outlives the first owner to let go", 0, platform.released)

        env.multicastLock.release("dlna")
        assertEquals(1, platform.released)
    }

    @Test
    fun `localAddress and allAddresses reflect the selector at construction`() = runTest {
        val all = listOf(local("192.168.1.10"), local("192.168.1.11"))
        val env = DefaultReceiverEnvironment(
            scope = backgroundScope,
            displayName = "TV",
            platformLock = FakePlatformLock(),
            addressSource = FakeSelector(local("192.168.1.10"), all),
        )

        assertEquals(local("192.168.1.10"), env.localAddress.value)
        assertEquals(all, env.allAddresses.value)
    }

    @Test
    fun `refreshNetwork re-reads the selector and updates the flows`() = runTest {
        val selector = FakeSelector(local("192.168.1.10"))
        val env = DefaultReceiverEnvironment(
            scope = backgroundScope,
            displayName = "TV",
            platformLock = FakePlatformLock(),
            addressSource = selector,
        )

        selector.changeTo(local("192.168.1.99"), listOf(local("192.168.1.99")))
        env.refreshNetwork()

        assertEquals(local("192.168.1.99"), env.localAddress.value)
        assertEquals(listOf(local("192.168.1.99")), env.allAddresses.value)
    }

    @Test
    fun `refreshNetwork publishes a null primary while offline`() = runTest {
        val selector = FakeSelector(local("192.168.1.10"))
        val env = DefaultReceiverEnvironment(
            scope = backgroundScope,
            displayName = "TV",
            platformLock = FakePlatformLock(),
            addressSource = selector,
        )

        selector.changeTo(null, emptyList())
        env.refreshNetwork()

        assertNull(env.localAddress.value)
        assertTrue(env.allAddresses.value.isEmpty())
    }

    // ─── wiring the rest of the contract ─────────────────────────────────────

    @Test
    fun `the event bus is the registry's own event stream`() = runTest {
        val registry = ReceiverRegistry()
        val env = DefaultReceiverEnvironment(
            scope = backgroundScope,
            displayName = "TV",
            platformLock = FakePlatformLock(),
            addressSource = FakeSelector(local("192.168.1.10")),
            eventBus = RegistryEventBus(registry),
        )

        // Identity, not equality: a protocol emitting through the environment bus
        // and the service collecting from registry.events must be one stream.
        assertTrue(env.eventBus.events === registry.events)
    }

    @Test
    fun `displayName and surfaceSink are the configured instances`() = runTest {
        val sink = SimpleSurfaceSink()
        val env = DefaultReceiverEnvironment(
            scope = backgroundScope,
            displayName = "Living Room TV",
            platformLock = FakePlatformLock(),
            addressSource = FakeSelector(local("192.168.1.10")),
            surfaceSink = sink,
        )

        assertEquals("Living Room TV", env.displayName)
        assertEquals(sink, env.surfaceSink)
        assertFalse(env.scope === this.coroutineContext)
    }
}
