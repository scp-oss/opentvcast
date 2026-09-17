/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.dlna.ssdp

/**
 * Something we advertise over SSDP.
 *
 * `nt` is the notification type a control point searches for; `usn` is derived
 * from it plus our UDN so each target has a unique identifier. Sharing one USN
 * across targets — a mistake this list exists to prevent — makes some control
 * points collapse the renderer into a single entry with the wrong service.
 */
data class SsdpTarget(val nt: String) {

    /** `uuid:<udn>::<nt>` — the unique service name for this target. */
    fun usn(udn: String): String = "uuid:$udn::$nt"

    companion object {
        /** Present in every UPnP device; what `upnp:rootdevice` searches find. */
        val ROOT_DEVICE = SsdpTarget("upnp:rootdevice")

        val MEDIA_RENDERER = SsdpTarget("urn:schemas-upnp-org:device:MediaRenderer:1")

        val AV_TRANSPORT = SsdpTarget("urn:schemas-upnp-org:service:AVTransport:1")

        val RENDERING_CONTROL = SsdpTarget("urn:schemas-upnp-org:service:RenderingControl:1")

        val CONNECTION_MANAGER = SsdpTarget("urn:schemas-upnp-org:service:ConnectionManager:1")

        /** Everything we advertise, in the order we announce it. */
        val ALL = listOf(ROOT_DEVICE, MEDIA_RENDERER, AV_TRANSPORT, RENDERING_CONTROL, CONNECTION_MANAGER)
    }
}

/**
 * Decides which advertised targets answer a given `M-SEARCH`, and how long to
 * wait before answering.
 *
 * Two rules matter:
 *
 * 1. **`MAN` must be `"ssdp:discover"`.** Without that check we would answer
 *    arbitrary UDP traffic that merely looks like HTTP.
 * 2. **Answers are spread across the sender's `MX` window.** Every renderer on
 *    the network answers the same multicast search at the same instant; the
 *    spec's `MX` header exists so replies arrive spread out instead of
 *    colliding. `MX` is clamped because senders have been seen advertising
 *    values like 120, and waiting two minutes to be discovered is worse than
 *    answering immediately.
 */
object SsdpSearchMatcher {

    /** Upper bound on the reply delay, however large the sender's `MX` is. */
    const val MAX_DELAY_MS = 5000L

    fun matches(search: SsdpMessage, targets: List<SsdpTarget>, udn: String = ""): List<SsdpTarget> {
        if (!search.isSearch) return emptyList()
        if (search.man != "ssdp:discover") return emptyList()

        val st = search.header(SsdpMessage.ST)?.trim() ?: return emptyList()

        return when {
            st == "ssdp:all" -> targets
            st.equals("upnp:rootdevice", ignoreCase = true) -> targets.filter { it == SsdpTarget.ROOT_DEVICE }
            udn.isNotEmpty() && st.equals("uuid:$udn", ignoreCase = true) ->
                targets.filter { it == SsdpTarget.ROOT_DEVICE }
            else -> targets.filter { it.nt.equals(st, ignoreCase = true) }
        }
    }

    /**
     * Milliseconds to wait before replying.
     *
     * @param random01 a value in `[0, 1]` supplied by the caller, so the
     *   decision stays deterministic under test instead of reading a clock or
     *   an RNG (the same technique `CoalescingNetworkMonitor` uses for time).
     */
    fun responseDelayMs(mxSeconds: Int, random01: Double): Long {
        if (mxSeconds <= 0) return 0L
        val windowMs = (mxSeconds * 1000L).coerceAtMost(MAX_DELAY_MS)
        val clamped = random01.coerceIn(0.0, 1.0)
        return (windowMs * clamped).toLong()
    }
}
