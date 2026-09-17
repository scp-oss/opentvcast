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
import kotlinx.coroutines.CoroutineScope
import tv.opentvcast.core.net.DefaultInterfaceSelector
import tv.opentvcast.core.net.DefaultReceiverEnvironment
import tv.opentvcast.core.net.InterfaceSelector
import tv.opentvcast.core.net.NetworkSnapshot
import tv.opentvcast.core.net.ReceiverEnvironment
import tv.opentvcast.util.Logger

/**
 * Builds the production [DefaultReceiverEnvironment] for an Android device.
 *
 * This is the composition point where the pure-JVM contract implementations in
 * `:core` meet their Android counterparts:
 *
 * | Contract            | :core implementation        | Android counterpart here        |
 * |---------------------|-----------------------------|---------------------------------|
 * | MulticastLockHandle | `RefCountedMulticastLock`   | [AndroidMulticastLock]          |
 * | InterfaceSelector   | [DefaultInterfaceSelector]  | same (reads `NetworkInterface`) |
 * | NetworkMonitor      | `CoalescingNetworkMonitor`  | [AndroidNetworkMonitor]         |
 * | PortAllocator       | `ServerSocketPortAllocator` | same (real socket binds)        |
 *
 * WHY A FACTORY RATHER THAN A SUBCLASS: [DefaultReceiverEnvironment] already
 * implements every `ReceiverEnvironment` member; Android contributes only *which*
 * implementations are plugged in. A subclass would add inheritance for what is
 * really configuration, and configuration is what the existing constructor
 * parameters are for.
 *
 * ### Network-change behaviour
 *
 * On a settled network change the monitor calls [DefaultReceiverEnvironment.refreshNetwork],
 * so `localAddress`/`allAddresses` observe the new identity. What does *not* happen
 * yet is protocol re-advertising — restarting mDNS/SSDP under the new address —
 * because the protocol layer does not consume the environment until the
 * `ProtocolReceiver` integration (DLNA, Phase 7). The wiring point is kept here,
 * in one visible place, so that step is a one-line addition rather than a
 * rediscovery.
 */
class AndroidReceiverEnvironment private constructor(
    private val env: DefaultReceiverEnvironment,
    private val monitor: AndroidNetworkMonitor,
) : ReceiverEnvironment by env, AutoCloseable {

    /**
     * Stops watching and unregisters the `NetworkCallback`. Idempotent. Call from
     * the service's `onDestroy` — the monitor holds a system-registered callback,
     * and leaking it keeps the whole process alive after the service is gone.
     */
    override fun close() {
        monitor.stop()
    }

    companion object {

        /**
         * @param context     any application context; only used for system services.
         * @param scope       structured-concurrency scope, typically the foreground service's.
         * @param displayName already-resolved name shown in sender pickers.
         * @param addressSource the interface policy; defaults to the stock ranking, which
         *        prefers Ethernet over Wi-Fi, site-local over global, IPv4 over IPv6.
         * @param settleMs    how long an identity change must hold before it is reported.
         */
        fun create(
            context: Context,
            scope: CoroutineScope,
            displayName: String,
            addressSource: InterfaceSelector = DefaultInterfaceSelector(),
            settleMs: Long = AndroidNetworkMonitor.DEFAULT_SETTLE_MS,
        ): AndroidReceiverEnvironment {
            val env = DefaultReceiverEnvironment(
                scope = scope,
                displayName = displayName,
                platformLock = AndroidMulticastLock(context),
                addressSource = addressSource,
            )

            val monitor = AndroidNetworkMonitor.create(
                context = context,
                scope = scope,
                snapshot = {
                    NetworkSnapshot(
                        primaryAddress = env.localAddress.value?.hostAddress,
                        addresses = env.allAddresses.value.mapNotNull { it.hostAddress }.toSet(),
                    )
                },
                settleMs = settleMs,
            )
            monitor.start {
                env.refreshNetwork()
                Logger.i(
                    "Network identity changed — now advertising ${env.localAddress.value?.hostAddress}"
                )
                // TODO(Phase 7): tell registered protocols to re-advertise under the
                // new identity (restart mDNS/SSDP). Protocols do not consume the
                // environment yet; when they do, that hook belongs right here, after
                // refreshNetwork() has published the new addresses.
            }
            return AndroidReceiverEnvironment(env, monitor)
        }
    }
}
