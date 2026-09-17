package tv.opentvcast.airplay

/**
 * NowPlayingState — the extracted state machine behind the audio-only
 * "now playing" card.
 *
 * The card shows exactly when audio plays WITHOUT video (mirroring or URL
 * video always takes over the screen). Track metadata and artwork are pushed
 * by the sender via DMAP `SET_PARAMETER` bodies and MUST NOT bleed from one
 * audio session into the next — an audio stop clears them.
 *
 * Every transition returns precisely what the receiver must emit: a
 * [NowPlayingInfo] when the card should be visible after the transition, else
 * `null`. This makes a forgotten recomputation impossible at the call site —
 * the previous inline implementation (seven @Volatile fields plus a separate
 * `emitNowPlaying()` that each mutation had to remember to call) allowed a
 * stale card whenever a setter skipped the emit.
 *
 * Thread-safety: transitions arrive from the RTSP thread and from background
 * coroutines (mirror/audio servers), so every method is synchronized; each
 * transition is atomic — no caller can observe a half-applied one.
 */
internal class NowPlayingState {

    private var audioPlaying = false
    private var videoPlaying = false
    private var senderName = DEFAULT_SENDER_NAME
    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var artwork: ByteArray? = null

    /** ANNOUNCE: a sender connected and declared its stream kinds. */
    @Synchronized
    fun onAnnounce(senderName: String, hasVideo: Boolean, hasAudio: Boolean): NowPlayingInfo? {
        if (senderName.isNotBlank()) this.senderName = senderName
        videoPlaying = hasVideo
        audioPlaying = hasAudio
        return snapshot()
    }

    /** Sender pushed track metadata (DMAP minm/asar/asal). */
    @Synchronized
    fun onMetadata(title: String?, artist: String?, album: String?): NowPlayingInfo? {
        this.title = title
        this.artist = artist
        this.album = album
        return snapshot()
    }

    /** Sender pushed artwork; an empty payload means "no artwork". */
    @Synchronized
    fun onArtwork(bytes: ByteArray?): NowPlayingInfo? {
        artwork = bytes?.takeIf { it.isNotEmpty() }
        return snapshot()
    }

    /** A mirror stream started — video takes over the screen. */
    @Synchronized
    fun onMirrorStarted(): NowPlayingInfo? {
        videoPlaying = true
        return snapshot()
    }

    /** Mirror/URL video ended — audio may still be playing and takes the card back. */
    @Synchronized
    fun onVideoStopped(): NowPlayingInfo? {
        videoPlaying = false
        return snapshot()
    }

    /** An RTP or buffered audio stream started. */
    @Synchronized
    fun onAudioStarted(): NowPlayingInfo? {
        audioPlaying = true
        return snapshot()
    }

    /** An audio stream stopped — the card goes away and the track metadata is dropped. */
    @Synchronized
    fun onAudioStopped(): NowPlayingInfo? {
        audioPlaying = false
        clearTrackMetadata()
        return snapshot()
    }

    /** Full teardown (receiver stop or connection reset) — everything resets. */
    @Synchronized
    fun onStreamEnded(): NowPlayingInfo? {
        audioPlaying = false
        videoPlaying = false
        clearTrackMetadata()
        return snapshot()
    }

    /** Drops stale track metadata/artwork so it cannot bleed into the next session. */
    private fun clearTrackMetadata() {
        title = null
        artist = null
        album = null
        artwork = null
    }

    /** The emit decision: audio without video shows the card, everything else is `null`. */
    private fun snapshot(): NowPlayingInfo? =
        if (audioPlaying && !videoPlaying) {
            NowPlayingInfo(senderName, title, artist, album, artwork)
        } else {
            null
        }

    companion object {
        /** Shown until a sender announces its real name. */
        const val DEFAULT_SENDER_NAME = "AirPlay"
    }
}
