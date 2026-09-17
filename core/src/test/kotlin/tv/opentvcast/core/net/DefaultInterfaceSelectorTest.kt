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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Tests for [DefaultInterfaceSelector]'s ranking policy.
 *
 * Every case here is a device layout that has actually been a bug report somewhere:
 * a TV with Ethernet and Wi-Fi both up, a phone with a VPN, an Android device with
 * a container interface, an IPv6-only link with no IPv4 address.
 *
 * Testing this through synthetic [LocalAddress] lists rather than the host machine
 * is the only way it can be exhaustive — the address this test process happens to
 * have says nothing about the addresses a TV will have, and a test that depended on
 * the host would pass on a developer laptop and tell you nothing about a Fire TV
 * Stick.
 *
 * The failure being guarded against is silent: an unreachable advertised address
 * still registers successfully in mDNS, so the receiver simply never appears in the
 * sender's picker and logs nothing.
 *
 * Addresses are compared as [InetAddress] rather than as strings, because
 * `hostAddress` normalises `2001:db8::1` to `2001:db8:0:0:0:0:0:1` and a string
 * comparison would be asserting the JDK's formatting rather than the selection.
 */
class DefaultInterfaceSelectorTest {

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun candidate(
        interfaceName: String,
        address: String,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
        isVirtual: Boolean = false,
        isPointToPoint: Boolean = false,
    ) = LocalAddress(
        address = ip(address),
        interfaceName = interfaceName,
        isUp = isUp,
        isLoopback = isLoopback,
        isVirtual = isVirtual,
        isPointToPoint = isPointToPoint,
    )

    /** The addresses from [list], in the order the selector would report them. */
    private fun order(list: List<LocalAddress>): List<InetAddress> =
        DefaultInterfaceSelector.rank(list).map { it.address }

    private fun selectorOver(list: List<LocalAddress>) =
        DefaultInterfaceSelector(source = { list })

    // ─── link classification ─────────────────────────────────────────────────

    @Test
    fun `Ethernet interface names are recognised`() {
        // eth0 is Android/Linux; enp3s0/en0 are systemd predictable names; rndis0 and
        // usb0 are USB tethering, which is still a wired link for our purposes.
        for (name in listOf("eth0", "eth1", "enp3s0", "en0", "rndis0", "usb0")) {
            assertEquals(name, LinkKind.ETHERNET, DefaultInterfaceSelector.linkKind(name))
        }
    }

    @Test
    fun `Wi-Fi interface names are recognised`() {
        for (name in listOf("wlan0", "wlan1", "wl0", "ap0")) {
            assertEquals(name, LinkKind.WIFI, DefaultInterfaceSelector.linkKind(name))
        }
    }

    @Test
    fun `everything else is OTHER rather than being guessed at`() {
        // p2p0 is Wi-Fi Direct: not reachable from the LAN unless P2P is actually in
        // use, so ranking it lowest is the safe default.
        for (name in listOf("p2p0", "lo", "dummy0", "docker0", "tun0")) {
            assertEquals(name, LinkKind.OTHER, DefaultInterfaceSelector.linkKind(name))
        }
    }

    @Test
    fun `classification is case insensitive`() {
        assertEquals(LinkKind.ETHERNET, DefaultInterfaceSelector.linkKind("ETH0"))
        assertEquals(LinkKind.WIFI, DefaultInterfaceSelector.linkKind("WLAN0"))
    }

    // ─── exclusion ───────────────────────────────────────────────────────────

    @Test
    fun `loopback is excluded`() {
        val list = listOf(
            candidate("lo", "127.0.0.1", isLoopback = true),
            candidate("lo", "::1", isLoopback = true),
        )

        assertEquals(emptyList<InetAddress>(), order(list))
        assertNull(selectorOver(list).selectAddress())
    }

    @Test
    fun `a down interface is excluded`() {
        val list = listOf(candidate("eth0", "192.168.1.10", isUp = false))

        assertEquals(emptyList<InetAddress>(), order(list))
    }

    @Test
    fun `virtual interfaces are excluded`() {
        // Android exposes container interfaces; upstream's "first non-loopback"
        // rule could select one, producing an address no sender can reach.
        val list = listOf(candidate("docker0", "172.17.0.1", isVirtual = true))

        assertEquals(emptyList<InetAddress>(), order(list))
    }

    @Test
    fun `point-to-point interfaces are excluded`() {
        // A VPN tunnel address is reachable only from inside the tunnel.
        val list = listOf(candidate("tun0", "10.8.0.2", isPointToPoint = true))

        assertEquals(emptyList<InetAddress>(), order(list))
    }

    @Test
    fun `an empty interface list yields no address`() {
        assertEquals(emptyList<InetAddress>(), order(emptyList()))
        assertNull(selectorOver(emptyList()).selectAddress())
    }

    // ─── the upstream bug ────────────────────────────────────────────────────

    @Test
    fun `picks the reachable Wi-Fi address over a container interface enumerated first`() {
        // Exactly the upstream failure: iteration order put a virtual interface
        // first and "first non-loopback" selected it.
        val list = listOf(
            candidate("docker0", "172.17.0.1", isVirtual = true),
            candidate("wlan0", "192.168.1.20"),
        )

        assertEquals(listOf(ip("192.168.1.20")), order(list))
    }

    @Test
    fun `picks the LAN address over a VPN tunnel listed first`() {
        val list = listOf(
            candidate("tun0", "10.8.0.2", isPointToPoint = true),
            candidate("wlan0", "192.168.1.20"),
        )

        assertEquals(listOf(ip("192.168.1.20")), order(list))
    }

    // ─── the documented preference order ─────────────────────────────────────

    @Test
    fun `Ethernet beats Wi-Fi`() {
        // A wired TV does not roam, and its address survives the Wi-Fi radio
        // powering down.
        val list = listOf(
            candidate("wlan0", "192.168.1.20"),
            candidate("eth0", "192.168.1.10"),
        )

        assertEquals(listOf(ip("192.168.1.10"), ip("192.168.1.20")), order(list))
        assertEquals(ip("192.168.1.10"), selectorOver(list).selectAddress())
    }

    @Test
    fun `site-local beats global`() {
        // A sender on the LAN reaches 192.168.x directly. Advertising a public
        // address invites a NAT hairpin, which most home routers refuse.
        val list = listOf(
            candidate("wlan0", "8.8.8.8"),
            candidate("wlan0", "192.168.1.20"),
        )

        assertEquals(listOf(ip("192.168.1.20"), ip("8.8.8.8")), order(list))
    }

    @Test
    fun `IPv4 beats IPv6 at equal scope`() {
        // Too many senders still fail to route .local over IPv6; advertising only
        // IPv6 makes the receiver invisible to them.
        val list = listOf(
            candidate("wlan0", "2001:db8::1"),
            candidate("wlan0", "192.168.1.20"),
        )

        assertEquals(listOf(ip("192.168.1.20"), ip("2001:db8::1")), order(list))
    }

    @Test
    fun `link-local ranks below global`() {
        // 169.254.x.x and fe80:: are unusable for advertisement: a sender has no
        // route to them until it has joined the same link and resolved the scope.
        val list = listOf(
            candidate("wlan0", "fe80::1"),
            candidate("wlan0", "2001:db8::1"),
            candidate("wlan0", "192.168.1.20"),
        )

        assertEquals(
            listOf(ip("192.168.1.20"), ip("2001:db8::1"), ip("fe80::1")),
            order(list),
        )
    }

    @Test
    fun `the criteria apply in order, not as a sum`() {
        // Ethernet site-local IPv4 wins on the first criterion even though a Wi-Fi
        // global IPv6 address exists.
        val list = listOf(
            candidate("wlan0", "2001:db8::1"),
            candidate("eth0", "192.168.1.10"),
        )

        assertEquals(listOf(ip("192.168.1.10"), ip("2001:db8::1")), order(list))
    }

    // ─── the realistic TV layout ─────────────────────────────────────────────

    @Test
    fun `ranks a realistic TV interface layout correctly`() {
        val list = listOf(
            candidate("lo", "127.0.0.1", isLoopback = true),
            candidate("docker0", "172.17.0.1", isVirtual = true),
            candidate("tun0", "10.8.0.2", isPointToPoint = true),
            candidate("p2p0", "192.168.49.1"),
            candidate("wlan0", "fe80::a00:27ff:fe4e:66a1"),
            candidate("wlan0", "2001:db8::5"),
            candidate("wlan0", "192.168.1.20"),
            candidate("eth0", "192.168.1.10"),
        )

        assertEquals(
            // Ethernet site-local IPv4 first; then Wi-Fi by scope then family;
            // p2p0 last of the usable ones.
            listOf(
                ip("192.168.1.10"),
                ip("192.168.1.20"),
                ip("2001:db8::5"),
                ip("fe80::a00:27ff:fe4e:66a1"),
                ip("192.168.49.1"),
            ),
            order(list),
        )
    }

    @Test
    fun `allAddresses returns every usable address for dual-stack advertisement`() {
        val list = listOf(
            candidate("eth0", "192.168.1.10"),
            candidate("eth0", "2001:db8::1"),
            candidate("lo", "127.0.0.1", isLoopback = true),
            candidate("docker0", "172.17.0.1", isVirtual = true),
        )

        val addresses = selectorOver(list).allAddresses()

        assertEquals(
            "an IPv6-only sender needs the IPv6 address advertised too",
            listOf(ip("192.168.1.10"), ip("2001:db8::1")),
            addresses,
        )
    }

    @Test
    fun `usable preserves excludes without reordering`() {
        val list = listOf(
            candidate("wlan0", "192.168.1.20"),
            candidate("lo", "127.0.0.1", isLoopback = true),
            candidate("eth0", "192.168.1.10"),
        )

        assertEquals(
            "usable() filters only; ranking is rank()'s job",
            listOf("wlan0", "eth0"),
            DefaultInterfaceSelector.usable(list).map { it.interfaceName },
        )
    }

    @Test
    fun `the source is consulted on every call, not cached`() {
        // An address change must be picked up without rebuilding the selector.
        var list = listOf(candidate("eth0", "192.168.1.10"))
        val selector = DefaultInterfaceSelector(source = { list })

        assertEquals(ip("192.168.1.10"), selector.selectAddress())

        list = listOf(candidate("eth0", "192.168.1.99"))

        assertEquals(ip("192.168.1.99"), selector.selectAddress())
    }

    // ─── mechanism: the real system read ─────────────────────────────────────

    @Test
    fun `reading the real system interfaces does not throw`() {
        // No assertion on content — this box may be offline, may be in a container,
        // and may have no non-loopback interface at all. What must hold is that the
        // enumeration is total and returns only well-formed entries.
        val read = SystemLocalAddresses.read()

        assertTrue("read() must be total", read.size >= 0)
        for (entry in read) {
            assertTrue("interface name must not be blank", entry.interfaceName.isNotBlank())
            assertTrue("address must be non-null", entry.address.hostAddress != null)
        }
    }

    @Test
    fun `the default selector on the real system is total`() {
        // Either an address or null, never an exception.
        val selector = DefaultInterfaceSelector()
        val selected = selector.selectAddress()

        assertTrue(
            "selectAddress must return null rather than throwing when nothing is usable",
            selected == null || selected.hostAddress != null,
        )
        for (address in selector.allAddresses()) {
            assertTrue(address.hostAddress != null)
        }
    }
}
