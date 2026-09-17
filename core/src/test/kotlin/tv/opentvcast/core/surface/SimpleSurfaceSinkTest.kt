package tv.opentvcast.core.surface

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SimpleSurfaceSink] — the production implementation of the
 * [SurfaceSink] broker that replaces the upstream static
 * `var mirrorSurface: Surface?`, which leaked the Activity and kept decoders
 * feeding a destroyed Surface.
 *
 * The load-bearing behaviours are the lease rules: a lease must be grantable
 * exactly once at a time, invalidation must be visible to a holder before it
 * can do damage, and a request must never be satisfied by a target published
 * *after* the request timed out.
 */
class SimpleSurfaceSinkTest {

    private class FakeTarget(override val isValid: Boolean = true) : VideoTarget {
        override val handle: Any = Object()
    }

    private val sink = SimpleSurfaceSink()
    private val handle = SurfaceHandle("test")

    // ─── publish / unpublish ─────────────────────────────────────────────────

    @Test
    fun `an acquire is granted immediately when a target is already published`() = runTest {
        val target = FakeTarget()
        sink.publish(handle, target)

        val lease = sink.acquire(SurfaceRequester.AIRPLAY_MIRROR, timeoutMs = 1_000)

        assertNotNull(lease)
        assertTrue(lease!!.isValid)
        assertEquals(target, lease.target)
    }

    @Test
    fun `publishing while a request waits satisfies that request`() = runTest {
        val deferred = async { sink.acquire(SurfaceRequester.AIRPLAY_MIRROR, timeoutMs = 5_000) }
        runCurrent()
        assertTrue("no target yet — must still be waiting", deferred.isActive)

        sink.publish(handle, FakeTarget())
        runCurrent()

        assertNotNull(deferred.await())
    }

    @Test
    fun `a request that times out returns null`() = runTest {
        val deferred = async { sink.acquire(SurfaceRequester.DLNA_VIDEO, timeoutMs = 1_000) }

        advanceTimeBy(1_500)
        runCurrent()

        assertNull(deferred.await())
    }

    @Test
    fun `a target published after a request timed out does not satisfy it`() = runTest {
        val deferred = async { sink.acquire(SurfaceRequester.DLNA_VIDEO, timeoutMs = 1_000) }
        advanceTimeBy(1_500)
        runCurrent()
        assertNull(deferred.await())

        sink.publish(handle, FakeTarget())
        runCurrent()
        // The stale waiter must not be resumed by a later publish.
        assertNull(deferred.await())
    }

    // ─── lease semantics ─────────────────────────────────────────────────────

    @Test
    fun `closing a lease allows the next acquire to be granted`() = runTest {
        sink.publish(handle, FakeTarget())
        val first = sink.acquire(SurfaceRequester.AIRPLAY_MIRROR, timeoutMs = 100)
        assertNotNull(first)
        first!!.close()

        val second = sink.acquire(SurfaceRequester.AIRPLAY_MIRROR, timeoutMs = 100)
        assertNotNull("a closed lease must free the sink for the next requester", second)
    }

    @Test
    fun `unpublish invalidates an outstanding lease`() = runTest {
        sink.publish(handle, FakeTarget())
        val lease = sink.acquire(SurfaceRequester.AIRPLAY_MIRROR, timeoutMs = 100)
        assertNotNull(lease)
        assertTrue(lease!!.isValid)

        sink.unpublish(handle)

        assertFalse(
            "the holder must see the target die before feeding a destroyed surface",
            lease.isValid,
        )
        assertNull(lease.target)
    }

    @Test
    fun `closing twice is safe`() = runTest {
        sink.publish(handle, FakeTarget())
        val lease = sink.acquire(SurfaceRequester.AIRPLAY_MIRROR, timeoutMs = 100)
        assertNotNull(lease)
        lease!!.close()
        lease.close()
    }

    // ─── misc ────────────────────────────────────────────────────────────────

    @Test
    fun `a lease exposes the underlying handle`() = runTest {
        val target = FakeTarget()
        sink.publish(handle, target)
        val lease = sink.acquire(SurfaceRequester.AIRPLAY_URL, timeoutMs = 100)
        assertEquals(target, lease!!.target)
        assertEquals(target.handle, lease.surface)
    }

    @Test
    fun `launching a waiter and publishing from another coroutine resolves it`() = runTest {
        var lease: SurfaceLease? = null
        launch { lease = sink.acquire(SurfaceRequester.AIRPLAY_MIRROR, timeoutMs = 5_000) }
        runCurrent()
        sink.publish(handle, FakeTarget())
        runCurrent()
        assertNotNull(lease)
    }
}
