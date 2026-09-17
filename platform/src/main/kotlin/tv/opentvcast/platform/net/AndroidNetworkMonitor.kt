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

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.opentvcast.core.net.NetworkMonitor
import tv.opentvcast.core.net.NetworkSnapshot
import tv.opentvcast.util.Logger

/**
 * Entry point for platform network signals, injected so the coalescing logic in
 * [AndroidNetworkMonitor] is testable without a real [ConnectivityManager].
 *
 * Android's `NetworkCallback` delivers several distinct events — available, lost,
 * `LinkProperties` changed, capabilities changed — but for a receiver they all
 * answer one question: "might the identity I advertised be stale?" One method is
 * therefore the right granularity; the monitor decides what to do with the answer.
 */
fun interface NetworkSignals {

    /** Called whenever the platform suggests the network identity may have changed. */
    fun onSignal()
}

/**
 * [NetworkMonitor] driven by Android's `ConnectivityManager.NetworkCallback`
 * instead of the poll loop in [tv.opentvcast.core.net.CoalescingNetworkMonitor].
 *
 * WHY AN EVENT-DRIVEN TWIN: the coalescing policy (report a change only after it
 * has held for a settle window; swallow flaps back to the last advertised identity)
 * is the part worth keeping, and it lives in `:core` where virtual-time tests can
 * pin it. But a pure poller wastes a wake-up every second on a device that sits on
 * one network for days, and on TVs whose Wi-Fi drivers under-report `NET_CAPABILITY`
 * transitions the callback alone cannot be trusted either. This class takes the
 * best of both: the platform's callbacks provide the trigger, the same settle
 * policy provides the discipline.
 *
 * ### Callback threading
 *
 * `NetworkCallback` fires on a system connectivity thread. State is guarded by a
 * lock; the settle timer runs in [scope], and a newer signal cancels any timer
 * still pending for an older candidate — so bursts of intermediate states
 * (address, then carrier, then routes) collapse into exactly one report.
 */
class AndroidNetworkMonitor(
    private val scope: CoroutineScope,
    private val snapshot: () -> NetworkSnapshot,
    /** Platform registration; defaults to nothing (a monitor without signals never reports). */
    private val signalsFactory: ((NetworkSignals) -> AutoCloseable)? = null,
    private val settleMs: Long = DEFAULT_SETTLE_MS,
) : NetworkMonitor {

    private val lock = Any()

    private var registration: AutoCloseable? = null
    private var signals: NetworkSignals? = null
    private var settleJob: Job? = null

    /** Identity the last report advertised; the start snapshot is the baseline. */
    private var lastEmitted: NetworkSnapshot? = null

    /** Identity currently waiting out its settle window, if any. */
    private var candidate: NetworkSnapshot? = null

    /** Whether a platform registration is currently in place. Tests and diagnostics. */
    val isRegistered: Boolean
        get() = synchronized(lock) { registration != null }

    override fun start(onChanged: suspend () -> Unit) {
        stop()
        synchronized(lock) {
            lastEmitted = snapshot()
            candidate = null
        }

        val factory = signalsFactory ?: return
        val handler = NetworkSignals {
            val s = synchronized(lock) { signals }
            s?.let { onSignal(it, onChanged) }
        }
        synchronized(lock) {
            signals = handler
            registration = try {
                factory(handler)
            } catch (e: Exception) {
                // A device that refuses registration degrades to the pre-existing
                // behaviour (no re-advertise on change) rather than a crashed service.
                Logger.w("NetworkCallback registration failed — network monitoring disabled", e)
                signals = null
                null
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            settleJob?.cancel()
            settleJob = null
            candidate = null
            runCatching { registration?.close() }
                .onFailure { Logger.w("NetworkCallback unregister failed (non-fatal)", it) }
            registration = null
            signals = null
            lastEmitted = null
        }
    }

    /**
     * A platform signal arrived. Runs synchronously on the callback thread; all
     * decisions are made under [lock] so two rapid callbacks serialise cleanly.
     */
    private fun onSignal(signals: NetworkSignals, onChanged: suspend () -> Unit) {
        val current = snapshot()
        synchronized(lock) {
            if (current == lastEmitted) {
                // Flap recovered (or noise): withdraw any pending report.
                settleJob?.cancel()
                settleJob = null
                candidate = null
                return
            }

            if (current == candidate) {
                // Same candidate still holding; its timer keeps running.
                return
            }

            // A new candidate: restart the settle window from zero.
            settleJob?.cancel()
            candidate = current
            settleJob = scope.launch {
                delay(settleMs)
                reportIfStill(signals, current, onChanged)
            }
        }
    }

    /**
     * The settle window elapsed. Re-reads the snapshot: the identity must still be
     * the candidate's — a change during the timer is handled by that timer's own
     * restart, but re-checking keeps a race between cancel and launch harmless.
     */
    private suspend fun reportIfStill(
        signals: NetworkSignals,
        expected: NetworkSnapshot,
        onChanged: suspend () -> Unit,
    ) {
        synchronized(lock) {
            if (signals != this.signals) return  // stopped/restarted while we waited
            val still = snapshot()
            if (still != expected) return
            lastEmitted = expected
            candidate = null
            settleJob = null
        }
        runCatching { onChanged() }
            .onFailure { Logger.e("Network-change re-advertise failed (contained)", it) }
    }

    companion object {
        /**
         * Long enough to absorb an interface coming up (addresses, carrier, routes),
         * short enough that a re-advertise still beats a user retrying manually.
         */
        const val DEFAULT_SETTLE_MS: Long = 2_000L

        /**
         * Builds the production monitor for [context].
         *
         * The request asks for any network with internet capability — Wi-Fi,
         * Ethernet, or cellular — because a receiver is reachable over any of them.
         * Every callback variant that can alter addressing funnels into one signal.
         *
         * @param snapshot supplies the current identity; on Android this is derived
         *        from the [tv.opentvcast.core.net.InterfaceSelector] (B4 wiring).
         */
        fun create(
            context: Context,
            scope: CoroutineScope,
            snapshot: () -> NetworkSnapshot,
            settleMs: Long = DEFAULT_SETTLE_MS,
        ): AndroidNetworkMonitor {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val factory: (NetworkSignals) -> AutoCloseable = { handler ->
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = handler.onSignal()
                    override fun onLost(network: Network) = handler.onSignal()
                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: android.net.LinkProperties,
                    ) = handler.onSignal()

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        // Delivered frequently (signal strength churn) but cheap: the
                        // snapshot comparison absorbs everything that is not a change.
                        handler.onSignal()
                    }
                }
                cm.registerNetworkCallback(request, callback)
                AutoCloseable { cm.unregisterNetworkCallback(callback) }
            }
            return AndroidNetworkMonitor(scope, snapshot, signalsFactory = factory, settleMs = settleMs)
        }
    }
}
