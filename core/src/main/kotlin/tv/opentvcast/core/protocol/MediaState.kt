/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.core.protocol

/**
 * Playback state, normalised across protocols.
 *
 * AirPlay reports progress by stream position and duration, DLNA reports it as a
 * UPnP `TransportState` plus `GetPositionInfo` values. Each protocol module
 * collapses its own model into this one, which is what lets a single UI render
 * either without a protocol branch.
 */
data class MediaState(
    val transport: TransportState,
    val positionMs: Long,
    /** Total duration, or `-1` when unknown (live stream, or not yet probed). */
    val durationMs: Long,
    val volume: Float,
    val muted: Boolean,
    val meta: MediaMeta? = null,
    /** Video dimensions, or `null` for an audio-only stream. */
    val videoSize: VideoSize? = null,
    /** Whether the stream is seekable. DLNA/fixed-length URL media is; live mirroring is not. */
    val seekable: Boolean = false,
) {
    init {
        require(volume in 0f..1f) { "volume must be within 0..1, was $volume" }
        require(durationMs == -1L || durationMs >= 0L) {
            "durationMs must be -1 (unknown) or non-negative, was $durationMs"
        }
    }
}

enum class TransportState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    ENDED,
}

data class MediaMeta(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Artwork bytes. Kept small — mirrored album art is typically < 64 KB. */
    val artwork: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaMeta) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            artwork.contentEquals(other.artwork)
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        return result
    }
}

data class VideoSize(val width: Int, val height: Int)
