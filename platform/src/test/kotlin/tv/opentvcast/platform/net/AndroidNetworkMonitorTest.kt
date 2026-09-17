/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.platform.net

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.opentvcast.core.net.NetworkSnapshot

/**
 * Tests for [AndroidNetworkMonitor]'s event-driven coalescing.
 *
 * The coalescing behaviour mirrors [tv.opentvcast.core.net.CoalescingNetworkMonitor]
 * but is driven by platform signals instead of a poll loop, so the tests inject a
 * fake signal dispatcher and run under the virtual-time test dispatcher: no
 * sleeping, no flakiness, and the settle window is asserted exactly.
 *
 * This class is deliberately **plain JUnit**: the coalescing logic needs no
 * Android runtime, so it runs anywhere (including the SDK-free aggregate runner),
 * and JaCoCo can see it. The real `ConnectivityManager` registration path lives in
 * `AndroidNetworkMonitorSmokeTest`, which does need Robolectric.
 */
class AndroidNetworkMonitorTest {

    private class Harness(val settleMs: Long = 1_000) {
        var current: NetworkSnapshot = NetworkSnapshot("192.168.1.10", setOf("192.168.1.10"))
        var dispatch: (() -> Unit)? = null

        /**
         * The factory every monitor in this file is built with: platform signals
         * arrive through [signal], unregistering withdraws it.
         */
        val signalsFactory: (NetworkSignals) -> AutoCloseable = { handler ->
            dispatch = { handler.onSignal() }
            AutoCloseable { dispatch = null }
        }

        fun signal() = dispatch?.invoke()
    }

    // ─── Baseline ─────────────────────────────────────────────────────────────

    @Test
    fun `the snapshot taken at start is the baseline and is not reported`() = runTest {
        val h = Harness()
        var changeCount = 0
        val monitor = AndroidNetworkMonitor(
            scope = backgroundScope,
            snapshot = { h.current },
            signalsFactory = h.signalsFactory,
            settleMs = h.settleMs,
        )
        monitor.start { changeCount++ }

        // Several signals with no identity change at all.
        h.signal(); h.signal(); h.signal()
        advanceTimeBy(h.settleMs * 3)

        assertEquals(0, changeCount)
    }

    // ─── Settle window ────────────────────────────────────────────────────────

    @Test
    fun `a change that persists through the settle window is reported once`() = runTest {
        val h = Harness(settleMs = 1_000)
        var changeCount = 0
        val monitor = AndroidNetworkMonitor(
            scope = backgroundScope,
            snapshot = { h.current },
            signalsFactory = h.signalsFactory,
            settleMs = h.settleMs,
        )
        monitor.start { changeCount++ }

        h.current = NetworkSnapshot("192.168.1.11", setOf("192.168.1.11"))
        h.signal()

        advanceTimeBy(500)
        assertEquals("not yet settled", 0, changeCount)

        advanceTimeBy(500)
        runCurrent() // a task scheduled exactly at the target time needs its own run pass
        assertEquals("settled exactly once", 1, changeCount)

        // Further signals with the same identity report nothing more.
        h.signal()
        advanceTimeBy(h.settleMs)
        runCurrent()
        assertEquals(1, changeCount)
    }

    @Test
    fun `a flapping interface that recovers produces no report`() = runTest {
        val h = Harness(settleMs = 1_000)
        var changeCount = 0
        val monitor = AndroidNetworkMonitor(
            scope = backgroundScope,
            snapshot = { h.current },
            signalsFactory = h.signalsFactory,
            settleMs = h.settleMs,
        )
        monitor.start { changeCount++ }

        h.current = NetworkSnapshot("192.168.1.11", setOf("192.168.1.11"))
        h.signal()
        advanceTimeBy(400)

        // Back to baseline before settling — no report, and the pending timer dies.
        h.current = NetworkSnapshot("192.168.1.10", setOf("192.168.1.10"))
        h.signal()
        advanceTimeBy(h.settleMs * 2)

        assertEquals(0, changeCount)

        // And a later genuine change still settles normally (timer was reset, not lost).
        h.current = NetworkSnapshot("192.168.1.11", setOf("192.168.1.11"))
        h.signal()
        advanceTimeBy(h.settleMs)
        runCurrent()
        assertEquals(1, changeCount)
    }

    @Test
    fun `a new identity during the settle window restarts the timer`() = runTest {
        val h = Harness(settleMs = 1_000)
        var changeCount = 0
        val monitor = AndroidNetworkMonitor(
            scope = backgroundScope,
            snapshot = { h.current },
            signalsFactory = h.signalsFactory,
            settleMs = h.settleMs,
        )
        monitor.start { changeCount++ }

        h.current = NetworkSnapshot("192.168.1.11", setOf("192.168.1.11"))
        h.signal()
        advanceTimeBy(700)

        h.current = NetworkSnapshot("192.168.1.12", setOf("192.168.1.12"))
        h.signal()
        advanceTimeBy(700) // 1400 total; only 700 on the second candidate
        assertEquals("first candidate's timer was cancelled", 0, changeCount)

        advanceTimeBy(300) // second candidate reaches 1000
        runCurrent()
        assertEquals(1, changeCount)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    fun `stop silences further signals`() = runTest {
        val h = Harness(settleMs = 1_000)
        var changeCount = 0
        val monitor = AndroidNetworkMonitor(
            scope = backgroundScope,
            snapshot = { h.current },
            signalsFactory = h.signalsFactory,
            settleMs = h.settleMs,
        )
        monitor.start { changeCount++ }

        h.current = NetworkSnapshot("192.168.1.11", setOf("192.168.1.11"))
        monitor.stop()
        h.signal()
        advanceTimeBy(h.settleMs * 2)

        assertEquals(0, changeCount)
        assertFalse(monitor.isRegistered)
    }

    @Test
    fun `restart after stop re-registers and works`() = runTest {
        val h = Harness(settleMs = 1_000)
        var changeCount = 0
        val monitor = AndroidNetworkMonitor(
            scope = backgroundScope,
            snapshot = { h.current },
            signalsFactory = h.signalsFactory,
            settleMs = h.settleMs,
        )
        monitor.start { changeCount++ }
        monitor.stop()

        monitor.start { changeCount++ }
        assertTrue(monitor.isRegistered)

        h.current = NetworkSnapshot("10.0.0.1", setOf("10.0.0.1"))
        h.signal()
        advanceTimeBy(h.settleMs)
        runCurrent()
        assertEquals(1, changeCount)
    }

    @Test
    fun `an exception thrown by the callback is contained`() = runTest {
        val h = Harness(settleMs = 1_000)
        val monitor = AndroidNetworkMonitor(
            scope = backgroundScope,
            snapshot = { h.current },
            signalsFactory = h.signalsFactory,
            settleMs = h.settleMs,
        )
        var throwNext = true
        monitor.start {
            if (throwNext) {
                throwNext = false
                throw IllegalStateException("re-advertise failed")
            }
        }

        h.current = NetworkSnapshot("192.168.1.11", setOf("192.168.1.11"))
        h.signal()
        advanceTimeBy(h.settleMs)
        runCurrent()

        // The monitor is still watching: the next settled change is delivered too.
        h.current = NetworkSnapshot("192.168.1.12", setOf("192.168.1.12"))
        h.signal()
        advanceTimeBy(h.settleMs)
        runCurrent()

        assertFalse(throwNext)
    }

    // ─── Degraded modes ──────────────────────────────────────────────────────

    @Test
    fun `a monitor with no platform registration never reports`() = runTest {
        // The default in this class: no factory means no signals, which is the
        // documented behaviour rather than an error.
        val h = Harness(settleMs = 1_000)
        var changeCount = 0
        val monitor = AndroidNetworkMonitor(
            scope = backgroundScope,
            snapshot = { h.current },
            signalsFactory = null,
            settleMs = h.settleMs,
        )
        monitor.start { changeCount++ }

        h.current = NetworkSnapshot("192.168.1.99", setOf("192.168.1.99"))
        advanceTimeBy(h.settleMs * 3)
        runCurrent()

        assertEquals(0, changeCount)
    }

    @Test
    fun `a refused registration degrades instead of throwing`() = runTest {
        // Some TV firmwares refuse the callback registration outright. The service
        // must keep running with re-advertise-on-change disabled.
        val monitor = AndroidNetworkMonitor(
            scope = backgroundScope,
            snapshot = { NetworkSnapshot("192.168.1.10", setOf("192.168.1.10")) },
            signalsFactory = { throw IllegalStateException("registration refused") },
            settleMs = 1_000,
        )

        monitor.start { }
        assertFalse(monitor.isRegistered)
        monitor.stop()
    }
}
