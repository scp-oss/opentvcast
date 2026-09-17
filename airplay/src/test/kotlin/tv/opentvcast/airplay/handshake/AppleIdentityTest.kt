package tv.opentvcast.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [AppleIdentity] — the single source of truth for the Apple device
 * identity presented to senders. The values are not arbitrary: they mirror a real
 * Apple TV, and a sender cross-checks them across the mDNS TXT record, the `/info`
 * plist, and the `/server-info` plist. A disagreement makes the receiver invisible
 * with no error, so the exact known-good literals are pinned here.
 */
class AppleIdentityTest {

    @Test
    fun `the mDNS TXT form is derived from the numeric mask`() {
        // "0x5A7FFFF7,0x1E" is the split (low,high) 32-bit halves of 0x1E5A7FFFF7.
        // Deriving one from the other is what makes drift between mDNS and /info
        // impossible; this pins the derivation to the value real senders accept.
        assertEquals("0x5A7FFFF7,0x1E", AppleIdentity.FEATURES_TXT)
        assertEquals(0x1E5A7FFFF7L, AppleIdentity.FEATURES_MASK)
    }

    @Test
    fun `the response Server header is derived from the source version`() {
        assertEquals("AirTunes/220.68", AppleIdentity.SERVER_HEADER)
        assertEquals("220.68", AppleIdentity.SOURCE_VERSION)
    }

    @Test
    fun `the spoofed model and protocol version match a real Apple TV`() {
        assertEquals("AppleTV5,3", AppleIdentity.MODEL)
        assertEquals("1.1", AppleIdentity.PROTOCOL_VERSION)
    }
}
