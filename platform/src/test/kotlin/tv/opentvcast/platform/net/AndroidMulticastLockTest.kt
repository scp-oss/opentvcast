package tv.opentvcast.platform.net

import android.content.Context
import android.net.wifi.WifiManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AndroidMulticastLock].
 *
 * The interesting behaviour is not the happy path — it is what happens when there
 * is no Wi-Fi at all, which is a normal configuration for a wired Android TV or a
 * TV stick with the radio off. In that case `WIFI_SERVICE` is absent, and the whole
 * point of this adapter is that the receiver starts anyway rather than failing to
 * advertise because a lock could not be taken.
 *
 * Also pinned here: `setReferenceCounted(false)`. The project counts references by
 * owner tag in `RefCountedMulticastLock`, and two layers counting the same thing
 * under different rules is how a lock gets released while someone is still relying
 * on it.
 */
class AndroidMulticastLockTest {

    private fun contextWith(wifiManager: WifiManager?): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        return context
    }

    private fun contextWithoutWifiService(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.WIFI_SERVICE) } returns null
        return context
    }

    // ─── happy path ──────────────────────────────────────────────────────────

    @Test
    fun `disables the platform's own reference counting`() {
        val platformLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } returns platformLock

        AndroidMulticastLock(contextWith(wifiManager))

        verify(exactly = 1) { platformLock.setReferenceCounted(false) }
    }

    @Test
    fun `acquire takes the platform lock`() {
        val platformLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        every { platformLock.isHeld } returns false
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } returns platformLock

        AndroidMulticastLock(contextWith(wifiManager)).acquire()

        verify(exactly = 1) { platformLock.acquire() }
    }

    @Test
    fun `acquire is a no-op when the platform lock is already held`() {
        // Two holders starting at once is a normal race, not an error: acquiring
        // twice with reference counting off would make the second release a no-op
        // and leak the lock.
        val platformLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        every { platformLock.isHeld } returns true
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } returns platformLock

        AndroidMulticastLock(contextWith(wifiManager)).acquire()

        verify(exactly = 0) { platformLock.acquire() }
    }

    @Test
    fun `release releases the platform lock`() {
        val platformLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        every { platformLock.isHeld } returns true
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } returns platformLock

        AndroidMulticastLock(contextWith(wifiManager)).release()

        verify(exactly = 1) { platformLock.release() }
    }

    @Test
    fun `release is a no-op when the platform lock is not held`() {
        // Releasing something never acquired throws on some devices.
        val platformLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        every { platformLock.isHeld } returns false
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } returns platformLock

        AndroidMulticastLock(contextWith(wifiManager)).release()

        verify(exactly = 0) { platformLock.release() }
    }

    @Test
    fun `reports whether the platform lock is held`() {
        val platformLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        every { platformLock.isHeld } returns true
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } returns platformLock

        assertTrue(AndroidMulticastLock(contextWith(wifiManager)).isPlatformLockHeld)
    }

    // ─── degradation ─────────────────────────────────────────────────────────

    @Test
    fun `an absent Wi-Fi service does not throw at construction`() {
        // A wired TV, or a stick with the radio off. AirPlay still works over
        // unicast once connected, so this must not prevent the receiver starting.
        val lock = AndroidMulticastLock(contextWithoutWifiService())

        assertFalse(lock.isPlatformLockHeld)
    }

    @Test
    fun `acquire and release are no-ops without a Wi-Fi service`() {
        val lock = AndroidMulticastLock(contextWithoutWifiService())

        lock.acquire()
        lock.acquire()
        lock.release()

        assertFalse(lock.isPlatformLockHeld)
    }

    @Test
    fun `a throwing createMulticastLock does not throw out of the constructor`() {
        // Some vendor builds restrict multicast lock creation. Refusing to start is
        // a worse outcome than running without the lock.
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } throws IllegalStateException("restricted")

        val lock = AndroidMulticastLock(contextWith(wifiManager))

        lock.acquire()
        lock.release()
        assertFalse(lock.isPlatformLockHeld)
    }

    @Test
    fun `a throwing acquire is contained`() {
        val platformLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        every { platformLock.isHeld } returns false
        every { platformLock.acquire() } throws SecurityException("denied")
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } returns platformLock

        // Must not propagate: the service is in the middle of starting receivers.
        AndroidMulticastLock(contextWith(wifiManager)).acquire()
    }

    @Test
    fun `a throwing release is contained`() {
        val platformLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        every { platformLock.isHeld } returns true
        every { platformLock.release() } throws IllegalStateException("not held")
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } returns platformLock

        AndroidMulticastLock(contextWith(wifiManager)).release()
    }

    @Test
    fun `a context that throws on getSystemService is contained`() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(any<String>()) } throws IllegalStateException("no such service")

        val lock = AndroidMulticastLock(context)

        lock.acquire()
        assertFalse(lock.isPlatformLockHeld)
    }

    @Test
    fun `the debug tree is not required for the failure path to be safe`() {
        // Logging goes through Logger with no Timber tree planted in unit tests,
        // which must stay a no-op rather than throwing.
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } throws IllegalStateException("restricted")

        AndroidMulticastLock(contextWith(wifiManager))
    }

    @Test
    fun `acquire is safe to call before any release`() {
        val platformLock = mockk<WifiManager.MulticastLock>(relaxed = true)
        every { platformLock.isHeld } returns false
        every { platformLock.acquire() } just runs
        val wifiManager = mockk<WifiManager>(relaxed = true)
        every { wifiManager.createMulticastLock(any()) } returns platformLock
        val lock = AndroidMulticastLock(contextWith(wifiManager))

        repeat(3) { lock.acquire() }

        // Idempotency per owner tag lives in RefCountedMulticastLock; this adapter
        // only guards against acquiring an already-held platform lock.
        verify(exactly = 3) { platformLock.acquire() }
    }
}
