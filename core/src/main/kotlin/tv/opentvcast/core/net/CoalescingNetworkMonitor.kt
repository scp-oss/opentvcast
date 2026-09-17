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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The device's network identity, reduced to what re-advertising depends on.
 *
 * Addresses are held as strings so equality is exact and cheap; [primaryAddress]
 * is the one the receiver would advertise right now.
 */
data class NetworkSnapshot(
    val primaryAddress: String?,
    val addresses: Set<String>,
)

/**
 * Watches the device's network identity and reports changes, coalesced.
 *
 * WHY THIS IS NEEDED: without it, an IP change — a DHCP lease renewal, a router
 * reboot, a Wi-Fi to Ethernet switch, or simply the radio waking up — leaves mDNS
 * advertising the old address and SSDP publishing a dead `LOCATION`. The sender
 * still *sees* the device, because the mDNS record lives on in its cache, so every
 * connection attempt fails with a timeout and only an app restart clears it. That
 * combination is the worst kind to diagnose: the user reports "it's there but it
 * won't connect".
 *
 * ### Coalescing
 *
 * A change is reported only after the new identity has been observed continuously
 * for [settleMs]. This matters more than it sounds: bringing up an interface
 * produces a burst of intermediate states (address assigned, then carrier up, then
 * routes installed, then an IPv6 address arriving seconds later). Reporting each
 * one would have the receiver tear down and re-advertise four times, and an
 * interface that flaps up and back produces *no* report at all, because the
 * snapshot returns to what was last emitted.
 *
 * ### Why polling rather than a platform callback
 *
 * Android's `ConnectivityManager.NetworkCallback` would be more efficient, and
 * when an Android adapter lands it can drive this class by calling a package-private
 * "check now" — the coalescing policy above is the part worth sharing, and the
 * trigger is the part worth specialising. Polling is used here because it is
 * correct everywhere, including on TVs whose Wi-Fi driver does not deliver
 * `NET_CAPABILITY` callbacks, and because it needs no Android types, which is what
 * keeps this class in `:core` where it can be tested under virtual time.
 *
 * ### Timing
 *
 * Elapsed time is accumulated from the poll interval rather than read from a
 * clock. A monotonic clock reading would be real time even under a virtual-time
 * test dispatcher, which would make every test either slow or flaky; an
 * accumulator is exact in both.
 */
class CoalescingNetworkMonitor(
    private val scope: CoroutineScope,
    private val snapshot: () -> NetworkSnapshot,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val settleMs: Long = DEFAULT_SETTLE_MS,
) : NetworkMonitor {

    private var job: Job? = null

    /**
     * Starts watching. Any previous watch is stopped first, so calling this twice
     * does not produce two pollers racing to report the same change.
     *
     * The snapshot taken at start is the baseline and is **not** reported: starting
     * a receiver is not a network change, and treating it as one would make every
     * launch re-advertise the address it had just advertised.
     *
     * @param onChanged invoked when the identity has changed and settled. An
     *        exception it throws is contained so that one failed re-advertise does
     *        not silently end all future network monitoring — the callback is
     *        responsible for logging its own failures.
     */
    override fun start(onChanged: suspend () -> Unit) {
        stop()
        job = scope.launch {
            var lastEmitted = snapshot()
            var candidate: NetworkSnapshot? = null
            var candidateSeenForMs = 0L

            while (isActive) {
                delay(pollIntervalMs)
                val current = snapshot()

                when {
                    // Back to what was last advertised: an interface flapped and
                    // recovered. Nothing to report, and deliberately not counted as
                    // a change.
                    current == lastEmitted -> {
                        candidate = null
                        candidateSeenForMs = 0
                    }

                    // A different candidate than last time: restart the settle timer.
                    current != candidate -> {
                        candidate = current
                        candidateSeenForMs = pollIntervalMs
                    }

                    else -> {
                        candidateSeenForMs += pollIntervalMs
                        if (candidateSeenForMs >= settleMs) {
                            runCatching { onChanged() }
                            lastEmitted = current
                            candidate = null
                            candidateSeenForMs = 0
                        }
                    }
                }
            }
        }
    }

    /** Idempotent. */
    override fun stop() {
        job?.cancel()
        job = null
    }

    /** Whether a watch is currently running. Diagnostics and tests. */
    val isRunning: Boolean
        get() = job?.isActive == true

    companion object {
        /** Fast enough to re-advertise before a sender's mDNS cache is consulted. */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 1_000L

        /**
         * Long enough to absorb an interface coming up (addresses, carrier, routes),
         * short enough that a re-advertise still beats a user retrying manually.
         */
        const val DEFAULT_SETTLE_MS: Long = 2_000L
    }
}
