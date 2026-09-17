package tv.opentvcast

import tv.opentvcast.airplay.NowPlayingInfo
import tv.opentvcast.core.protocol.ProtocolState

/**
 * The full-screen overlay decision, extracted from MainActivity.updateOverlay
 * as a pure function.
 *
 * MainActivity collects four StateFlows from [tv.opentvcast.service.CastService]
 * (pairing PIN, AirPlay protocol state, now-playing card, photo frame) and must
 * pick exactly one full-screen overlay to show. The priority encodes the real
 * usage order:
 *
 * 1. **PIN** — access-control pairing happens BEFORE any streaming, so the code
 *    must be visible over everything the moment it appears.
 * 2. **Now-playing** — audio-only AirPlay (system audio, Music) shows the
 *    metadata card instead of a black video surface.
 * 3. **Streaming** — a CONNECTED AirPlay session with video owns the screen.
 *    It outranks now-playing because mirroring with system audio must not
 *    flash the audio card on top of the video.
 * 4. **Photo** — a received still image shows when nothing else is active.
 * 5. **NONE** — the normal app UI.
 *
 * An earlier version of this `when` lived inline in the activity, and the test
 * hand-copied a drift-gone STALE subset of it ("state == CONNECTED"). Both now
 * share this function — the same anti-drift move as [tv.opentvcast.service.ConnectionMapping].
 */
object OverlayDecision {

    /** The overlay MainActivity must show, in priority order. */
    enum class Overlay { PIN, NOW_PLAYING, STREAMING, PHOTO, NONE }

    fun resolve(
        pin: String?,
        nowPlaying: NowPlayingInfo?,
        airPlayState: ProtocolState,
        photoFrame: PhotoFrame?,
    ): Overlay = when {
        pin != null                                     -> Overlay.PIN
        nowPlaying != null                              -> Overlay.NOW_PLAYING
        airPlayState == ProtocolState.CONNECTED         -> Overlay.STREAMING
        photoFrame != null                              -> Overlay.PHOTO
        else                                            -> Overlay.NONE
    }
}
