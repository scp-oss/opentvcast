package tv.opentvcast.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import tv.opentvcast.airplay.media.SpsParser
import tv.opentvcast.util.Logger

/**
 * VideoDecoder — Hardware H.264 video decoder using Android's MediaCodec API.
 *
 * WHY: AirPlay screen mirroring sends video as a stream of H.264-encoded frames.
 * To display these frames on screen, we need to decode the H.264 bitstream.
 * MediaCodec is Android's API to the hardware video decoder (GPU), which is
 * much faster and more power-efficient than any software decoder.
 *
 * HOW: Initialize with a [Surface] (from StreamingScreen) and the codec parameters
 * from the SDP (received in the RTSP ANNOUNCE). Then call [decodeNalUnit] for each
 * video chunk received from the RTP stream. MediaCodec outputs decoded frames directly
 * to the Surface — no intermediate buffer copies.
 *
 * AirPlay video data flow:
 *   RTP packet → RtspHandler strips RTP header → NAL unit bytes → VideoDecoder.decodeNalUnit()
 *   → MediaCodec input buffer → GPU hardware decode → Surface (displayed on TV)
 *
 * Example:
 *   val decoder = VideoDecoder(surface)
 *   decoder.initialize(spsBytes, ppsBytes, width, height)  // call once with SDP params
 *   decoder.decodeNalUnit(nalUnitBytes)                    // call for each video chunk
 *   decoder.release()                                       // call when done
 */
class VideoDecoder(private val outputSurface: Surface) {

    // The underlying hardware decoder — null until initialize() is called
    private var mediaCodec: MediaCodec? = null

    // Track whether the decoder has been initialized (to prevent double-init)
    @Volatile
    private var isInitialized = false

    /**
     * False once MediaCodec has thrown (entered an unrecoverable error state). The caller should
     * drop this decoder and create a fresh one — error state cannot be cleared by reconfigure.
     */
    @Volatile
    var isHealthy = true
        private set

    /**
     * Initializes the MediaCodec decoder with the video stream parameters from the SDP.
     *
     * This must be called ONCE before any calls to [decodeNalUnit].
     * The parameters (SPS, PPS, width, height) come from the SDP body of the
     * RTSP ANNOUNCE message.
     *
     * What is SPS/PPS?
     *   H.264 requires two special "configuration" NAL units before the first frame:
     *   - SPS (Sequence Parameter Set): describes the video resolution, profile, level
     *   - PPS (Picture Parameter Set): describes encoding parameters for each frame
     *   MediaCodec needs these to configure the hardware decoder correctly.
     *
     * RULE 5: If initialization fails, the exception propagates to the caller
     * (RtspHandler) which will handle it gracefully (log + return to WAITING state).
     *
     * @param spsBytes  The SPS NAL unit bytes (from SDP "sprop-parameter-sets" field)
     * @param ppsBytes  The PPS NAL unit bytes (from SDP "sprop-parameter-sets" field)
     * @param width     Video width in pixels (from SDP)
     * @param height    Video height in pixels (from SDP)
     */
    fun initialize(spsBytes: ByteArray, ppsBytes: ByteArray, width: Int, height: Int) {
        if (isInitialized) {
            Logger.w("VideoDecoder.initialize() called twice — ignoring second call")
            return
        }

        // Try to extract actual resolution from the SPS NAL unit. The parser can misread some senders'
        // SPS (e.g. iPhone) and return nonsense like 32x87392 — configuring MediaCodec with that wedges
        // the decoder (no input buffers, no frames). Validate the result and fall back to the hint; the
        // hardware decoder reads the true size from the SPS (csd-0) itself and reports it via
        // INFO_OUTPUT_FORMAT_CHANGED, which we use to refine the size for aspect-fit.
        val parsed = Companion.parseSpsResolution(spsBytes)
        val (actualWidth, actualHeight) = parsed?.takeIf { isPlausibleSize(it.first, it.second) } ?: run {
            Logger.w("SPS resolution $parsed implausible/failed — using hint ${width}x${height}")
            Pair(width, height)
        }

        Logger.i("Initializing H.264 decoder: ${actualWidth}x${actualHeight} " +
                 "(hint was ${width}x${height})")

        // Provisional size for StreamingScreen aspect-fit; replaced by the decoder's reported size.
        StreamStats.videoWidth = actualWidth
        StreamStats.videoHeight = actualHeight

        // Create the MediaFormat that describes the H.264 stream to the hardware decoder
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,  // AVC = Advanced Video Coding = H.264
            actualWidth,
            actualHeight
        ).apply {
            // Provide SPS and PPS so MediaCodec can configure the hardware decoder.
            // These are wrapped in ByteBuffers as required by the MediaCodec API.
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(spsBytes))  // SPS
            setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(ppsBytes))  // PPS
            // NOTE: do NOT set KEY_MAX_WIDTH/HEIGHT here — this SoC's MStar decoder rejects
            // adaptive playback (BadParameter / buffer-count failures) and produces banding.
            // Resolution changes are handled by recreating the decoder in MirrorStreamServer.
        }

        // Create the hardware H.264 decoder.
        // "video/avc" is the MIME type for H.264. Android will pick the best
        // available hardware decoder for this format.
        mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

        // Configure the decoder:
        // - format: what the input will look like (H.264, width, height, SPS/PPS)
        // - outputSurface: where decoded frames go (directly to screen — no intermediate copy)
        // - crypto: null (we handle decryption before this point, if needed)
        // - flags: 0 (0 = decoder mode; CONFIGURE_FLAG_ENCODE would be for encoding)
        mediaCodec!!.configure(format, outputSurface, null, 0)
        mediaCodec!!.start()

        isInitialized = true
        Logger.i("H.264 decoder initialized successfully")
    }

    /**
     * Decodes a single H.264 NAL unit and sends it to the display.
     *
     * A NAL unit (Network Abstraction Layer Unit) is the basic building block of H.264.
     * Each RTP packet from AirPlay contains one or more NAL units.
     * Some NAL units are full frames (IDR frames), others are partial updates.
     *
     * PERFORMANCE: This method runs on the IO coroutine dispatcher (network thread).
     * MediaCodec handles the actual decoding on its own internal thread.
     * The decoded frame appears on the Surface without any UI thread involvement.
     *
     * SECURITY: The caller (RtspHandler) is responsible for validating the byte array
     * length before passing it here.
     *
     * @param nalUnit The raw NAL unit bytes (without the RTP header).
     * @param presentationTimeUs Presentation timestamp in microseconds (for A/V sync).
     */
    fun decodeNalUnit(nalUnit: ByteArray, presentationTimeUs: Long) {
        val codec = mediaCodec ?: run {
            Logger.w("decodeNalUnit() called but decoder not initialized")
            return
        }

        try {
            // Drain finished frames FIRST — renders them and frees the pipeline so an input
            // buffer becomes available. Dropping NAL units corrupts H.264 (loses reference
            // frames) and causes a black screen until the next keyframe, so we avoid it.
            releaseOutputBuffers(codec)

            // Wait for an input buffer. Longer than before: on a modest SoC the decoder can
            // briefly fall behind, and waiting beats dropping (which corrupts the stream).
            val inputBufferIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_US)

            if (inputBufferIndex >= 0) {
                // We got an input buffer — fill it with the NAL unit bytes
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                inputBuffer.clear()
                inputBuffer.put(nalUnit)

                // Tell MediaCodec: "input buffer [index] is filled with [size] bytes
                // of data with timestamp [presentationTimeUs] — please decode it"
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,                 // offset: start from beginning of buffer
                    nalUnit.size,      // size: how many bytes to decode
                    presentationTimeUs,
                    0                  // flags: 0 = normal frame (not end-of-stream)
                )
            } else {
                // No input buffer available — the decoder is catching up.
                // Drop this NAL unit to avoid building up backlog (prefer low latency).
                Logger.v("VideoDecoder: no input buffer available, dropping NAL unit")
            }

            // Release any output buffers that MediaCodec has finished decoding.
            // render=true means the frame goes to the Surface immediately.
            releaseOutputBuffers(codec)

        } catch (e: IllegalStateException) {
            // MediaCodec is now in the error state and cannot recover — flag for recreation.
            Logger.e("VideoDecoder entered error state — will recreate", e)
            isHealthy = false
        } catch (e: Exception) {
            Logger.e("Error decoding NAL unit", e)
        }
    }

    /**
     * Releases any decoded output buffers back to MediaCodec and renders them to the Surface.
     *
     * MediaCodec works asynchronously: we put encoded data in input buffers,
     * and decoded frames appear in output buffers. We must release each output
     * buffer back to MediaCodec after rendering, or we'll run out of buffers.
     *
     * render=true: the frame is rendered to the Surface (displayed on TV).
     * render=false: the frame is discarded (used to flush without displaying).
     *
     * @param codec The active MediaCodec instance.
     */
    private fun releaseOutputBuffers(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)

        while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The decoder parsed the real size from the SPS — authoritative for aspect-fit.
                publishOutputSize(codec.outputFormat)
            } else {
                // Render immediately. We deliberately do NOT schedule a future render time for A/V sync:
                // this Surface's BufferQueue holds only ~3 frames, so any hold quickly back-pressures the
                // decoder → the upstream frame queue saturates → big latency + dropped (corrupt) frames.
                // A/V alignment is handled by keeping the AUDIO path low-latency instead (AudioStreamServer).
                codec.releaseOutputBuffer(outputBufferIndex, true)
            }
            outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    /** Reads the decoder's true display size (honouring the crop rectangle) for StreamingScreen. */
    private fun publishOutputSize(format: MediaFormat) {
        var w = format.getInteger(MediaFormat.KEY_WIDTH)
        var h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        if (isPlausibleSize(w, h)) {
            StreamStats.videoWidth = w
            StreamStats.videoHeight = h
            Logger.i("Video output size ${w}x$h")
        }
    }

    /**
     * Releases all MediaCodec resources.
     *
     * MUST be called when streaming ends (from AirPlayReceiver.onStreamingStopped()
     * or from onDestroy()). Failing to release MediaCodec causes:
     * - Memory leaks (codec buffers are GPU memory, a scarce resource)
     * - The hardware decoder being unavailable to other apps
     *
     * After release(), call initialize() again before using the decoder.
     */
    fun release() {
        Logger.d("Releasing VideoDecoder")
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing MediaCodec (non-fatal)", e)
        } finally {
            mediaCodec = null
            isInitialized = false
        }
    }

    /** True if (w, h) look like a real video resolution — rejects parser garbage (e.g. 32x87392). */
    private fun isPlausibleSize(w: Int, h: Int): Boolean = w in 64..8192 && h in 64..8192

    companion object {
        // How long to wait for an input buffer before dropping (microseconds).
        // 100ms — generous enough that the decoder rarely has to drop a NAL unit (which would
        // corrupt the stream), while still bounding stall if the codec is truly wedged.
        private const val INPUT_BUFFER_TIMEOUT_US = 100_000L

        /**
         * Extracts the video resolution from an SPS NAL unit.
         *
         * Delegates to [SpsParser] rather than implementing the parse inline.
         * The algorithm is pure bit manipulation with no Android dependency, so
         * keeping it in this file forced the test runner to compile a *duplicate*
         * of it — and that duplicate, not this code, was what the unit tests
         * exercised. See SpsParser's KDoc.
         *
         * @param sps raw SPS NAL bytes, first byte being the NAL header (0x67).
         * @return (width, height), or null when the unit cannot be parsed.
         */
        internal fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? =
            SpsParser.parseResolution(sps)

    }
}
