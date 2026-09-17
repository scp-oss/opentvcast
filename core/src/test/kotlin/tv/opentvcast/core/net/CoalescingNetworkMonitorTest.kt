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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [CoalescingNetworkMonitor].
 *
 * Run under virtual time, which is why the class accumulates elapsed time from the
 * poll interval instead of reading a clock: a `System.nanoTime()` reading would be
 * real time even here, and every timing assertion would then have to be a sleep
 * with a tolerance.
 *
 * The behaviour that matters most is not "does it notice a change" — that is one
 * line — but what it does about the *noise*. Bringing up an interface produces a
 * burst of intermediate states, and re-advertising on each one would tear the
 * receiver down and back up several times. Conversely, an interface that flaps up
 * and recovers must produce no report at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingNetworkMonitorTest {

    private val poll = 100L
    private val settle = 200L

    private fun snap(vararg addresses: String, primary: String? = addresses.firstOrNull()) =
        NetworkSnapshot(primaryAddress = primary, addresses = addresses.toSet())

    /** Records how many times the monitor reported a change. */
    private class Recorder {
        var calls = 0
            private set
        var lastError: Throwable? = null

        fun onChanged() { calls++ }
    }

    // ─── baseline ────────────────────────────────────────────────────────────

    @Test
    fun `the snapshot at start is not reported`() = runTest {
        // Starting a receiver is not a network change. Reporting the baseline would
        // make every launch re-advertise the address it had just advertised.
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()

        monitor.start { recorder.onChanged() }
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(0, recorder.calls)
    }

    // ─── detecting a change ──────────────────────────────────────────────────

    @Test
    fun `a settled change is reported once`() = runTest {
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap("192.168.1.99")
        advanceTimeBy(500)
        runCurrent()

        assertEquals(1, recorder.calls)
    }

    @Test
    fun `a change is not reported before it has settled`() = runTest {
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap("192.168.1.99")
        advanceTimeBy(poll + poll / 2)   // one observation, not yet two
        runCurrent()

        assertEquals("settling must not be skipped", 0, recorder.calls)
    }

    @Test
    fun `a change in the primary address alone is reported`() = runTest {
        // Same address set, different choice of which to advertise.
        var current = snap("192.168.1.10", "192.168.1.11", primary = "192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap("192.168.1.10", "192.168.1.11", primary = "192.168.1.11")
        advanceTimeBy(500)
        runCurrent()

        assertEquals(1, recorder.calls)
    }

    @Test
    fun `a change in the address set alone is reported`() = runTest {
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap("192.168.1.10", "2001:db8::1")
        advanceTimeBy(500)
        runCurrent()

        assertEquals(1, recorder.calls)
    }

    // ─── coalescing ──────────────────────────────────────────────────────────

    @Test
    fun `an interface that flaps and recovers is not reported`() = runTest {
        // The interface dropped and came back before the change settled. Nothing
        // changed as far as the sender is concerned, so re-advertising would be
        // pure churn.
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap()                      // radio down
        advanceTimeBy(poll)                   // observed once
        runCurrent()
        current = snap("192.168.1.10")        // radio back
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(0, recorder.calls)
    }

    @Test
    fun `a burst of intermediate states produces one report`() = runTest {
        // What actually happens when an interface comes up: carrier, then a
        // link-local address, then DHCP, then a second address.
        var current = snap()
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap("169.254.10.10")       // link-local only
        advanceTimeBy(poll)
        runCurrent()
        current = snap("169.254.10.10", "192.168.1.10")
        advanceTimeBy(poll)
        runCurrent()
        current = snap("192.168.1.10")        // settled state
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(
            "the receiver re-advertises once for the interface coming up, not three times",
            1,
            recorder.calls,
        )
    }

    @Test
    fun `a second change after a settled one is reported separately`() = runTest {
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap("192.168.1.99")
        advanceTimeBy(500)
        runCurrent()
        current = snap("192.168.1.123")
        advanceTimeBy(500)
        runCurrent()

        assertEquals(2, recorder.calls)
    }

    @Test
    fun `a flap back to the last reported state is not reported`() = runTest {
        // B was reported. Going out to C and back to B inside the settle window is
        // the same flap as before, one level down.
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap("192.168.1.99")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, recorder.calls)

        current = snap("192.168.1.123")   // transient
        advanceTimeBy(poll)
        runCurrent()
        current = snap("192.168.1.99")    // back to what was last advertised
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(1, recorder.calls)
    }

    @Test
    fun `a change back to an earlier identity is reported as a new change`() = runTest {
        // The mirror image of the test above, and worth pinning because treating it
        // as a flap would be a tempting "optimisation". A -> B was reported, so
        // B -> A is a genuinely different identity and the sender is still holding
        // the B advertisement. Not reporting it leaves the receiver unreachable.
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap("192.168.1.99")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, recorder.calls)

        current = snap("192.168.1.10")    // back to the original, which is not the last reported
        advanceTimeBy(500)
        runCurrent()

        assertEquals(2, recorder.calls)
    }

    // ─── lifecycle ───────────────────────────────────────────────────────────

    @Test
    fun `isRunning reflects whether a watch is active`() = runTest {
        val monitor = CoalescingNetworkMonitor(backgroundScope, { snap("192.168.1.10") }, poll, settle)

        assertFalse(monitor.isRunning)
        monitor.start { }
        runCurrent()
        assertTrue(monitor.isRunning)
        monitor.stop()
        assertFalse(monitor.isRunning)
    }

    @Test
    fun `stop is idempotent`() = runTest {
        val monitor = CoalescingNetworkMonitor(backgroundScope, { snap("192.168.1.10") }, poll, settle)

        monitor.start { }
        runCurrent()
        monitor.stop()
        monitor.stop()
        monitor.stop()

        assertFalse(monitor.isRunning)
    }

    @Test
    fun `stop ends reporting`() = runTest {
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()
        monitor.start { recorder.onChanged() }
        runCurrent()

        monitor.stop()
        current = snap("192.168.1.99")
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(0, recorder.calls)
    }

    @Test
    fun `stop before start is harmless`() = runTest {
        val monitor = CoalescingNetworkMonitor(backgroundScope, { snap("192.168.1.10") }, poll, settle)

        monitor.stop()

        assertFalse(monitor.isRunning)
    }

    @Test
    fun `starting twice leaves one watcher, not two`() = runTest {
        // Two pollers would each report the same change, and the receiver would
        // re-advertise twice.
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()

        monitor.start { recorder.onChanged() }
        runCurrent()
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap("192.168.1.99")
        advanceTimeBy(500)
        runCurrent()

        assertEquals(1, recorder.calls)
    }

    @Test
    fun `a restart takes a fresh baseline`() = runTest {
        // The caller re-advertises on start anyway, so re-reporting the address that
        // changed while the monitor was stopped would be a redundant teardown.
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()

        monitor.start { recorder.onChanged() }
        runCurrent()
        monitor.stop()

        current = snap("192.168.1.99")
        monitor.start { recorder.onChanged() }
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(0, recorder.calls)
    }

    @Test
    fun `monitoring resumes after a restart`() = runTest {
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()

        monitor.start { recorder.onChanged() }
        runCurrent()
        monitor.stop()
        current = snap("192.168.1.99")
        monitor.start { recorder.onChanged() }
        runCurrent()

        current = snap("192.168.1.123")
        advanceTimeBy(500)
        runCurrent()

        assertEquals(1, recorder.calls)
    }

    // ─── resilience ──────────────────────────────────────────────────────────

    @Test
    fun `a throwing callback does not end monitoring`() = runTest {
        // The callback re-advertises; if it fails once, the receiver still needs to
        // hear about the *next* network change rather than going deaf for good.
        var current = snap("192.168.1.10")
        val monitor = CoalescingNetworkMonitor(backgroundScope, { current }, poll, settle)
        val recorder = Recorder()

        monitor.start {
            recorder.onChanged()
            throw IllegalStateException("re-advertise failed")
        }
        runCurrent()

        current = snap("192.168.1.99")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, recorder.calls)

        current = snap("192.168.1.123")
        advanceTimeBy(500)
        runCurrent()

        assertEquals(
            "a failed re-advertise must not stop the monitor from reporting the next change",
            2,
            recorder.calls,
        )
        assertTrue(monitor.isRunning)
    }

    // ─── defaults ────────────────────────────────────────────────────────────

    @Test
    fun `the default timings are coherent`() {
        assertTrue(CoalescingNetworkMonitor.DEFAULT_POLL_INTERVAL_MS > 0)
        assertTrue(CoalescingNetworkMonitor.DEFAULT_SETTLE_MS > 0)
        assertTrue(
            "settling must span more than one poll, otherwise coalescing " +
                "cannot suppress a single-poll flap",
            CoalescingNetworkMonitor.DEFAULT_SETTLE_MS > CoalescingNetworkMonitor.DEFAULT_POLL_INTERVAL_MS,
        )
    }

    @Test
    fun `a snapshot with no addresses is representable`() {
        // Offline is a legitimate identity, not an error.
        val offline = NetworkSnapshot(primaryAddress = null, addresses = emptySet())
        assertEquals(null, offline.primaryAddress)
        assertTrue(offline.addresses.isEmpty())
    }
}
