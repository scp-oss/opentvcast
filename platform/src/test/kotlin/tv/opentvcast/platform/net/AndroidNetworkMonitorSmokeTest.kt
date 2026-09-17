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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tv.opentvcast.core.net.NetworkSnapshot

/**
 * The one part of [AndroidNetworkMonitor] that needs an Android runtime: building
 * the real `ConnectivityManager.NetworkCallback` and registering it.
 *
 * WHAT THIS CATCHES: a `NetworkRequest` the platform rejects, a callback that
 * cannot be registered twice, or an unregister that throws — all of which are
 * invisible to the pure tests, since they inject the signals factory.
 *
 * Kept in its own class on purpose: under Robolectric the sandbox classloader
 * hides these classes from JaCoCo, so anything that does *not* need the runtime
 * belongs in `AndroidNetworkMonitorTest`, where coverage is measured and the
 * SDK-free aggregate runner can reach it too.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidNetworkMonitorSmokeTest {

    @Test
    fun `the real ConnectivityManager registration path registers and unregisters cleanly`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = AndroidNetworkMonitor.create(
            context,
            scope,
            snapshot = { NetworkSnapshot(null, emptySet()) },
        )

        monitor.start { }
        monitor.stop()

        assertFalse("the callback must be released on stop", monitor.isRegistered)
    }

    @Test
    fun `the factory built by create is usable for a full start-stop-start cycle`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = AndroidNetworkMonitor.create(
            context,
            scope,
            snapshot = { NetworkSnapshot("10.0.0.5", setOf("10.0.0.5")) },
        )

        monitor.start { }
        monitor.stop()
        monitor.start { }
        monitor.stop()
    }
}
