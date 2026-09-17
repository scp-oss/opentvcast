/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

/**
 * Tests for [ServerSocketPortAllocator].
 *
 * Two layers, deliberately separated:
 *
 * - **Policy**, against an injected [PortProbe]. Fully deterministic, and this is
 *   where the interesting behaviour lives: when a preferred port is honoured,
 *   when it is substituted, and — crucially — when an *in-process reservation*
 *   outranks the probe.
 * - **Mechanism**, against the real [SystemPortProbe]. A handful of tests that
 *   bind actual sockets, to prove the probe reports reality rather than always
 *   saying yes.
 *
 * The reason this class exists at all: upstream hardcoded 6001/6002 in three
 * files and let `BindException` escape, so a second receiver on the LAN killed the
 * protocol instead of negotiating.
 */
class ServerSocketPortAllocatorTest {

    /**
     * Deterministic [PortProbe].
     *
     * @param bindable ports the fake will grant when asked for them specifically.
     * @param dynamicPorts ports handed out, in order, when asked for "any".
     */
    private class FakeProbe(
        bindable: Set<Int> = emptySet(),
        dynamicPorts: List<Int> = emptyList(),
    ) : PortProbe {
        private val bindable = bindable.toMutableSet()
        private val dynamic = dynamicPorts.toMutableList()

        var probeCount = 0
            private set

        fun makeBindable(port: Int) { bindable += port }
        fun stopBeingBindable(port: Int) { bindable -= port }

        override fun probe(preferred: Int?): Int? {
            probeCount++
            return when (preferred) {
                null -> dynamic.removeFirstOrNull()
                else -> if (bindable.contains(preferred)) preferred else null
            }
        }
    }

    // ─── policy: the preferred port is honoured ──────────────────────────────

    @Test
    fun `a free preferred port is granted as requested`() {
        val allocator = ServerSocketPortAllocator(FakeProbe(bindable = setOf(7000)))

        assertEquals(7000, allocator.allocate(preferred = 7000, owner = "airplay"))
    }

    @Test
    fun `a taken preferred port is substituted rather than failing`() {
        // The core behaviour change from upstream: 6001 being busy is a normal
        // situation, not a fatal one.
        val allocator = ServerSocketPortAllocator(
            FakeProbe(bindable = setOf(49152), dynamicPorts = listOf(49152)),
        )

        val granted = allocator.allocate(preferred = 7000, owner = "airplay")

        assertEquals(49152, granted)
        assertNotEquals("the caller asked for 7000 and must be told otherwise", 7000, granted)
    }

    @Test
    fun `a null preferred port yields a dynamic one`() {
        val allocator = ServerSocketPortAllocator(FakeProbe(dynamicPorts = listOf(51000)))

        assertEquals(51000, allocator.allocate(preferred = null, owner = "dlna"))
    }

    @Test
    fun `each probe refusal consumes a fresh dynamic port`() {
        // A probe can decline for several reasons — taken, out of range, permission
        // denied. Whatever the reason, the allocator's answer is the same, and a
        // second refusal must not hand back the port it just used.
        val allocator = ServerSocketPortAllocator(
            FakeProbe(dynamicPorts = listOf(52000, 52001, 52002)),
        )

        assertEquals(52000, allocator.allocate(preferred = -1, owner = "airplay"))
        assertEquals(52001, allocator.allocate(preferred = 0, owner = "airplay"))
        assertEquals(52002, allocator.allocate(preferred = 70_000, owner = "airplay"))

        assertEquals(3, allocator.snapshot().size)
    }

    // ─── policy: in-process reservations outrank the probe ────────────────────

    @Test
    fun `a port already reserved by another owner is substituted even though it is bindable`() {
        // This is the reason the reservation table exists. The second owner may not
        // have bound the port yet, so a probe would happily call it free and two
        // receivers would then fight over it — the exact upstream failure.
        val probe = FakeProbe(bindable = setOf(7000), dynamicPorts = listOf(53000))
        val allocator = ServerSocketPortAllocator(probe)

        assertEquals(7000, allocator.allocate(preferred = 7000, owner = "airplay"))
        assertEquals(53000, allocator.allocate(preferred = 7000, owner = "dlna"))
    }

    @Test
    fun `re-allocating the same preferred port to the same owner is idempotent`() {
        // A receiver restarting with the same preference should get the same answer,
        // and must not consume a second dynamic port from the pool.
        val probe = FakeProbe(bindable = setOf(7000), dynamicPorts = listOf(53000))
        val allocator = ServerSocketPortAllocator(probe)

        val first = allocator.allocate(preferred = 7000, owner = "airplay")
        val probesAfterFirst = probe.probeCount
        val second = allocator.allocate(preferred = 7000, owner = "airplay")

        assertEquals(first, second)
        assertEquals("an idempotent re-allocate must not probe again", probesAfterFirst, probe.probeCount)
    }

    @Test
    fun `the same owner may hold several ports`() {
        val allocator = ServerSocketPortAllocator(
            FakeProbe(bindable = setOf(7000, 6001), dynamicPorts = listOf(54000)),
        )

        assertEquals(7000, allocator.allocate(preferred = 7000, owner = "airplay"))
        assertEquals(6001, allocator.allocate(preferred = 6001, owner = "airplay"))
        assertEquals(54000, allocator.allocate(preferred = null, owner = "airplay"))

        assertEquals(mapOf(7000 to "airplay", 6001 to "airplay", 54000 to "airplay"), allocator.snapshot())
    }

    // ─── policy: release ─────────────────────────────────────────────────────

    @Test
    fun `only the owner may release a port`() {
        // Otherwise a protocol shutting down could free a port its sibling is still
        // using — the same bug class the multicast lock counts references to avoid.
        val allocator = ServerSocketPortAllocator(
            FakeProbe(bindable = setOf(7000), dynamicPorts = listOf(55000)),
        )
        allocator.allocate(preferred = 7000, owner = "airplay")

        allocator.release(port = 7000, owner = "dlna")

        assertEquals("a non-owner release must be ignored", mapOf(7000 to "airplay"), allocator.snapshot())

        allocator.release(port = 7000, owner = "airplay")
        assertEquals(emptyMap<Int, String>(), allocator.snapshot())

        // And now the port is genuinely available again.
        assertEquals(
            7000,
            allocator.allocate(preferred = 7000, owner = "dlna"),
        )
    }

    @Test
    fun `releasing an unknown port is a no-op`() {
        val allocator = ServerSocketPortAllocator(FakeProbe(bindable = setOf(7000)))
        allocator.allocate(preferred = 7000, owner = "airplay")

        allocator.release(port = 9999, owner = "airplay")

        assertEquals(mapOf(7000 to "airplay"), allocator.snapshot())
    }

    @Test
    fun `snapshot is a copy`() {
        val allocator = ServerSocketPortAllocator(FakeProbe(bindable = setOf(7000, 6001)))
        allocator.allocate(preferred = 7000, owner = "airplay")

        val taken = allocator.snapshot()
        allocator.allocate(preferred = 6001, owner = "dlna")
        allocator.release(port = 7000, owner = "airplay")

        assertEquals(
            "a snapshot must not change when the allocator does",
            mapOf(7000 to "airplay"),
            taken,
        )
        assertNull(taken[6001])
    }

    // ─── policy: giving up ───────────────────────────────────────────────────

    @Test
    fun `throws when neither the preferred nor any dynamic port can be bound`() {
        val allocator = ServerSocketPortAllocator(FakeProbe())

        try {
            allocator.allocate(preferred = 7000, owner = "airplay")
            fail("Expected PortBindException")
        } catch (e: PortBindException) {
            assertEquals(
                "the exception must report the port the caller wanted, not the " +
                    "dynamic port that also failed",
                7000,
                e.port,
            )
            assertTrue(e.message!!.contains("7000"))
        }
    }

    @Test
    fun `throws with a null port when a dynamic allocation fails`() {
        val allocator = ServerSocketPortAllocator(FakeProbe())

        try {
            allocator.allocate(preferred = null, owner = "dlna")
            fail("Expected PortBindException")
        } catch (e: PortBindException) {
            assertNull("there was no preferred port to report", e.port)
        }
    }

    @Test
    fun `rejects a probe result that another owner already holds`() {
        // Defends against a misbehaving probe handing out an occupied port. Better a
        // loud failure than two receivers silently sharing one socket.
        val allocator = ServerSocketPortAllocator(
            FakeProbe(bindable = setOf(7000), dynamicPorts = listOf(7000)),
        )
        allocator.allocate(preferred = 7000, owner = "airplay")

        try {
            allocator.allocate(preferred = null, owner = "dlna")
            fail("Expected PortBindException")
        } catch (e: PortBindException) {
            assertTrue(e.message!!.contains("already reserved"))
        }
    }

    @Test
    fun `a failed allocation reserves nothing`() {
        val allocator = ServerSocketPortAllocator(FakeProbe())

        runCatching { allocator.allocate(preferred = 7000, owner = "airplay") }

        assertEquals(emptyMap<Int, String>(), allocator.snapshot())
    }

    @Test
    fun `several owners each get their own dynamic port`() {
        val allocator = ServerSocketPortAllocator(
            FakeProbe(dynamicPorts = listOf(56001, 56002, 56003)),
        )

        val a = allocator.allocate(preferred = null, owner = "airplay")
        val b = allocator.allocate(preferred = null, owner = "dlna")
        val c = allocator.allocate(preferred = null, owner = "dacp")

        assertEquals(3, setOf(a, b, c).size)
        assertEquals(mapOf(a to "airplay", b to "dlna", c to "dacp"), allocator.snapshot())
    }

    // ─── mechanism: the real probe binds real sockets ─────────────────────────

    @Test
    fun `the real probe refuses a port a real listener holds`() {
        // Deterministic: the socket stays open for the duration of the assertion.
        ServerSocket(0).use { listener ->
            val allocator = ServerSocketPortAllocator()

            val granted = allocator.allocate(preferred = listener.localPort, owner = "airplay")

            assertNotEquals(
                "a port with a live listener must not be granted",
                listener.localPort,
                granted,
            )
            assertTrue("the substitute must be a usable port", granted in 1..65535)
        }
    }

    @Test
    fun `the real probe grants a genuinely free port`() {
        val freePort = ServerSocket(0).use { it.localPort }
        val allocator = ServerSocketPortAllocator()

        assertEquals(freePort, allocator.allocate(preferred = freePort, owner = "airplay"))
    }

    @Test
    fun `the real probe produces a usable dynamic port`() {
        val allocator = ServerSocketPortAllocator()

        val granted = allocator.allocate(preferred = null, owner = "airplay")

        assertTrue("got $granted", granted in 1..65535)
    }

    @Test
    fun `the real probe rejects ports outside the valid range`() {
        // Directly on SystemPortProbe, because this is the one check the fake probe
        // does not model. 0 is the important case: the OS treats it as "any port",
        // so without the guard it would report success and hand back a port the
        // caller never asked for.
        for (bad in listOf(-1, 0, 65_536, 100_000)) {
            assertNull("SystemPortProbe must refuse $bad", SystemPortProbe.probe(bad))
        }
    }

    @Test
    fun `the allocator substitutes when the real probe refuses the preferred port`() {
        val allocator = ServerSocketPortAllocator()

        val granted = allocator.allocate(preferred = 0, owner = "airplay")

        assertTrue("got $granted", granted in 1..65535)
    }

    @Test
    fun `the real probe is exercised end to end through the default allocator`() {
        // No injected probe: proves the shipped default is wired up, not just the
        // policy around it.
        val allocator: PortAllocator = ServerSocketPortAllocator()
        ServerSocket(0).use { listener ->
            val granted = allocator.allocate(preferred = listener.localPort, owner = "airplay")

            assertNotEquals(listener.localPort, granted)
            assertEquals(mapOf(granted to "airplay"), allocator.snapshot())
        }
    }
}
