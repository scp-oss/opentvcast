/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RefCountedMulticastLock].
 *
 * The load-bearing test here is [flapping one holder does not disturb the other].
 * Both discovery mechanisms need the lock and they do not share a lifetime:
 * AirPlay's mDNS advertising runs for the whole receiver lifetime, while its DACP
 * discovery client starts when a session is established. If the lock were not
 * counted, whichever finished first would drop it and the other would go deaf
 * mid-session — with no error, because a multicast socket that receives nothing
 * looks exactly like a quiet network.
 */
class RefCountedMulticastLockTest {

    /** Stands in for `WifiManager.MulticastLock`, counting calls. */
    private class FakePlatformLock : PlatformMulticastLock {
        var acquireCount = 0
        var releaseCount = 0
        val isHeld: Boolean get() = acquireCount > releaseCount

        override fun acquire() { acquireCount++ }
        override fun release() { releaseCount++ }
    }

    private fun fixture(): Pair<RefCountedMulticastLock, FakePlatformLock> {
        val platform = FakePlatformLock()
        return RefCountedMulticastLock(platform) to platform
    }

    @Test
    fun `starts unheld and does not touch the platform lock`() {
        val (lock, platform) = fixture()

        assertFalse(lock.isHeld)
        assertEquals("constructing must not acquire", 0, platform.acquireCount)
        assertEquals(0, platform.releaseCount)
    }

    @Test
    fun `the first acquire takes the platform lock`() {
        val (lock, platform) = fixture()

        lock.acquire("airplay")

        assertTrue(lock.isHeld)
        assertTrue(platform.isHeld)
        assertEquals(1, platform.acquireCount)
    }

    @Test
    fun `a second owner does not acquire the platform lock again`() {
        // The platform lock is not itself counted by us; taking it twice would make
        // the pairing asymmetric.
        val (lock, platform) = fixture()

        lock.acquire("airplay")
        lock.acquire("dacp")

        assertEquals(1, platform.acquireCount)
        assertEquals(setOf("airplay", "dacp"), lock.owners)
    }

    @Test
    fun `flapping one holder does not disturb the other`() {
        val (lock, platform) = fixture()

        lock.acquire("airplay")   // long-lived advertising
        lock.acquire("dacp")      // per-session discovery

        lock.release("dacp")      // session ends

        assertTrue(
            "Releasing the session-scoped holder must not drop the lock that " +
                "mDNS advertising is still relying on",
            lock.isHeld,
        )
        assertTrue(platform.isHeld)
        assertEquals("the platform lock must never have been released", 0, platform.releaseCount)
        assertEquals(setOf("airplay"), lock.owners)
    }

    @Test
    fun `the platform lock is released only when the last holder lets go`() {
        val (lock, platform) = fixture()

        lock.acquire("airplay")
        lock.acquire("dacp")
        lock.release("airplay")

        assertEquals(0, platform.releaseCount)

        lock.release("dacp")

        assertFalse(lock.isHeld)
        assertEquals(1, platform.releaseCount)
    }

    @Test
    fun `acquire is idempotent per owner tag`() {
        // A receiver that restarts without a matching release must not be able to
        // inflate the count and leak the lock for the life of the process.
        val (lock, platform) = fixture()

        lock.acquire("airplay")
        lock.acquire("airplay")

        assertEquals(1, platform.acquireCount)

        lock.release("airplay")

        assertFalse(
            "One release must undo all acquisitions under the same tag",
            lock.isHeld,
        )
        assertEquals(1, platform.releaseCount)
    }

    @Test
    fun `repeated acquire and release cycles are balanced`() {
        val (lock, platform) = fixture()

        repeat(5) {
            lock.acquire("airplay")
            lock.release("airplay")
        }

        assertEquals(5, platform.acquireCount)
        assertEquals(5, platform.releaseCount)
        assertFalse(lock.isHeld)
    }

    @Test
    fun `releasing an owner that never acquired is a no-op`() {
        // Must not release the platform lock: that would drop it out from under a
        // real holder.
        val (lock, platform) = fixture()
        lock.acquire("airplay")

        lock.release("nobody")

        assertTrue(lock.isHeld)
        assertEquals(0, platform.releaseCount)
    }

    @Test
    fun `releasing when nothing is held is a no-op`() {
        val (lock, platform) = fixture()

        lock.release("airplay")
        lock.release("airplay")

        assertEquals(0, platform.releaseCount)
        assertFalse(lock.isHeld)
    }

    @Test
    fun `an owner may re-acquire after its own release`() {
        val (lock, platform) = fixture()

        lock.acquire("dacp")
        lock.release("dacp")
        lock.acquire("dacp")

        assertTrue(lock.isHeld)
        assertEquals(2, platform.acquireCount)
        assertEquals(setOf("dacp"), lock.owners)
    }

    @Test
    fun `owners reflects the current holder set`() {
        val (lock, _) = fixture()

        lock.acquire("a")
        lock.acquire("b")
        lock.acquire("c")
        lock.release("b")

        assertEquals(setOf("a", "c"), lock.owners)
    }

    @Test
    fun `owners is a snapshot, not a live view`() {
        val (lock, _) = fixture()
        lock.acquire("airplay")

        val taken = lock.owners
        lock.acquire("dacp")

        assertEquals("mutating the lock must not mutate an earlier snapshot", setOf("airplay"), taken)
    }
}
