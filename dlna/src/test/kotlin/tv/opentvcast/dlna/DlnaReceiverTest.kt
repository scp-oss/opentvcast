package tv.opentvcast.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.opentvcast.core.net.MulticastLockHandle
import tv.opentvcast.core.net.ReceiverEnvironment
import tv.opentvcast.core.protocol.CastEvent
import tv.opentvcast.core.protocol.CastEventBus
import tv.opentvcast.core.protocol.ProtocolState
import tv.opentvcast.core.surface.SurfaceHandle
import tv.opentvcast.core.surface.SurfaceLease
import tv.opentvcast.core.surface.SurfaceSink
import tv.opentvcast.core.surface.VideoTarget
import tv.opentvcast.dlna.soap.DlnaPlayerPort
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Tests for [DlnaReceiver]'s lifecycle — start, serve, stop — with **no device
 * and no emulator**.
 *
 * WHY THIS MATTERS: "the receiver lifecycle needs a device" is the assumption
 * that keeps whole layers untested. It is not true. What the receiver actually
 * needs is (a) an environment, (b) a port, (c) a player, (d) a network path.
 * All four are injectable:
 *
 * - the environment is a plain interface, faked here;
 * - the port comes from a fake allocator that returns an ephemeral one;
 * - the player arrives through `playerFactory`;
 * - the network path is the loopback interface, which is real TCP.
 *
 * The SSDP socket is the one piece that cannot run in a sandbox (multicast joins
 * are routinely refused), so it is faked too — its *policy* is covered by
 * [tv.opentvcast.dlna.ssdp.SsdpResponderTest].
 *
 * What still needs hardware: `MediaCodec`, the vendor's multicast filtering,
 * and interop with a real sender.
 */
class DlnaReceiverTest {

    /** Records what the receiver did to the platform. */
    private class FakeEnvironment(
        private val receiverScope: CoroutineScope,
        private val portToGrant: Int,
    ) : ReceiverEnvironment {
        val lockAcquires = mutableListOf<String>()
        val lockReleases = mutableListOf<String>()
        val releasedPorts = mutableListOf<Int>()
        var requestCount = 0

        override val scope: CoroutineScope = receiverScope
        override val localAddress: StateFlow<InetAddress?> =
            MutableStateFlow(InetAddress.getByName("127.0.0.1"))
        override val allAddresses: StateFlow<List<InetAddress>> =
            MutableStateFlow(listOf(InetAddress.getByName("127.0.0.1")))
        override val displayName: String = "Test Renderer"
        override val surfaceSink: SurfaceSink = NoSurfaceSink
        override val multicastLock: MulticastLockHandle = object : MulticastLockHandle {
            override var isHeld: Boolean = false
                private set
            override fun acquire(owner: String) { lockAcquires += owner; isHeld = true }
            override fun release(owner: String) { lockReleases += owner; isHeld = lockReleases.size < lockAcquires.size }
        }
        override val eventBus: CastEventBus = object : CastEventBus {
            override val events: SharedFlow<CastEvent> = MutableSharedFlow()
            override suspend fun emit(event: CastEvent) = Unit
        }

        override fun allocatePort(preferred: Int?): Int {
            requestCount++
            return portToGrant
        }

        override fun releasePort(port: Int) { releasedPorts += port }
    }

    /** A sink that never grants a surface: DLNA must still start (audio-only). */
    private object NoSurfaceSink : SurfaceSink {
        override fun publish(handle: SurfaceHandle, target: VideoTarget) = Unit
        override fun unpublish(handle: SurfaceHandle) = Unit
        override suspend fun acquire(requester: tv.opentvcast.core.surface.SurfaceRequester, timeoutMs: Long): SurfaceLease? = null
    }

    /** A player that records calls instead of touching MediaPlayer. */
    private class FakePlayer : DlnaPlayerPort {
        val calls = mutableListOf<String>()
        override var positionMs: Long = 0L
        override var durationMs: Long = -1L
        override fun play(uri: String) { calls += "play:$uri" }
        override fun resume() { calls += "resume" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun seek(targetMs: Long) { calls += "seek:$targetMs" }
        override fun release() { calls += "release" }
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    /** Boots a receiver on loopback and hands the caller its HTTP port. */
    private fun withRunningReceiver(
        block: (receiver: DlnaReceiver, httpPort: Int, player: FakePlayer, env: FakeEnvironment) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.IO)
        val port = freePort()
        val env = FakeEnvironment(scope, port)
        val player = FakePlayer()
        val receiver = DlnaReceiver(
            udn = "2f402f80-da50-11e1-9b23-0017882a4a01",
            playerFactory = { _, _ -> player },
            ssdpSocketProvider = { UnusableMulticastSocket() },
            ssdpGroupJoin = { /* the sandbox refuses real group joins */ },
        )
        try {
            runBlocking { receiver.start(env) }
            // The HTTP server binds asynchronously in the receiver's scope.
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val ready = runCatching { get(port, "/description.xml").first == 200 }.getOrDefault(false)
                if (ready) break
                Thread.sleep(20)
            }
            block(receiver, port, player, env)
        } finally {
            runBlocking { receiver.stop() }
            scope.cancel()
        }
    }

    /** A socket that binds nothing: SSDP is not part of this test. */
    private class UnusableMulticastSocket : java.net.MulticastSocket(null)

    private fun get(port: Int, path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val code = connection.responseCode
            val body = (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.readText() ?: ""
            code to body
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `starting advertises and serves the description on the allocated port`() = runBlocking {
        withRunningReceiver { receiver, port, _, env ->
            assertEquals(ProtocolState.ADVERTISING, receiver.state.value)
            assertTrue("the multicast lock must be held while advertising", env.lockAcquires.contains("dlna"))

            // The port it was GRANTED is the port it serves on (FR-20/FR-33).
            val (code, body) = get(port, "/description.xml")
            assertEquals(200, code)
            assertTrue("the description must carry the configured name", body.contains("Test Renderer"))
        }
    }

    @Test
    fun `stopping releases the port and the multicast lock`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val port = freePort()
        val env = FakeEnvironment(scope, port)
        val receiver = DlnaReceiver(
            udn = "udn",
            playerFactory = { _, _ -> FakePlayer() },
            ssdpSocketProvider = { UnusableMulticastSocket() },
            ssdpGroupJoin = { },
        )
        receiver.start(env)
        receiver.stop()

        assertTrue("the granted port must be returned", env.releasedPorts.contains(port))
        assertTrue("the lock must be released", env.lockReleases.contains("dlna"))
        assertEquals("a stopped receiver reports DISABLED", ProtocolState.DISABLED, receiver.state.value)
        scope.cancel()
    }

    @Test
    fun `stopping releases the player`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val env = FakeEnvironment(scope, freePort())
        val player = FakePlayer()
        val receiver = DlnaReceiver(
            udn = "udn",
            playerFactory = { _, _ -> player },
            ssdpSocketProvider = { UnusableMulticastSocket() },
            ssdpGroupJoin = { },
        )
        receiver.start(env)
        receiver.stop()

        assertTrue("MediaPlayer must be released, or the surface lease leaks", player.calls.contains("release"))
        scope.cancel()
    }
}
