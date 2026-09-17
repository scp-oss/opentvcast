package tv.opentvcast.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [StreamStats], the counters behind the optional debug overlay.
 *
 * This is a mutable singleton written from the per-frame decode path and read by
 * the UI a few times a second, so the interesting properties are about *lifecycle*
 * rather than formatting: what a session end must clear, and — more subtly — what
 * it must NOT clear.
 *
 * [StreamStats.overlayEnabled] is mirrored from the user's setting when the receiver
 * starts. If [StreamStats.resetStreams] cleared it, the HUD would switch itself off
 * every time a session ended, and the toggle in Settings would appear to have no
 * effect until the app was restarted.
 */
class StreamStatsTest {

    @Before
    fun resetToBaseline() {
        StreamStats.resetStreams()
        StreamStats.overlayEnabled = false
    }

    @Test
    fun `resetStreams clears every per-stream counter`() {
        StreamStats.videoRes = "1920x1080"
        StreamStats.videoFps = 60
        StreamStats.videoQueue = 3
        StreamStats.videoDropPct = 7
        StreamStats.videoWidth = 1920
        StreamStats.videoHeight = 1080
        StreamStats.audioActive = true
        StreamStats.audioQueue = 12
        StreamStats.audioDupPct = 4

        StreamStats.resetStreams()

        assertEquals("", StreamStats.videoRes)
        assertEquals(0, StreamStats.videoFps)
        assertEquals(0, StreamStats.videoQueue)
        assertEquals(0, StreamStats.videoDropPct)
        assertEquals(0, StreamStats.videoWidth)
        assertEquals(0, StreamStats.videoHeight)
        assertFalse(StreamStats.audioActive)
        assertEquals(0, StreamStats.audioQueue)
        assertEquals(0, StreamStats.audioDupPct)
    }

    @Test
    fun `resetStreams preserves the overlay switch`() {
        StreamStats.overlayEnabled = true
        StreamStats.videoFps = 30

        StreamStats.resetStreams()

        assertTrue(
            "The overlay setting comes from user preferences, not from the session. " +
                "Clearing it here would make the Settings toggle look broken until " +
                "the app restarted",
            StreamStats.overlayEnabled,
        )
    }

    @Test
    fun `resetting twice is harmless`() {
        StreamStats.resetStreams()
        StreamStats.resetStreams()

        assertEquals("", StreamStats.videoRes)
    }

    // ─── summary formatting ──────────────────────────────────────────────────

    @Test
    fun `summary names the app`() {
        assertTrue(
            "The HUD is a screenshot in bug reports, so it must identify the build",
            StreamStats.summary().startsWith("opentvcast · debug"),
        )
    }

    @Test
    fun `summary shows a placeholder resolution before video starts`() {
        // An em dash rather than "0x0", which would look like a decode failure.
        val summary = StreamStats.summary()

        assertTrue(summary, summary.contains("VIDEO  —"))
    }

    @Test
    fun `summary shows the resolution once the stream is up`() {
        StreamStats.videoRes = "1280x720"
        StreamStats.videoFps = 30
        StreamStats.videoQueue = 2
        StreamStats.videoDropPct = 1

        val summary = StreamStats.summary()

        assertTrue(summary.contains("1280x720"))
        assertTrue(summary.contains("30 fps"))
        assertTrue(summary.contains("q 2"))
        assertTrue(summary.contains("drop 1%"))
    }

    @Test
    fun `summary reports audio off while no audio stream runs`() {
        val summary = StreamStats.summary()

        assertTrue(
            "Audio is optional and often absent (video-only mirroring), so it must " +
                "render as 'off' rather than as zeros",
            summary.contains("AUDIO  off"),
        )
    }

    @Test
    fun `summary reports audio queue and duplicate rate when active`() {
        StreamStats.audioActive = true
        StreamStats.audioQueue = 9
        StreamStats.audioDupPct = 3

        val summary = StreamStats.summary()

        assertTrue(summary.contains("on"))
        assertTrue(summary.contains("q 9"))
        assertTrue(summary.contains("dup 3%"))
    }

    @Test
    fun `summary is multi-line so the HUD does not need to compose it`() {
        assertEquals(
            "The overlay renders this verbatim; line count is part of the contract",
            3,
            StreamStats.summary().lines().size,
        )
    }

    @Test
    fun `portrait streams report their own dimensions`() {
        // A phone in portrait mirrors portrait; the UI must be able to aspect-fit
        // to these rather than stretching to 16:9.
        StreamStats.videoRes = "1080x1920"
        StreamStats.videoWidth = 1080
        StreamStats.videoHeight = 1920

        assertTrue(StreamStats.summary().contains("1080x1920"))
        assertEquals(1080, StreamStats.videoWidth)
        assertEquals(1920, StreamStats.videoHeight)
    }
}
