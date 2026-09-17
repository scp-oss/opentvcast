package tv.opentvcast.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [InfoResponder.buildBody] — the body of `GET /info`, the FIRST
 * request every macOS sender makes. The response decides whether the sender
 * continues to pairing → FairPlay → mirroring; a missing or wrong field means
 * the TV never even appears usable, with no error anywhere.
 *
 * The wire values pinned here mirror [AppleIdentity] so an identity constant
 * drifting away from what /info advertises fails a test instead of failing
 * on a Mac.
 */
class InfoResponderTest {

    private val mac = "AA:BB:CC:DD:EE:FF"
    private val pk = ByteArray(32) { it.toByte() }

    private fun body(
        width: Int = 1920,
        height: Int = 1080,
        pinRequired: Boolean = false,
    ): Map<String, Any?> = PlistCodec.decode(
        InfoResponder.buildBody(
            mac = mac,
            deviceName = "Living Room TV",
            persistentUuid = "01234567-89AB-CDEF-0123-456789ABCDEF",
            edPublic = pk,
            width = width,
            height = height,
            pinRequired = pinRequired,
        )
    )

    @Test
    fun `identity fields match AppleIdentity and the request values`() {
        val info = body()

        assertEquals(mac, info["deviceID"])
        assertEquals(mac, info["macAddress"])
        assertEquals("Living Room TV", info["name"])
        assertEquals("01234567-89AB-CDEF-0123-456789ABCDEF", info["pi"])
        assertArrayEquals(pk, info["pk"] as ByteArray)
        assertEquals(AppleIdentity.FEATURES_MASK, info["features"])
        assertEquals(AppleIdentity.MODEL, info["model"])
        assertEquals(AppleIdentity.SOURCE_VERSION, info["sourceVersion"])
        assertEquals(AppleIdentity.PROTOCOL_VERSION, info["protovers"])
        assertEquals(2L, info["vv"])
    }

    @Test
    fun `statusFlags advertise PIN requirement only when pinRequired`() {
        assertEquals(68L, body(pinRequired = false)["statusFlags"])
        assertEquals(68L or 0x8L, body(pinRequired = true)["statusFlags"])
    }

    @Test
    fun `display advertises the requested resolution`() {
        val display = (body()["displays"] as List<*>).single() as Map<*, *>

        assertEquals(1920L, display["width"])
        assertEquals(1080L, display["height"])
        assertEquals(1920L, display["widthPixels"])
        assertEquals(1080L, display["heightPixels"])
        assertEquals(false, display["overscanned"])
    }

    @Test
    fun `custom dimensions are honoured, not hardcoded`() {
        val display = (body(width = 3840, height = 2160)["displays"] as List<*>).single() as Map<*, *>

        assertEquals(3840L, display["width"])
        assertEquals(2160L, display["height"])
    }

    @Test
    fun `audio formats and latencies list both format types`() {
        val info = body()

        val formats = info["audioFormats"] as List<*>
        assertEquals(2, formats.size)
        val types = formats.map { (it as Map<*, *>)["type"] }
        assertTrue("both 100 and 101 must be advertised", types == listOf(100L, 101L))

        val latencies = info["audioLatencies"] as List<*>
        assertEquals(2, latencies.size)
    }
}
