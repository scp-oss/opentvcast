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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.opentvcast.core.protocol.CastEventBus
import tv.opentvcast.core.protocol.RegistryEventBus
import tv.opentvcast.core.protocol.ReceiverRegistry
import tv.opentvcast.core.surface.SimpleSurfaceSink
import tv.opentvcast.core.surface.SurfaceSink
import java.net.InetAddress

/**
 * The production composition of [ReceiverEnvironment] over the four network
 * contracts. Pure Kotlin/JVM: the only Android-specific piece it needs is a
 * [PlatformMulticastLock], which the app supplies — that single injection is
 * what keeps this class testable on a desktop JVM.
 *
 * Composition notes:
 *
 * - Port requests are reserved under the `"receiver"` owner tag; a dump of
 *   [PortAllocator.snapshot] therefore shows everything this process holds.
 * - [localAddress]/[allAddresses] are snapshots, not live views: the selector
 *   is consulted at construction and on [refreshNetwork], which the network
 *   monitor's change callback is expected to invoke. A receiver that never
 *   calls it simply advertises the address it started with.
 * - [eventBus] defaults to a [RegistryEventBus] over [registry], so protocols
 *   and the service share one event stream by construction.
 */
class DefaultReceiverEnvironment(
    override val scope: CoroutineScope,
    override val displayName: String,
    platformLock: PlatformMulticastLock,
    private val addressSource: InterfaceSelector,
    private val allocator: PortAllocator = ServerSocketPortAllocator(),
    override val surfaceSink: SurfaceSink = SimpleSurfaceSink(),
    eventBus: CastEventBus? = null,
    val registry: ReceiverRegistry = ReceiverRegistry(),
) : ReceiverEnvironment {

    override val multicastLock: MulticastLockHandle = RefCountedMulticastLock(platformLock)

    private val eventBusOverride: CastEventBus? = eventBus

    override val eventBus: CastEventBus =
        eventBusOverride ?: RegistryEventBus(registry)

    private val _localAddress = MutableStateFlow(addressSource.selectAddress())
    override val localAddress: StateFlow<InetAddress?> = _localAddress.asStateFlow()

    private val _allAddresses = MutableStateFlow(addressSource.allAddresses())
    override val allAddresses: StateFlow<List<InetAddress>> = _allAddresses.asStateFlow()

    override fun allocatePort(preferred: Int?): Int = allocator.allocate(preferred, owner = OWNER)

    override fun releasePort(port: Int) = allocator.release(port, owner = OWNER)

    /**
     * Re-reads the address source and publishes the new values. Called by the
     * network monitor's change callback before receivers are told to
     * re-advertise, so they observe the *new* identity, not the old one.
     */
    fun refreshNetwork() {
        _localAddress.value = addressSource.selectAddress()
        _allAddresses.value = addressSource.allAddresses()
    }

    private companion object {
        const val OWNER = "receiver"
    }
}
