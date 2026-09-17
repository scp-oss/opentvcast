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

/**
 * The platform's own multicast lock: one acquire/release pair over whatever the
 * OS provides. On Android that is a `WifiManager.MulticastLock`.
 *
 * This interface exists so [RefCountedMulticastLock] can live in `:core`, which
 * is a plain Kotlin/JVM module and cannot name `WifiManager`. Protocol modules
 * never see this type — they only ever touch [MulticastLockHandle], which
 * `ReceiverEnvironment` hands them.
 */
interface PlatformMulticastLock {

    /** Acquires the platform lock. Callers must have already decided to hold it. */
    fun acquire()

    /** Releases the platform lock. */
    fun release()
}

/**
 * Reference-counted [MulticastLockHandle].
 *
 * WHY NOW: `AndroidManifest.xml` has declared `CHANGE_WIFI_MULTICAST_STATE` since
 * the fork, and nothing has ever called `createMulticastLock`. On many Android
 * devices a plain `NsdManager.registerService` or an SSDP socket then receives
 * **no inbound multicast at all** — the TV registers successfully, no error is
 * logged, and it simply never appears in the sender's AirPlay menu. It is close
 * to the worst kind of bug: silent, platform-dependent, and impossible to
 * attribute without already knowing the cause.
 *
 * WHY COUNTED: both discovery mechanisms need the lock, and they do not run for
 * the same lifetime. AirPlay's mDNS advertising starts when the receiver starts;
 * its DACP discovery client starts when a session is established. If the first
 * holder to finish released the lock outright, the other would go deaf
 * mid-session. The lock is therefore released only when the *last* holder lets
 * go.
 *
 * Counting is **idempotent per owner tag**: acquiring twice under the same tag
 * counts once, so a receiver that restarts without a matching release cannot
 * inflate the count and leak the lock for the life of the process.
 */
class RefCountedMulticastLock(
    private val platform: PlatformMulticastLock,
) : MulticastLockHandle {

    /**
     * Owners currently holding a reference.
     *
     * A set, not a count, because the contract is idempotent per owner tag —
     * see [acquire]. Counting per call would make a receiver that restarts without
     * a matching release accumulate references it will never give back.
     */
    private val holders = LinkedHashSet<String>()

    /** Guards [holders] and the platform calls, so acquire/release can race. */
    private val guard = Any()

    override fun acquire(owner: String) {
        synchronized(guard) {
            if (!holders.add(owner)) {
                // Already held under this tag. Deliberately not counted twice.
                return
            }
            if (holders.size == 1) {
                // First holder overall — take the platform lock.
                platform.acquire()
            }
        }
    }

    override fun release(owner: String) {
        synchronized(guard) {
            if (!holders.remove(owner)) return
            if (holders.isEmpty()) {
                platform.release()
            }
        }
    }

    override val isHeld: Boolean
        get() = synchronized(guard) { holders.isNotEmpty() }

    /** Owners currently holding the lock. Diagnostics only. */
    val owners: Set<String>
        get() = synchronized(guard) { holders.toSet() }
}
