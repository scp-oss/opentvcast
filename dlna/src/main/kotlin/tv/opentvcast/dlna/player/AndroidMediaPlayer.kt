/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.dlna.player

import android.media.MediaPlayer
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.opentvcast.core.surface.SurfaceRequester
import tv.opentvcast.core.surface.SurfaceSink
import tv.opentvcast.core.surface.SurfaceLease
import tv.opentvcast.dlna.soap.DlnaPlayerPort
import tv.opentvcast.util.Logger

/**
 * The Android adapter behind [DlnaPlayerPort]: a `MediaPlayer` rendering onto a
 * surface leased from [SurfaceSink].
 *
 * Everything that can be decided without Android lives in the transport state
 * machine above this class, so this adapter stays mechanical. Two details come
 * from real TV behaviour:
 *
 * - **The surface is leased, never held.** A statically held Surface keeps the
 *   Activity alive and keeps pointing at a destroyed Surface after rotation —
 *   the exact bug `ReceiverEnvironment` exists to prevent.
 * - **`getCurrentPosition()` is only meaningful once playback has started**, so
 *   before that we report 0 rather than a stale or negative value.
 */
class AndroidMediaPlayer(
    private val scope: CoroutineScope,
    private val surfaceSink: SurfaceSink,
) : DlnaPlayerPort {

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var lease: SurfaceLease? = null

    @Volatile
    private var started = false

    override var positionMs: Long = 0L
        get() = if (started) (player?.currentPosition ?: 0).toLong().coerceAtLeast(0L) else 0L

    override var durationMs: Long = -1L
        get() {
            val current = player ?: return -1L
            if (!started) return -1L
            return try {
                current.duration.let { if (it > 0) it.toLong() else -1L }
            } catch (e: Exception) {
                // MediaPlayer throws when asked for a duration it does not have.
                Logger.d("DLNA duration unavailable: ${e.message}")
                -1L
            }
        }

    override fun play(uri: String) {
        releasePlayer()
        started = false
        val mediaPlayer = MediaPlayer()
        val surface = attachSurface(mediaPlayer)
        try {
            mediaPlayer.setDataSource(uri)
            mediaPlayer.setOnPreparedListener { it.start(); started = true }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Logger.e("DLNA playback error what=$what extra=$extra")
                true
            }
            mediaPlayer.setOnCompletionListener { started = false }
            mediaPlayer.prepareAsync()
            player = mediaPlayer
        } catch (e: Exception) {
            Logger.e("DLNA: cannot play $uri", e)
            mediaPlayer.release()
            lease?.close()
            lease = null
            player = null
            return
        }
        Logger.i("DLNA: playing $uri (surface=${surface != null})")
    }

    override fun resume() {
        runCatching { player?.start(); started = true }
            .onFailure { Logger.e("DLNA resume failed", it) }
    }

    override fun pause() {
        runCatching { player?.pause(); started = false }
            .onFailure { Logger.e("DLNA pause failed", it) }
    }

    override fun stop() {
        releasePlayer()
        started = false
        positionMs = 0L
    }

    override fun seek(targetMs: Long) {
        runCatching { player?.seekTo(targetMs.toInt()) }
            .onFailure { Logger.e("DLNA seek failed", it) }
    }

    /** Closes the player and returns the surface lease. */
    override fun release() {
        releasePlayer()
        started = false
    }

    private fun releasePlayer() {
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {
            Logger.d("DLNA player release failed (non-fatal): ${e.message}")
        }
        player = null
        lease?.close()
        lease = null
    }

    /**
     * Acquires a surface for this playback and hands it to the player.
     *
     * `acquire` suspends, so this runs on the receiver's scope; if no surface
     * appears in time we start audio-only rather than refusing to play at all.
     */
    private fun attachSurface(mediaPlayer: MediaPlayer): Surface? {
        var attached: Surface? = null
        scope.launch(Dispatchers.Main) {
            val acquired = try {
                surfaceSink.acquire(SurfaceRequester.DLNA_VIDEO)
            } catch (e: Exception) {
                Logger.w("DLNA: no surface available (${e.message}) — playing without video")
                null
            }
            if (acquired == null) return@launch
            lease = acquired
            val surface = acquired.surface as? Surface
            if (surface != null && acquired.isValid) {
                runCatching { mediaPlayer.setSurface(surface) }
                    .onFailure { Logger.e("DLNA: cannot attach surface", it) }
                attached = surface
            }
        }
        return attached
    }
}
