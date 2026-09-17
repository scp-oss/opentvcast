/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.dlna.soap

import tv.opentvcast.dlna.ssdp.DlnaService
import tv.opentvcast.dlna.ssdp.DlnaServices

/**
 * The player a DLNA transport drives.
 *
 * A port rather than a concrete `MediaPlayer` so the whole transport state
 * machine is testable without Android, and so the Android-specific part stays a
 * thin adapter — the same reason `ReceiverEnvironment` exists in `:core`.
 */
interface DlnaPlayerPort {
    val positionMs: Long
    val durationMs: Long

    /** Starts [uri] from the beginning. */
    fun play(uri: String)

    /** Continues a paused item without reloading it. */
    fun resume()

    fun pause()

    fun stop()

    fun seek(targetMs: Long)

    /** Releases whatever the implementation holds (MediaPlayer, surface lease). */
    fun release()
}

/** UPnP `TransportState` values we can report. */
enum class TransportPhase { STOPPED, PLAYING, PAUSED, TRANSITIONING }

/**
 * The renderer's mutable state: what is loaded, where playback is, and the
 * volume the user's control point last set.
 *
 * Volume is a renderer property, not a per-session one: DLNA control points set
 * it once at the start and expect it to survive across items.
 */
class DlnaRendererState {
    var currentUri: String? = null
        internal set
    var currentUriMetaData: String? = null
        internal set
    var phase: TransportPhase = TransportPhase.STOPPED
        internal set
    var volume: Int = DEFAULT_VOLUME
        internal set
    var muted: Boolean = false
        internal set

    companion object {
        const val DEFAULT_VOLUME = 50
    }
}

/**
 * Dispatches UPnP SOAP actions onto the renderer (FR-34) and owns the transport
 * state machine (FR-36).
 *
 * Why the state machine lives here rather than in the player: a control point
 * drives us through `SetAVTransportURI` → `Play` → `Pause` → `Stop`, and its
 * idea of legality is UPnP's, not MediaPlayer's. Refusing a `Play` that arrives
 * before a URI is loaded makes the sender report "cannot play to this device";
 * refusing a *second* `Pause` (which senders re-send after losing track of our
 * state) breaks playback that was fine. The distinction is in what we answer,
 * and only this class knows it.
 *
 * Every reply is a SOAP envelope — including faults — so the caller's only job
 * is to put it on the socket with the right status code.
 */
class DlnaControlDispatcher(
    private val state: DlnaRendererState,
    private val player: DlnaPlayerPort,
) {

    fun dispatch(service: DlnaService, action: String, args: Map<String, String>): String {
        // InstanceID is present on nearly every AVTransport/RenderingControl
        // call and must be 0 — we expose exactly one renderer instance.
        if (service != DlnaServices.connectionManager) {
            val instanceId = args["InstanceID"]
            if (instanceId != null && instanceId.toIntOrNull() != 0) {
                return SoapResponse.fault(SoapResponse.ERROR_INVALID_ARGS, "Invalid InstanceID")
            }
        }

        return when (service.serviceType) {
            DlnaServices.avTransport.serviceType -> dispatchAvTransport(action, args)
            DlnaServices.renderingControl.serviceType -> dispatchRenderingControl(action, args)
            DlnaServices.connectionManager.serviceType -> dispatchConnectionManager(action)
            else -> invalidAction(action)
        }
    }

    private fun dispatchAvTransport(action: String, args: Map<String, String>): String {
        return when (action) {
        "SetAVTransportURI" -> {
            state.currentUri = args["CurrentURI"]
            state.currentUriMetaData = args["CurrentURIMetaData"]
            state.phase = TransportPhase.STOPPED
            SoapResponse.success(DlnaServices.avTransport.serviceType, action)
        }

        "Play" -> when (state.phase) {
            TransportPhase.PLAYING -> SoapResponse.success(DlnaServices.avTransport.serviceType, action)
            TransportPhase.PAUSED -> {
                player.resume()
                state.phase = TransportPhase.PLAYING
                SoapResponse.success(DlnaServices.avTransport.serviceType, action)
            }
            TransportPhase.STOPPED -> {
                val uri = state.currentUri
                if (uri == null) {
                    transitionUnavailable(action)
                } else {
                    player.play(uri)
                    state.phase = TransportPhase.PLAYING
                    SoapResponse.success(DlnaServices.avTransport.serviceType, action)
                }
            }
            TransportPhase.TRANSITIONING -> transitionUnavailable(action)
        }

        "Pause" -> when (state.phase) {
            TransportPhase.PLAYING -> {
                player.pause()
                state.phase = TransportPhase.PAUSED
                SoapResponse.success(DlnaServices.avTransport.serviceType, action)
            }
            // Re-sent Pause is harmless: senders use it to resynchronise.
            TransportPhase.PAUSED -> SoapResponse.success(DlnaServices.avTransport.serviceType, action)
            else -> transitionUnavailable(action)
        }

        "Stop" -> {
            player.stop()
            state.phase = TransportPhase.STOPPED
            SoapResponse.success(DlnaServices.avTransport.serviceType, action)
        }

        "Seek" -> {
            if (state.phase != TransportPhase.PLAYING && state.phase != TransportPhase.PAUSED) {
                transitionUnavailable(action)
            } else {
                val targetMs = parseClock(args["Target"])
                if (targetMs == null) {
                    SoapResponse.fault(SoapResponse.ERROR_INVALID_ARGS, "Unparseable seek target")
                } else {
                    player.seek(targetMs)
                    SoapResponse.success(DlnaServices.avTransport.serviceType, action)
                }
            }
        }

        "GetPositionInfo" -> SoapResponse.success(
            DlnaServices.avTransport.serviceType,
            action,
            mapOf(
                "Track" to "1",
                "TrackDuration" to formatClock(player.durationMs),
                "TrackMetaData" to (state.currentUriMetaData ?: ""),
                "TrackURI" to (state.currentUri ?: ""),
                "RelTime" to formatClock(player.positionMs),
                "AbsTime" to formatClock(player.positionMs),
                "RelCount" to "2147483647",
                "AbsCount" to "2147483647",
            ),
        )

        "GetTransportInfo" -> SoapResponse.success(
            DlnaServices.avTransport.serviceType,
            action,
            mapOf(
                "CurrentTransportState" to state.phase.name,
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to "1",
            ),
        )

        "GetMediaInfo" -> SoapResponse.success(
            DlnaServices.avTransport.serviceType,
            action,
            mapOf(
                "NrTracks" to "1",
                "MediaDuration" to formatClock(player.durationMs),
                "CurrentURI" to (state.currentUri ?: ""),
                "CurrentURIMetaData" to (state.currentUriMetaData ?: ""),
                "PlayMedium" to "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED",
            ),
        )

        "GetCurrentTransportActions" -> SoapResponse.success(
            DlnaServices.avTransport.serviceType,
            action,
            mapOf("Actions" to "Play,Pause,Stop,Seek"),
        )

        else -> invalidAction(action)
        }
    }

    private fun dispatchRenderingControl(action: String, args: Map<String, String>): String {
        return when (action) {
        "SetVolume" -> {
            val requested = args["DesiredVolume"]?.toIntOrNull()
            if (requested == null) {
                SoapResponse.fault(SoapResponse.ERROR_INVALID_ARGS, "DesiredVolume must be a number")
            } else {
                state.volume = requested.coerceIn(0, 100)
                SoapResponse.success(DlnaServices.renderingControl.serviceType, action)
            }
        }

        "GetVolume" -> SoapResponse.success(
            DlnaServices.renderingControl.serviceType,
            action,
            mapOf("CurrentVolume" to state.volume.toString()),
        )

        "SetMute" -> {
            state.muted = args["DesiredMute"]?.trim()?.equals("true", ignoreCase = true) == true ||
                args["DesiredMute"]?.trim() == "1"
            SoapResponse.success(DlnaServices.renderingControl.serviceType, action)
        }

        "GetMute" -> SoapResponse.success(
            DlnaServices.renderingControl.serviceType,
            action,
            mapOf("CurrentMute" to if (state.muted) "1" else "0"),
        )

        else -> invalidAction(action)
        }
    }

    private fun dispatchConnectionManager(action: String): String {
        return when (action) {
        "GetProtocolInfo" -> SoapResponse.success(
            DlnaServices.connectionManager.serviceType,
            action,
            mapOf(
                "Source" to "",
                // Only what the player can actually consume. Claiming more makes
                // senders push formats we then fail to open.
                "Sink" to "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*",
            ),
        )

        "GetCurrentConnectionIDs" -> SoapResponse.success(
            DlnaServices.connectionManager.serviceType,
            action,
            mapOf("ConnectionIDs" to "0"),
        )

        "GetCurrentConnectionInfo" -> SoapResponse.success(
            DlnaServices.connectionManager.serviceType,
            action,
            mapOf(
                "RcsID" to "-1",
                "AVTransportID" to "0",
                "ProtocolInfo" to "http-get:*:video/mp4:*",
                "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1",
                "Direction" to "Input",
                "Status" to "OK",
            ),
        )

        else -> invalidAction(action)
        }
    }

    private fun invalidAction(action: String) =
        SoapResponse.fault(SoapResponse.ERROR_INVALID_ACTION, "Invalid Action: $action")

    private fun transitionUnavailable(action: String) =
        SoapResponse.fault(SoapResponse.ERROR_TRANSITION_NOT_AVAILABLE, "$action not available in ${state.phase}")

    companion object {
        /** Parses UPnP's `H+:MM:SS[.F+]` clock into milliseconds. */
        fun parseClock(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            val parts = value.split(':')
            if (parts.isEmpty() || parts.size > 3) return null
            return try {
                var total = 0L
                for (part in parts) {
                    val seconds = part.toDoubleOrNull() ?: return null
                    if (seconds < 0) return null
                    total = total * 60 + (seconds * 1000).toLong()
                }
                total
            } catch (e: NumberFormatException) {
                null
            }
        }

        /** Formats milliseconds as UPnP's `H+:MM:SS`. Negative or unknown becomes 0. */
        fun formatClock(millis: Long): String {
            val safe = millis.coerceAtLeast(0L)
            val totalSeconds = safe / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return "%02d:%02d:%02d".format(hours, minutes, seconds)
        }
    }
}
