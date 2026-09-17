package tv.opentvcast.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the boot-autostart decision of [BootReceiver].
 *
 * The receiver's own behaviour (goAsync + DataStore read + service start) is
 * Android plumbing; the DECISION is what can be wrong in a user-visible way —
 * the receiver dead after every reboot, or a disabled service starting anyway.
 * The decision is therefore extracted as pure functions and pinned here.
 */
class BootReceiverTest {

    // ─── action filter ───────────────────────────────────────────────────────

    @Test
    fun `only the real BOOT_COMPLETED action counts`() {
        assertTrue(BootReceiver.isBootCompleted("android.intent.action.BOOT_COMPLETED"))
        assertFalse(BootReceiver.isBootCompleted("android.intent.action.TIME_SET"))
        assertFalse(BootReceiver.isBootCompleted("BOOT_COMPLETED"))
        assertFalse("a null action (direct app sends) must never autostart", BootReceiver.isBootCompleted(null))
    }

    // ─── autostart gate ──────────────────────────────────────────────────────

    @Test
    fun `autostart mirrors the user setting exactly`() {
        assertFalse("default (off) must not autostart", BootReceiver.shouldAutostart(false))
        assertTrue("enabled must autostart", BootReceiver.shouldAutostart(true))
    }
}
