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

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single-publisher/single-grant implementation of [SurfaceSink].
 *
 * Invariants that make the lease model safe for decoders:
 *
 * 1. At most one outstanding lease at a time. A decoder holding a lease owns
 *    the target exclusively; the next grant only happens after that lease is
 *    [closed][SurfaceLease.close].
 * 2. [unpublish] invalidates the outstanding lease *in place*. The holder
 *    observes `isValid == false` on its next check, which is what lets a decode
 *    loop unwind at a safe point instead of queueing buffers into a destroyed
 *    Surface — the fatal MediaCodec error the upstream static surface caused.
 * 3. A request that times out stays dead: a target published later satisfies
 *    only *new* requests, never ones that already gave up (the cancelled
 *    continuation is removed from the wait queue on cancellation).
 *
 * One waiter is expected today (one decoder at a time); the queue does not
 * depend on it — a publish resolves the oldest waiting request.
 */
class SimpleSurfaceSink : SurfaceSink {

    private val lock = Any()

    /** The currently published target, if any. */
    private var published: VideoTarget? = null
    private var publishedHandle: SurfaceHandle? = null

    /** The live grant, if any. Cleared when the holder closes its lease. */
    private var outstanding: LiveLease? = null

    /** Requests waiting for a target, oldest first. */
    private val waiters = ArrayDeque<CancellableContinuation<VideoTarget>>()

    override fun publish(handle: SurfaceHandle, target: VideoTarget) {
        val toResume: CancellableContinuation<VideoTarget>? = synchronized(lock) {
            published = target
            publishedHandle = handle
            while (waiters.isNotEmpty()) {
                val cont = waiters.removeFirst()
                if (cont.isActive) return@synchronized cont
            }
            null
        }
        // Resume outside the monitor: resuming only schedules the coroutine.
        toResume?.resume(target, onCancellation = null)
    }

    override fun unpublish(handle: SurfaceHandle) {
        synchronized(lock) {
            if (publishedHandle == handle) {
                published = null
                publishedHandle = null
            }
            val lease = outstanding
            if (lease != null && lease.publishedHandle == handle) {
                lease.invalidate()
            }
        }
    }

    override suspend fun acquire(
        requester: SurfaceRequester,
        timeoutMs: Long,
    ): SurfaceLease? = withTimeoutOrNull(timeoutMs) {
        val target: VideoTarget = suspendCancellableCoroutine { cont ->
            val grantedNow: VideoTarget? = synchronized(lock) {
                val existing = published
                if (existing != null) {
                    existing
                } else {
                    cont.invokeOnCancellation { synchronized(lock) { waiters.remove(cont) } }
                    waiters.addLast(cont)
                    null
                }
            }
            if (grantedNow != null) cont.resume(grantedNow, onCancellation = null)
        }

        synchronized(lock) {
            val lease = LiveLease(target, publishedHandle)
            if (outstanding == null) outstanding = lease
            lease
        }
    }

    /** Live grant; [LiveLease.invalidate] flips validity without closing the holder's reference. */
    private inner class LiveLease(
        @Volatile private var heldTarget: VideoTarget?,
        val publishedHandle: SurfaceHandle?,
    ) : SurfaceLease {

        override val target: VideoTarget? get() = heldTarget

        override val isValid: Boolean get() = heldTarget?.isValid == true

        fun invalidate() {
            heldTarget = null
        }

        override fun close() {
            synchronized(lock) {
                if (outstanding === this) outstanding = null
                heldTarget = null
            }
        }
    }
}
