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

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * One local address plus the interface facts the selection policy needs.
 *
 * Pure data, deliberately: it lets [DefaultInterfaceSelector.rank] be tested
 * exhaustively against synthetic interface layouts, which is the only way to cover
 * the cases that matter — a TV with Ethernet and Wi-Fi both up, a device with a
 * VPN, a device whose only IPv6 address is link-local — without shipping that
 * hardware.
 */
data class LocalAddress(
    val address: InetAddress,
    val interfaceName: String,
    val isUp: Boolean = true,
    val isLoopback: Boolean = false,
    val isVirtual: Boolean = false,
    val isPointToPoint: Boolean = false,
) {
    /**
     * Whether a sender on the same LAN could plausibly reach this address.
     *
     * The exclusions are all things upstream did not check, and each one has a
     * known failure: `isVirtual` catches the container/docker interfaces Android
     * exposes, `isPointToPoint` catches VPN tunnels, and down interfaces obviously
     * cannot be reached.
     */
    val isReachable: Boolean
        get() = isUp && !isLoopback && !isVirtual && !isPointToPoint
}

/** What kind of link an interface is, as far as preference goes. Lower is better. */
enum class LinkKind { ETHERNET, WIFI, OTHER }

/**
 * Picks the address the receiver advertises.
 *
 * WHY AN IMPLEMENTATION IS NEEDED AT ALL: upstream took the first non-loopback
 * interface. On a TV with both Ethernet and Wi-Fi up that is whichever the kernel
 * enumerated first; on a device with a tunnel it can be the tunnel. Either way the
 * receiver advertises an address the sender cannot reach and simply never appears
 * in the picker — with no error, because mDNS registration itself succeeded.
 */
class DefaultInterfaceSelector(
    private val source: () -> List<LocalAddress> = { SystemLocalAddresses.read() },
) : InterfaceSelector {

    override fun selectAddress(): InetAddress? = rank(source()).firstOrNull()?.address

    override fun allAddresses(): List<InetAddress> = rank(source()).map { it.address }

    companion object {

        /**
         * Drops everything a sender could not reach. Order is preserved.
         */
        fun usable(all: List<LocalAddress>): List<LocalAddress> = all.filter { it.isReachable }

        /**
         * [usable], best first.
         *
         * The preference order is the one documented on [InterfaceSelector], and
         * each step is a real-world decision rather than a taste:
         *
         * 1. **Ethernet before Wi-Fi.** A TV with a cable is not going to roam, and
         *    its address does not change when the Wi-Fi radio powers down.
         * 2. **Site-local before global.** A sender on the LAN reaches a
         *    192.168/10.x address directly. Advertising a public address instead
         *    invites a NAT hairpin, which most home routers do not support.
         * 3. **IPv4 before IPv6.** A large installed base of senders still fails to
         *    resolve or route `.local` over IPv6. Advertising only IPv6 makes the
         *    receiver invisible to them, which is a worse failure than the reverse.
         */
        fun rank(all: List<LocalAddress>): List<LocalAddress> =
            usable(all).sortedWith(
                compareBy(
                    { linkKind(it.interfaceName).ordinal },
                    { scopeRank(it.address) },
                    { if (it.address is Inet4Address) 0 else 1 },
                    { it.interfaceName },
                ),
            )

        /** Classifies by interface name, the only portable signal available. */
        fun linkKind(interfaceName: String): LinkKind {
            val name = interfaceName.lowercase()
            return when {
                // eth0 (Android/Linux), enp3s0/en0 (predictable names), rndis0/usb0 (USB tethering)
                ETHERNET_PREFIXES.any { name.startsWith(it) } -> LinkKind.ETHERNET
                // wlan0 (Android/Linux), wl0 (predictable name), ap0 (soft AP)
                WIFI_PREFIXES.any { name.startsWith(it) } -> LinkKind.WIFI
                else -> LinkKind.OTHER
            }
        }

        /**
         * Site-local beats global beats link-local. Lower is better.
         *
         * Link-local ranks worst on purpose: `169.254.x.x` and `fe80::` are
         * unusable for advertisement, because a sender has no route to them until
         * it has joined the same link and resolved the interface scope.
         */
        private fun scopeRank(address: InetAddress): Int = when {
            address.isSiteLocalAddress -> 0
            address.isLinkLocalAddress -> 2
            else -> 1
        }

        private val ETHERNET_PREFIXES = listOf("eth", "en", "rndis", "usb")
        private val WIFI_PREFIXES = listOf("wlan", "wl", "ap")
    }
}

/**
 * Reads the platform's real interfaces into [LocalAddress]es.
 *
 * Kept separate from the ranking so the policy above has no dependency on the
 * machine it runs on. `java.net.NetworkInterface` exists on Android too, so this
 * one implementation serves both, and a future Android build can substitute a
 * `ConnectivityManager`-aware source without touching the policy.
 */
object SystemLocalAddresses {

    /** Never throws: an interface that vanishes mid-enumeration is skipped. */
    fun read(): List<LocalAddress> = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.flatMap { iface -> addressesOf(iface) }
            ?.toList()
            .orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun addressesOf(iface: NetworkInterface): Sequence<LocalAddress> {
        val facts = try {
            InterfaceFacts(
                name = iface.name.orEmpty(),
                isUp = iface.isUp,
                isLoopback = iface.isLoopback,
                isVirtual = iface.isVirtual,
                isPointToPoint = iface.isPointToPoint,
            )
        } catch (_: Exception) {
            return emptySequence()
        }

        return try {
            iface.inetAddresses.asSequence().map { address ->
                LocalAddress(
                    address = address,
                    interfaceName = facts.name,
                    isUp = facts.isUp,
                    isLoopback = facts.isLoopback,
                    isVirtual = facts.isVirtual,
                    isPointToPoint = facts.isPointToPoint,
                )
            }
        } catch (_: Exception) {
            emptySequence()
        }
    }

    private data class InterfaceFacts(
        val name: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isVirtual: Boolean,
        val isPointToPoint: Boolean,
    )
}
