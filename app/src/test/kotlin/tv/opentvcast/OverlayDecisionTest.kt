package tv.opentvcast

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.opentvcast.OverlayDecision.Overlay
import tv.opentvcast.airplay.NowPlayingInfo
import tv.opentvcast.core.protocol.ProtocolState

/**
 * Tests for [OverlayDecision] — the full-screen overlay priority extracted
 * from MainActivity.updateOverlay as a pure function.
 *
 * WHY A SHARED FUNCTION AND NOT A REPLICA: an earlier version of this test
 * hand-copied "state == CONNECTED → show" out of MainActivity and drifted the
 * moment the decision grew the PIN / now-playing / photo priorities. The
 * production `when` now CALLS this decision, so the tests exercise the exact
 * logic the activity runs.
 *
 * The priority order is the product behaviour:
 *   PIN (pairing) > now-playing card (audio-only) > live video > photo > hidden.
 */
class OverlayDecisionTest {

    private val photo = PhotoFrame(bytes = byteArrayOf(1), mimeType = "image/jpeg")
    private val nowPlaying = NowPlayingInfo(senderName = "iPad", title = "Song")

    @Test
    fun `a fresh activity hides the overlay`() {
        assertEquals(
            Overlay.NONE,
            OverlayDecision.resolve(null, null, ProtocolState.DISABLED, null),
        )
    }

    @Test
    fun `connected video shows the streaming screen`() {
        assertEquals(
            Overlay.STREAMING,
            OverlayDecision.resolve(null, null, ProtocolState.CONNECTED, null),
        )
    }

    @Test
    fun `non-connected protocol states keep the overlay hidden`() {
        for (state in ProtocolState.entries - ProtocolState.CONNECTED) {
            assertEquals(
                "$state must not show the streaming screen",
                Overlay.NONE,
                OverlayDecision.resolve(null, null, state, null),
            )
        }
    }

    @Test
    fun `a photo shows the photo screen when idle`() {
        assertEquals(
            Overlay.PHOTO,
            OverlayDecision.resolve(null, null, ProtocolState.DISABLED, photo),
        )
    }

    @Test
    fun `audio-only now-playing shows the now-playing screen`() {
        assertEquals(
            Overlay.NOW_PLAYING,
            OverlayDecision.resolve(null, nowPlaying, ProtocolState.DISABLED, null),
        )
    }

    @Test
    fun `now-playing outranks a photo`() {
        assertEquals(
            Overlay.NOW_PLAYING,
            OverlayDecision.resolve(null, nowPlaying, ProtocolState.DISABLED, photo),
        )
    }

    @Test
    fun `now-playing outranks a CONNECTED state - the audio takeover case`() {
        // The receiver emits the audio card while the session is CONNECTED in
        // exactly one real situation: mirror/URL video stopped and still-playing
        // audio took the screen back. The card must win there — the first
        // draft of this test asserted the opposite and was wrong about the
        // receiver's semantics.
        assertEquals(
            Overlay.NOW_PLAYING,
            OverlayDecision.resolve(null, nowPlaying, ProtocolState.CONNECTED, null),
        )
    }

    @Test
    fun `the pairing PIN outranks everything`() {
        assertEquals(
            Overlay.PIN,
            OverlayDecision.resolve("1234", nowPlaying, ProtocolState.CONNECTED, photo),
        )
        assertEquals(
            Overlay.PIN,
            OverlayDecision.resolve("1234", null, ProtocolState.DISABLED, null),
        )
    }
}
