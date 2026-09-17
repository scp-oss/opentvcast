/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.airplay

import tv.opentvcast.util.Logger

/**
 * The SET_PARAMETER/GET_PARAMETER pair from the audio-only RAOP path: volume,
 * album artwork, and DMAP now-playing metadata.
 *
 * macOS queries `volume` during setup and aborts if it gets no value back, so
 * the last value the sender set is echoed from [getParameter] — that little bit
 * of remembered state is the whole reason this class exists as a stateful
 * object rather than two routing lines in [RtspHandler].
 */
internal class RtspNowPlayingControl(
    private val onVolume: (Float) -> Unit,
    private val onArtwork: (ByteArray) -> Unit,
    private val onNowPlayingMetadata: (title: String?, artist: String?, album: String?) -> Unit,
) {

    /** Last volume the sender set (AirPlay dB); returned to GET_PARAMETER volume queries. */
    @Volatile
    private var currentVolume: Float = 0f

    /** GET_PARAMETER — macOS polls `volume`; anything else is answered with an empty 200. */
    fun getParameter(request: RtspRequest): RtspResponse {
        val query = request.body.trim()
        Logger.i("GET_PARAMETER body='$query'")
        return if (query.startsWith("volume")) {
            RtspResponse(
                statusCode = 200, statusMessage = "OK",
                body = "volume: %.6f\r\n".format(currentVolume),
                contentType = "text/parameters",
                protocol = request.responseProtocol(),
            )
        } else {
            RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
        }
    }

    /** SET_PARAMETER — text bodies carry volume; binary bodies carry artwork or DMAP metadata. */
    fun setParameter(request: RtspRequest): RtspResponse {
        val body = request.body
        val contentType = request.headers["Content-Type"]?.lowercase() ?: ""
        when {
            body.startsWith("volume") -> {
                body.substringAfter(":").trim().toFloatOrNull()?.let { v ->
                    currentVolume = v
                    onVolume(v)
                    Logger.d("SET_PARAMETER volume=$v")
                }
            }
            contentType.startsWith("image/") -> {
                // Album artwork (image/jpeg, image/png). A zero-length body clears it.
                onArtwork(request.bodyBytes)
                Logger.i("SET_PARAMETER artwork (${request.bodyBytes.size}B, $contentType)")
            }
            contentType.contains("dmap") || looksLikeDmap(request.bodyBytes) -> {
                val meta = DmapParser.parseNowPlaying(request.bodyBytes)
                onNowPlayingMetadata(meta.title, meta.artist, meta.album)
                Logger.i("SET_PARAMETER now-playing: title='${meta.title}' artist='${meta.artist}' album='${meta.album}'")
            }
            else -> Logger.d("SET_PARAMETER (${request.bodyBytes.size}B, $contentType, unhandled)")
        }
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    companion object {
        /** Heuristic: a DMAP body starts with the `mlit` listing-item container tag. */
        internal fun looksLikeDmap(body: ByteArray): Boolean =
            body.size >= 8 && String(body, 0, 4, Charsets.US_ASCII) == "mlit"
    }
}
