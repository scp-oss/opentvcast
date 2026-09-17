package tv.opentvcast.airplay

/**
 * Compile-time stand-in for [tv.opentvcast.airplay.VideoDecoder].
 *
 * The real decoder drives `android.media.MediaCodec` against a
 * `android.view.Surface`, neither of which can execute on a desktop JVM, so the
 * test runner excludes it from compilation. Other classes in this package
 * (`AirPlayReceiver`, `MirrorStreamServer`) still reference it, so this stub
 * exists purely to satisfy them.
 *
 * IMPORTANT: this stub must NOT reimplement any logic. An earlier version
 * duplicated the SPS resolution parser, which meant `VideoDecoderSpsTest`
 * asserted against the copy in here rather than against production code —
 * a change to the real parser could not fail the suite. The parser now lives in
 * [tv.opentvcast.airplay.media.SpsParser] and is compiled for real; the test was
 * renamed to `SpsParserTest` and targets it directly.
 *
 * Keep this file behaviour-free.
 */
@Suppress("UNUSED_PARAMETER")
class VideoDecoder(private val outputSurface: Any?) {

    /** Mirrors the production flag used by MirrorStreamServer for self-heal. */
    var isHealthy = true

    fun initialize(spsBytes: ByteArray, ppsBytes: ByteArray, width: Int, height: Int) = Unit

    fun decodeNalUnit(nalUnit: ByteArray, presentationTimeUs: Long = 0L) = Unit

    fun release() = Unit
}
