/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

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

/**
 * A platform rendering destination.
 *
 * `:core` is a plain Kotlin/JVM module and therefore cannot name
 * `android.view.Surface`. Rather than break that rule — which is the only thing
 * keeping protocol contracts free of Android types — the concrete surface is
 * carried as an opaque [handle] and unwrapped by the Android-side caller:
 *
 * ```kotlin
 * val surface = target.handle as? android.view.Surface ?: return
 * ```
 *
 * On Android [handle] is always an `android.view.Surface`.
 */
interface VideoTarget {

    /** The platform object to render into. Android: `android.view.Surface`. */
    val handle: Any

    /** `false` once the destination has been destroyed and must not be used. */
    val isValid: Boolean
}

/** Identifies who owns a published [VideoTarget]. */
@JvmInline
value class SurfaceHandle(val owner: String)

/** Who is asking for a rendering target. */
enum class SurfaceRequester {
    /** AirPlay screen mirroring. */
    AIRPLAY_MIRROR,

    /** AirPlay URL video content. */
    AIRPLAY_URL,

    /** DLNA pushed media. */
    DLNA_VIDEO,
}

/**
 * Registry that brokers rendering destinations between the UI (which owns them)
 * and protocols (which need them).
 *
 * Replaces the upstream `companion object { var mirrorSurface: Surface? }`
 * static, which leaked the Activity, kept pointing at a destroyed Surface after
 * configuration changes, and offered no way to arbitrate between protocols.
 */
interface SurfaceSink {

    /** Called by the UI when a surface becomes available. */
    fun publish(handle: SurfaceHandle, target: VideoTarget)

    /** Called by the UI when a surface goes away; invalidates outstanding leases. */
    fun unpublish(handle: SurfaceHandle)

    /**
     * Requests a rendering target on behalf of a protocol.
     *
     * Suspends until the UI publishes one or the timeout elapses.
     *
     * @return a lease, or `null` if none became available in time. The caller
     *         MUST [SurfaceLease.close] it; a decoder that keeps feeding a
     *         released surface crashes the process.
     */
    suspend fun acquire(
        requester: SurfaceRequester,
        timeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
    ): SurfaceLease?

    companion object {
        const val DEFAULT_ACQUIRE_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * An exclusive grant to render into a [VideoTarget].
 *
 * Modelling this as a lease rather than a plain getter is deliberate. The
 * upstream lambda `() -> Surface?` could not express "the surface you are
 * holding is now dead", so decoders kept calling `queueInputBuffer` on a
 * destroyed Surface and died with a fatal MediaCodec error. A lease can be
 * invalidated, which lets the decode loop unwind at the next safe point.
 */
interface SurfaceLease : AutoCloseable {

    /** The granted target, or `null` if the lease has been invalidated. */
    val target: VideoTarget?

    /** Convenience access to the underlying Android surface, when present. */
    val surface: Any?
        get() = target?.handle

    /** `false` once the destination was destroyed. Check before every enqueue. */
    val isValid: Boolean
}
