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
import android.net.wifi.WifiManager
import tv.opentvcast.core.net.PlatformMulticastLock
import tv.opentvcast.util.Logger

/**
 * [PlatformMulticastLock] over `WifiManager.MulticastLock`.
 *
 * WHY: `AndroidManifest.xml` has declared `CHANGE_WIFI_MULTICAST_STATE` since the
 * fork and nothing ever acquired a lock. On many Android devices that is enough to
 * make the receiver invisible — mDNS registration succeeds, no error is logged, and
 * inbound multicast simply never arrives, so the TV never appears in the AirPlay
 * picker. The permission alone does nothing; the lock is what opens the filter.
 *
 * ### Graceful degradation
 *
 * An Ethernet-only TV can have no Wi-Fi service at all, and some vendors restrict
 * multicast even with the permission granted. Neither is a reason to refuse to
 * start: AirPlay still works over unicast once a sender is connected, and DLNA
 * needs the same lock for SSDP. So every failure here is logged and swallowed, and
 * the receiver runs with whatever connectivity it has.
 *
 * ### Reference counting is not delegated
 *
 * `MulticastLock.setReferenceCounted(false)` because [tv.opentvcast.core.net.RefCountedMulticastLock]
 * already counts by owner tag, and it needs to know when the *last* holder lets go
 * so it can release exactly once. Leaving the platform's own counting on would mean
 * two layers counting the same thing with different rules, and a mismatch there
 * leaks the lock — or worse, releases it while another protocol is still relying
 * on it.
 */
class AndroidMulticastLock(context: Context) : PlatformMulticastLock {

    private val lock: WifiManager.MulticastLock? = try {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.createMulticastLock(LOCK_TAG)?.apply { setReferenceCounted(false) }
    } catch (e: Exception) {
        Logger.w("Multicast lock unavailable — discovery may not work on this device", e)
        null
    }

    override fun acquire() {
        val target = lock ?: return
        // Already held is a normal race (two holders starting at once), not an error.
        if (target.isHeld) return
        runCatching { target.acquire() }
            .onFailure { Logger.w("Multicast lock acquire failed", it) }
    }

    override fun release() {
        val target = lock ?: return
        if (!target.isHeld) return
        runCatching { target.release() }
            .onFailure { Logger.w("Multicast lock release failed", it) }
    }

    /** Whether the platform lock is actually held. Diagnostics and the debug overlay. */
    val isPlatformLockHeld: Boolean
        get() = lock?.isHeld == true

    private companion object {
        /** Shown in `dumpsys wifi`; identifies the app when diagnosing on a device. */
        const val LOCK_TAG = "opentvcast:multicast"
    }
}
