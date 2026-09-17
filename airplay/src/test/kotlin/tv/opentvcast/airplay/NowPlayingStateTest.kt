package tv.opentvcast.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [NowPlayingState] — the extracted audio-only "now playing" card
 * state machine. Every transition returns exactly what the receiver must emit
 * (a [NowPlayingInfo] when audio plays WITHOUT video, else null), so the
 * receiver cannot emit a stale card by forgetting a recomputation.
 *
 * Scenarios mirror the real call sites in AirPlayReceiver: mirror/URL video
 * start, RTP + buffered audio start/stop, DMAP metadata and artwork pushes,
 * ANNOUNCE stream flags, and the full TEARDOWN reset.
 */
class NowPlayingStateTest {

    @Test
    fun `a fresh receiver emits no card`() {
        assertNull(NowPlayingState().onAnnounce("iPad", hasVideo = false, hasAudio = false))
    }

    @Test
    fun `audio alone shows the card with the default sender name`() {
        val state = NowPlayingState()

        val info = state.onAudioStarted()

        assertEquals("AirPlay", info!!.senderName)
        assertNull("no track pushed yet", info.title)
    }

    @Test
    fun `metadata and artwork pushed while playing reach the card`() {
        val state = NowPlayingState()
        state.onAudioStarted()

        val withTrack = state.onMetadata("Song", "Artist", "Album")!!
        assertEquals("Song", withTrack.title)
        assertEquals("Artist", withTrack.artist)
        assertEquals("Album", withTrack.album)

        val artwork = byteArrayOf(1, 2, 3)
        val withArt = state.onArtwork(artwork)!!
        assertArrayEquals(artwork, withArt.artwork)
    }

    @Test
    fun `an empty artwork payload is stored as absent`() {
        val state = NowPlayingState()
        state.onAudioStarted()

        val info = state.onArtwork(ByteArray(0))!!

        assertNull("empty artwork bytes mean 'no artwork'", info.artwork)
    }

    @Test
    fun `video suppresses the card even while audio plays`() {
        val state = NowPlayingState()
        state.onAudioStarted()

        assertNull("mirroring is video → no audio card", state.onMirrorStarted())

        // and a metadata push during video still emits nothing.
        assertNull(state.onMetadata("Song", null, null))
    }

    @Test
    fun `video ending hands the card back to still-playing audio`() {
        val state = NowPlayingState()
        state.onAudioStarted()
        state.onMetadata("Song", "Artist", null)
        state.onMirrorStarted()

        val info = state.onVideoStopped()!!

        assertEquals("Song", info.title)
        assertEquals("Artist", info.artist)
    }

    @Test
    fun `audio stopping clears the card AND the track metadata`() {
        val state = NowPlayingState()
        state.onAudioStarted()
        state.onMetadata("Song", null, null)

        assertNull("audio stopped → card gone", state.onAudioStopped())

        // A later audio session must not inherit the previous track's metadata.
        val next = state.onAudioStarted()!!
        assertNull("stale metadata bled into the next session", next.title)
    }

    @Test
    fun `ANNOUNCE replaces the sender name and stream flags`() {
        val state = NowPlayingState()
        state.onAudioStarted()

        // Video-only announce: no card.
        assertNull(state.onAnnounce("iPad", hasVideo = true, hasAudio = false))

        // Audio-only announce: card under the NEW sender name.
        val info = state.onAnnounce("iPad", hasVideo = false, hasAudio = true)!!
        assertEquals("iPad", info.senderName)
    }

    @Test
    fun `a blank ANNOUNCE sender keeps the previous one`() {
        val state = NowPlayingState()
        state.onAnnounce("iPad", hasVideo = false, hasAudio = true)

        val info = state.onAnnounce("", hasVideo = false, hasAudio = true)!!

        assertEquals("blank sender must not wipe the known name", "iPad", info.senderName)
    }

    @Test
    fun `stream ended resets everything`() {
        val state = NowPlayingState()
        state.onAudioStarted()
        state.onMetadata("Song", "Artist", "Album")

        assertNull(state.onStreamEnded())

        // After the reset, only a genuine new audio start shows a bare card.
        val fresh = state.onAudioStarted()!!
        assertNull(fresh.title)
        assertNull(fresh.artist)
        assertNull(fresh.album)
        assertNull(fresh.artwork)
    }
}
