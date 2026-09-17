package tv.opentvcast.dlna.soap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.opentvcast.dlna.ssdp.DlnaServices

/**
 * Tests for [DlnaControlDispatcher] — the SOAP control surface (FR-34) and the
 * transport state machine behind it (FR-36).
 *
 * WHY: this is where a renderer's behaviour actually lives. A control point
 * sends `SetAVTransportURI` then `Play`; if our state machine refuses the
 * second call because the first left us in the wrong state, the sender shows
 * "cannot play to this device" and we have nothing in the log.
 *
 * The player is injected as a port, so every transition below is verified
 * against what the player was told to do — never against a real MediaPlayer.
 */
class DlnaControlDispatcherTest {

    /** Records what the renderer asked the player to do. */
    private class FakePlayer : DlnaPlayerPort {
        val calls = mutableListOf<String>()
        override var positionMs: Long = 0L
        override var durationMs: Long = 600_000L

        override fun play(uri: String) { calls += "play:$uri"; positionMs = 0L }
        override fun resume() { calls += "resume" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop"; positionMs = 0L }
        override fun seek(targetMs: Long) { calls += "seek:$targetMs"; positionMs = targetMs }
        override fun release() {}
    }

    private fun uri() = "http://192.168.1.9:8200/media/movie.mp4"

    private fun dispatcher(): Triple<DlnaControlDispatcher, FakePlayer, DlnaRendererState> {
        val player = FakePlayer()
        val state = DlnaRendererState()
        return Triple(DlnaControlDispatcher(state, player), player, state)
    }

    private fun dispatch(
        d: DlnaControlDispatcher,
        service: tv.opentvcast.dlna.ssdp.DlnaService,
        action: String,
        vararg args: Pair<String, String>,
    ): String = d.dispatch(service, action, args.toMap())

    // ─── AVTransport ─────────────────────────────────────────────────────────

    @Test
    fun `SetAVTransportURI stores the URI and leaves the transport stopped`() {
        val (d, _, state) = dispatcher()

        val reply = dispatch(d, DlnaServices.avTransport, "SetAVTransportURI",
            "InstanceID" to "0", "CurrentURI" to uri())

        assertEquals(uri(), state.currentUri)
        assertEquals(TransportPhase.STOPPED, state.phase)
        assertTrue(reply.contains("SetAVTransportURIResponse"))
    }

    @Test
    fun `Play starts the player and reports PLAYING`() {
        val (d, player, state) = dispatcher()
        dispatch(d, DlnaServices.avTransport, "SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to uri())

        dispatch(d, DlnaServices.avTransport, "Play", "InstanceID" to "0", "Speed" to "1")

        assertEquals(TransportPhase.PLAYING, state.phase)
        assertTrue("the player must be told to play", player.calls.contains("play:${uri()}"))
    }

    @Test
    fun `Play before any URI is refused as an invalid transition`() {
        val (d, player, _) = dispatcher()

        val reply = dispatch(d, DlnaServices.avTransport, "Play", "InstanceID" to "0", "Speed" to "1")

        assertTrue(reply.contains("<errorCode>701</errorCode>"))
        assertTrue("nothing may reach the player", player.calls.isEmpty())
    }

    @Test
    fun `Pause is only legal while playing`() {
        val (d, player, state) = dispatcher()
        dispatch(d, DlnaServices.avTransport, "SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to uri())
        dispatch(d, DlnaServices.avTransport, "Play", "InstanceID" to "0", "Speed" to "1")

        dispatch(d, DlnaServices.avTransport, "Pause", "InstanceID" to "0")
        assertEquals(TransportPhase.PAUSED, state.phase)

        // A second Pause is a no-op rather than an error: senders re-send Pause
        // when they lose track of our state, and failing it breaks playback.
        assertTrue(dispatch(d, DlnaServices.avTransport, "Pause", "InstanceID" to "0").contains("PauseResponse"))
        assertTrue(player.calls.contains("pause"))
    }

    @Test
    fun `Pause before anything is loaded is refused as an invalid transition`() {
        // Found by a mutation sweep: deleting the fault branch of Pause left the
        // whole suite green, because every Pause test started from PLAYING. A
        // control point that pauses an idle renderer must be told no, not lied to.
        val (d, player, _) = dispatcher()

        val reply = dispatch(d, DlnaServices.avTransport, "Pause", "InstanceID" to "0")

        assertTrue(reply.contains("<errorCode>701</errorCode>"))
        assertTrue("nothing may reach the player", player.calls.isEmpty())
    }

    @Test
    fun `Resume after Pause goes back to PLAYING without restarting the media`() {
        val (d, player, state) = dispatcher()
        dispatch(d, DlnaServices.avTransport, "SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to uri())
        dispatch(d, DlnaServices.avTransport, "Play", "InstanceID" to "0", "Speed" to "1")
        dispatch(d, DlnaServices.avTransport, "Pause", "InstanceID" to "0")

        dispatch(d, DlnaServices.avTransport, "Play", "InstanceID" to "0", "Speed" to "1")

        assertEquals(TransportPhase.PLAYING, state.phase)
        assertTrue("resume must not reload the media", player.calls.contains("resume"))
        assertTrue(player.calls.count { it.startsWith("play:") } == 1)
    }

    @Test
    fun `Stop releases the player`() {
        val (d, player, state) = dispatcher()
        dispatch(d, DlnaServices.avTransport, "SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to uri())
        dispatch(d, DlnaServices.avTransport, "Play", "InstanceID" to "0", "Speed" to "1")

        dispatch(d, DlnaServices.avTransport, "Stop", "InstanceID" to "0")

        assertEquals(TransportPhase.STOPPED, state.phase)
        assertTrue(player.calls.contains("stop"))
    }

    @Test
    fun `Seek moves the position while playing or paused`() {
        val (d, player, _) = dispatcher()
        dispatch(d, DlnaServices.avTransport, "SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to uri())
        dispatch(d, DlnaServices.avTransport, "Play", "InstanceID" to "0", "Speed" to "1")

        dispatch(d, DlnaServices.avTransport, "Seek", "InstanceID" to "0",
            "Unit" to "REL_TIME", "Target" to "00:01:30")

        assertTrue(player.calls.contains("seek:90000"))
    }

    @Test
    fun `Seek is refused when nothing is loaded`() {
        val (d, player, _) = dispatcher()

        val reply = dispatch(d, DlnaServices.avTransport, "Seek", "InstanceID" to "0",
            "Unit" to "REL_TIME", "Target" to "00:01:30")

        assertTrue(reply.contains("<errorCode>701</errorCode>"))
        assertTrue(player.calls.none { it.startsWith("seek") })
    }

    @Test
    fun `GetPositionInfo reports the clock in UPnP time format`() {
        val (d, _, _) = dispatcher()
        dispatch(d, DlnaServices.avTransport, "SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to uri())
        // Seek is only legal once the transport has left STOPPED.
        dispatch(d, DlnaServices.avTransport, "Play", "InstanceID" to "0", "Speed" to "1")
        dispatch(d, DlnaServices.avTransport, "Seek", "InstanceID" to "0",
            "Unit" to "REL_TIME", "Target" to "00:01:23")

        val reply = dispatch(d, DlnaServices.avTransport, "GetPositionInfo", "InstanceID" to "0")

        assertTrue(reply.contains("<RelTime>00:01:23</RelTime>"))
        assertTrue(reply.contains("<TrackDuration>00:10:00</TrackDuration>"))
    }

    @Test
    fun `GetTransportInfo reports the current phase`() {
        val (d, _, _) = dispatcher()
        dispatch(d, DlnaServices.avTransport, "SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to uri())
        dispatch(d, DlnaServices.avTransport, "Play", "InstanceID" to "0", "Speed" to "1")

        val reply = dispatch(d, DlnaServices.avTransport, "GetTransportInfo", "InstanceID" to "0")

        assertTrue(reply.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"))
    }

    @Test
    fun `an unknown action is an invalid action fault, not a crash`() {
        val (d, _, _) = dispatcher()

        val reply = dispatch(d, DlnaServices.avTransport, "Explode", "InstanceID" to "0")

        assertTrue(reply.contains("<errorCode>401</errorCode>"))
    }

    // ─── RenderingControl ────────────────────────────────────────────────────

    @Test
    fun `SetVolume clamps to the 0-100 range a control point expects`() {
        val (d, _, state) = dispatcher()

        dispatch(d, DlnaServices.renderingControl, "SetVolume",
            "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "250")
        assertEquals(100, state.volume)

        dispatch(d, DlnaServices.renderingControl, "SetVolume",
            "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "-4")
        assertEquals(0, state.volume)
    }

    @Test
    fun `GetVolume returns what SetVolume stored`() {
        val (d, _, _) = dispatcher()
        dispatch(d, DlnaServices.renderingControl, "SetVolume",
            "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "42")

        val reply = dispatch(d, DlnaServices.renderingControl, "GetVolume",
            "InstanceID" to "0", "Channel" to "Master")

        assertTrue(reply.contains("<CurrentVolume>42</CurrentVolume>"))
    }

    @Test
    fun `mute round-trips`() {
        val (d, _, state) = dispatcher()

        dispatch(d, DlnaServices.renderingControl, "SetMute",
            "InstanceID" to "0", "Channel" to "Master", "DesiredMute" to "1")
        assertTrue(state.muted)

        val reply = dispatch(d, DlnaServices.renderingControl, "GetMute",
            "InstanceID" to "0", "Channel" to "Master")
        assertTrue(reply.contains("<CurrentMute>1</CurrentMute>"))
    }

    // ─── ConnectionManager ───────────────────────────────────────────────────

    @Test
    fun `GetProtocolInfo advertises what we can sink`() {
        val (d, _, _) = dispatcher()

        val reply = dispatch(d, DlnaServices.connectionManager, "GetProtocolInfo")

        assertTrue(reply.contains("<Sink>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*</Sink>"))
    }

    @Test
    fun `GetCurrentConnectionIDs reports the single renderer instance`() {
        val (d, _, _) = dispatcher()

        val reply = dispatch(d, DlnaServices.connectionManager, "GetCurrentConnectionIDs")

        assertTrue(reply.contains("<ConnectionIDs>0</ConnectionIDs>"))
    }

    // ─── shared behaviour ────────────────────────────────────────────────────

    @Test
    fun `an action called on the wrong service is an invalid action fault`() {
        val (d, _, _) = dispatcher()

        // RenderingControl's action, addressed to AVTransport.
        val reply = dispatch(d, DlnaServices.avTransport, "SetVolume",
            "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "10")

        assertTrue(reply.contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun `an unparseable InstanceID is an invalid args fault rather than a crash`() {
        val (d, _, _) = dispatcher()

        val reply = dispatch(d, DlnaServices.avTransport, "Play", "InstanceID" to "abc", "Speed" to "1")

        assertTrue(reply.contains("<errorCode>402</errorCode>"))
    }
}
