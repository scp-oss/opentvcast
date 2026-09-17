package tv.opentvcast.airplay.handshake

/**
 * The Apple device identity this receiver presents to senders.
 *
 * A sender cross-checks these values across three surfaces: the mDNS TXT record
 * ([tv.opentvcast.airplay.MdnsService]), the RTSP `/info` plist
 * ([InfoResponder]), and the legacy `/server-info` XML plist plus the response
 * `Server:` header ([tv.opentvcast.airplay.RtspHandler]). If they disagree, the
 * sender treats the receiver as inconsistent and drops it — usually with no
 * error anywhere. This object is the single source of truth so they cannot drift.
 */
internal object AppleIdentity {

    /**
     * 64-bit AirPlay feature bitmask (mirroring, video, audio). mDNS TXT carries
     * it split into two 32-bit halves: "0x5A7FFFF7,0x1E" (low,high).
     */
    const val FEATURES_MASK = 0x1E5A7FFFF7L

    /** The same bitmask in mDNS TXT form; derived so it cannot disagree with [FEATURES_MASK]. */
    val FEATURES_TXT: String = run {
        val low = FEATURES_MASK and 0xFFFF_FFFFL
        val high = FEATURES_MASK ushr 32
        "0x%08X,0x%X".format(low, high)
    }

    /** Pretend to be an Apple TV so macOS uses the screen mirroring protocol. */
    const val MODEL = "AppleTV5,3"

    /** AirPlay server version — matches a real Apple TV for maximum compatibility. */
    const val SOURCE_VERSION = "220.68"

    /** Value of the response `Server:` header. */
    const val SERVER_HEADER = "AirTunes/$SOURCE_VERSION"

    /** `protovers` advertised in both plists. */
    const val PROTOCOL_VERSION = "1.1"
}
