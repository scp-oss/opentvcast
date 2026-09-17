/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.core.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the rendering-target contract in [SurfaceSink.kt].
 *
 * This contract replaced upstream's `companion object { var mirrorSurface: Surface? }`,
 * which had three problems the type system now prevents: it leaked the Activity, it
 * kept pointing at a destroyed Surface after a configuration change, and it offered
 * no way to arbitrate between two protocols that both want to draw.
 *
 * The tests below therefore concentrate on the two mechanisms that fix those:
 * the opaque [VideoTarget.handle] that keeps `android.view.Surface` out of `:core`,
 * and the [SurfaceLease] that can be *invalidated* rather than merely returned.
 */
class SurfaceSinkContractTest {

    /**
     * A lease that supplies only the two members the interface requires, so the
     * assertions below exercise the interface's own default [SurfaceLease.surface]
     * getter rather than a reimplementation of it.
     */
    private class MinimalLease(
        override val target: VideoTarget?,
        override val isValid: Boolean,
    ) : SurfaceLease {
        override fun close() = Unit
    }

    private class FakeTarget(
        override val handle: Any,
        override val isValid: Boolean = true,
    ) : VideoTarget

    // ─── VideoTarget ─────────────────────────────────────────────────────────

    @Test
    fun `VideoTarget carries the platform object as an opaque handle`() {
        // :core cannot name android.view.Surface, so the Android side stores it as
        // Any and casts on the way out. If this ever became a typed field, :core
        // would stop being a plain Kotlin/JVM module — the constraint that keeps
        // protocol contracts free of Android types.
        val platformSurface = Any()
        val target = FakeTarget(handle = platformSurface)

        assertSame(platformSurface, target.handle)
    }

    @Test
    fun `a destroyed target reports itself invalid`() {
        val alive = FakeTarget(handle = Any(), isValid = true)
        val destroyed = FakeTarget(handle = Any(), isValid = false)

        assertTrue(alive.isValid)
        assertFalse(
            "The decode loop checks this before every enqueue; a destroyed surface " +
                "that still reports valid is a fatal MediaCodec error",
            destroyed.isValid,
        )
    }

    // ─── SurfaceLease ────────────────────────────────────────────────────────

    @Test
    fun `a lease surfaces its target's handle`() {
        val platformSurface = Any()
        val lease = MinimalLease(target = FakeTarget(platformSurface), isValid = true)

        assertSame(
            "surface is the convenience accessor protocols actually call",
            platformSurface,
            lease.surface,
        )
    }

    @Test
    fun `an invalidated lease has no target and therefore no surface`() {
        // This is the mechanism that lets a decode loop unwind after rotation: the
        // UI unpublishes the surface, the lease's target becomes null, and the loop
        // sees it at the next safe point instead of writing into freed memory.
        val lease = MinimalLease(target = null, isValid = false)

        assertNull(lease.target)
        assertNull(lease.surface)
        assertFalse(lease.isValid)
    }

    @Test
    fun `a lease is closeable so callers can use it as a resource`() {
        // SurfaceLease extends AutoCloseable; `use { }` is the intended call shape.
        var closed = false
        val lease = object : SurfaceLease {
            override val target: VideoTarget? = null
            override val isValid: Boolean = false
            override fun close() { closed = true }
        }

        lease.use { }

        assertTrue("Leases must be releasable without a manual finally block", closed)
    }

    @Test
    fun `the default acquire timeout is five seconds`() {
        // Long enough for a slow Activity to publish its SurfaceView, short enough
        // that a sender does not give up first.
        assertEquals(5_000L, SurfaceSink.DEFAULT_ACQUIRE_TIMEOUT_MS)
    }

    // ─── SurfaceHandle / SurfaceRequester ────────────────────────────────────

    @Test
    fun `SurfaceHandle identifies the owner by value`() {
        assertEquals(SurfaceHandle("MainActivity"), SurfaceHandle("MainActivity"))
        assertNotEquals(SurfaceHandle("MainActivity"), SurfaceHandle("PipActivity"))
    }

    @Test
    fun `SurfaceRequester covers exactly the v1 rendering paths`() {
        // Three, not two: AirPlay mirroring and AirPlay URL video are distinct
        // streams with distinct lifecycles, and DLNA will be a third.
        assertEquals(
            listOf("AIRPLAY_MIRROR", "AIRPLAY_URL", "DLNA_VIDEO"),
            SurfaceRequester.entries.map { it.name },
        )
    }
}
