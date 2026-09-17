/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.dlna.ssdp

/** Whether an argument is passed to us (`in`) or returned by us (`out`). */
enum class ArgumentDirection(val wire: String) {
    IN("in"),
    OUT("out"),
}

/** One argument of a UPnP action. */
data class DlnaArgument(
    val name: String,
    val direction: ArgumentDirection,
    /** Must be declared in the service state table — control points validate this. */
    val relatedStateVariable: String,
)

/** One UPnP action (a SOAP call a control point can make). */
data class DlnaAction(
    val name: String,
    val arguments: List<DlnaArgument> = emptyList(),
)

/** A state variable in a service's `serviceStateTable`. */
data class DlnaStateVariable(
    val name: String,
    val dataType: String,
    /** Evented variables are the ones a control point can subscribe to (GENA). */
    val sendsEvents: Boolean = false,
)

/** Argument names that recur across services; kept as constants so they cannot drift. */
object Arg {
    const val INSTANCE_ID = "InstanceID"
    const val A_ARG_INSTANCE_ID = "A_ARG_TYPE_InstanceID"
    const val CHANNEL = "Channel"
    const val A_ARG_CHANNEL = "A_ARG_TYPE_Channel"
}

/** A UPnP service we expose, with its URLs and its contract. */
data class DlnaService(
    val serviceType: String,
    val serviceId: String,
    /** Path (not URL) — the host and port come from the granted port at build time. */
    val scpdPath: String,
    val controlPath: String,
    val eventPath: String,
    val actions: List<DlnaAction>,
    val stateVariables: List<DlnaStateVariable>,
)

/**
 * The three services a MediaRenderer must expose (FR-34), plus the `LastChange`
 * variable each evented service needs for GENA (FR-35).
 *
 * Nothing here touches Android or a socket: both documents are pure functions
 * of (identity, granted port), which is why the interesting behaviour is
 * testable without a device.
 */
object DlnaServices {

    val avTransport = DlnaService(
        serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
        serviceId = "urn:upnp-org:serviceId:AVTransport",
        scpdPath = "/scpd/AVTransport.xml",
        controlPath = "/ctl/AVTransport",
        eventPath = "/evt/AVTransport",
        actions = listOf(
            DlnaAction(
                "SetAVTransportURI",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument("CurrentURI", ArgumentDirection.IN, "AVTransportURI"),
                    DlnaArgument("CurrentURIMetaData", ArgumentDirection.IN, "AVTransportURIMetaData"),
                ),
            ),
            DlnaAction(
                "Play",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument("Speed", ArgumentDirection.IN, "TransportPlaySpeed"),
                ),
            ),
            DlnaAction("Pause", listOf(DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID))),
            DlnaAction("Stop", listOf(DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID))),
            DlnaAction(
                "Seek",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument("Unit", ArgumentDirection.IN, "A_ARG_TYPE_SeekMode"),
                    DlnaArgument("Target", ArgumentDirection.IN, "A_ARG_TYPE_SeekTarget"),
                ),
            ),
            DlnaAction(
                "GetPositionInfo",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument("Track", ArgumentDirection.OUT, "CurrentTrack"),
                    DlnaArgument("TrackDuration", ArgumentDirection.OUT, "CurrentTrackDuration"),
                    DlnaArgument("TrackMetaData", ArgumentDirection.OUT, "CurrentTrackMetaData"),
                    DlnaArgument("TrackURI", ArgumentDirection.OUT, "CurrentTrackURI"),
                    DlnaArgument("RelTime", ArgumentDirection.OUT, "RelativeTimePosition"),
                    DlnaArgument("AbsTime", ArgumentDirection.OUT, "AbsoluteTimePosition"),
                    DlnaArgument("RelCount", ArgumentDirection.OUT, "RelativeCounterPosition"),
                    DlnaArgument("AbsCount", ArgumentDirection.OUT, "AbsoluteCounterPosition"),
                ),
            ),
            DlnaAction(
                "GetTransportInfo",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument("CurrentTransportState", ArgumentDirection.OUT, "TransportState"),
                    DlnaArgument("CurrentTransportStatus", ArgumentDirection.OUT, "TransportStatus"),
                    DlnaArgument("CurrentSpeed", ArgumentDirection.OUT, "TransportPlaySpeed"),
                ),
            ),
            DlnaAction(
                "GetMediaInfo",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument("NrTracks", ArgumentDirection.OUT, "NumberOfTracks"),
                    DlnaArgument("MediaDuration", ArgumentDirection.OUT, "CurrentMediaDuration"),
                    DlnaArgument("CurrentURI", ArgumentDirection.OUT, "AVTransportURI"),
                    DlnaArgument("CurrentURIMetaData", ArgumentDirection.OUT, "AVTransportURIMetaData"),
                    DlnaArgument("PlayMedium", ArgumentDirection.OUT, "PlaybackStorageMedium"),
                    DlnaArgument("RecordMedium", ArgumentDirection.OUT, "RecordStorageMedium"),
                    DlnaArgument("WriteStatus", ArgumentDirection.OUT, "RecordMediumWriteStatus"),
                ),
            ),
            DlnaAction(
                "GetCurrentTransportActions",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument("Actions", ArgumentDirection.OUT, "CurrentTransportActions"),
                ),
            ),
        ),
        stateVariables = listOf(
            DlnaStateVariable("TransportState", "string", sendsEvents = true),
            DlnaStateVariable("TransportStatus", "string"),
            DlnaStateVariable("TransportPlaySpeed", "string"),
            DlnaStateVariable("CurrentTransportActions", "string"),
            DlnaStateVariable("AVTransportURI", "string"),
            DlnaStateVariable("AVTransportURIMetaData", "string"),
            DlnaStateVariable("CurrentTrackURI", "string"),
            DlnaStateVariable("CurrentTrackMetaData", "string"),
            DlnaStateVariable("CurrentTrack", "ui4"),
            DlnaStateVariable("CurrentTrackDuration", "string"),
            DlnaStateVariable("CurrentMediaDuration", "string"),
            DlnaStateVariable("NumberOfTracks", "ui4"),
            DlnaStateVariable("RelativeTimePosition", "string"),
            DlnaStateVariable("AbsoluteTimePosition", "string"),
            DlnaStateVariable("RelativeCounterPosition", "i4"),
            DlnaStateVariable("AbsoluteCounterPosition", "i4"),
            DlnaStateVariable("PlaybackStorageMedium", "string"),
            DlnaStateVariable("RecordStorageMedium", "string"),
            DlnaStateVariable("RecordMediumWriteStatus", "string"),
            DlnaStateVariable(Arg.A_ARG_INSTANCE_ID, "ui4"),
            DlnaStateVariable("A_ARG_TYPE_SeekMode", "string"),
            DlnaStateVariable("A_ARG_TYPE_SeekTarget", "string"),
            DlnaStateVariable("LastChange", "string", sendsEvents = true),
        ),
    )

    val renderingControl = DlnaService(
        serviceType = "urn:schemas-upnp-org:service:RenderingControl:1",
        serviceId = "urn:upnp-org:serviceId:RenderingControl",
        scpdPath = "/scpd/RenderingControl.xml",
        controlPath = "/ctl/RenderingControl",
        eventPath = "/evt/RenderingControl",
        actions = listOf(
            DlnaAction(
                "SetVolume",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument(Arg.CHANNEL, ArgumentDirection.IN, Arg.A_ARG_CHANNEL),
                    DlnaArgument("DesiredVolume", ArgumentDirection.IN, "Volume"),
                ),
            ),
            DlnaAction(
                "GetVolume",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument(Arg.CHANNEL, ArgumentDirection.IN, Arg.A_ARG_CHANNEL),
                    DlnaArgument("CurrentVolume", ArgumentDirection.OUT, "Volume"),
                ),
            ),
            DlnaAction(
                "SetMute",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument(Arg.CHANNEL, ArgumentDirection.IN, Arg.A_ARG_CHANNEL),
                    DlnaArgument("DesiredMute", ArgumentDirection.IN, "Mute"),
                ),
            ),
            DlnaAction(
                "GetMute",
                listOf(
                    DlnaArgument(Arg.INSTANCE_ID, ArgumentDirection.IN, Arg.A_ARG_INSTANCE_ID),
                    DlnaArgument(Arg.CHANNEL, ArgumentDirection.IN, Arg.A_ARG_CHANNEL),
                    DlnaArgument("CurrentMute", ArgumentDirection.OUT, "Mute"),
                ),
            ),
        ),
        stateVariables = listOf(
            DlnaStateVariable("Volume", "ui2", sendsEvents = true),
            DlnaStateVariable("Mute", "boolean", sendsEvents = true),
            DlnaStateVariable(Arg.A_ARG_CHANNEL, "string"),
            DlnaStateVariable(Arg.A_ARG_INSTANCE_ID, "ui4"),
            DlnaStateVariable("LastChange", "string", sendsEvents = true),
        ),
    )

    val connectionManager = DlnaService(
        serviceType = "urn:schemas-upnp-org:service:ConnectionManager:1",
        serviceId = "urn:upnp-org:serviceId:ConnectionManager",
        scpdPath = "/scpd/ConnectionManager.xml",
        controlPath = "/ctl/ConnectionManager",
        eventPath = "/evt/ConnectionManager",
        actions = listOf(
            DlnaAction(
                "GetProtocolInfo",
                listOf(
                    DlnaArgument("Source", ArgumentDirection.OUT, "SourceProtocolInfo"),
                    DlnaArgument("Sink", ArgumentDirection.OUT, "SinkProtocolInfo"),
                ),
            ),
            DlnaAction(
                "GetCurrentConnectionIDs",
                listOf(DlnaArgument("ConnectionIDs", ArgumentDirection.OUT, "CurrentConnectionIDs")),
            ),
            DlnaAction(
                "GetCurrentConnectionInfo",
                listOf(
                    DlnaArgument("ConnectionID", ArgumentDirection.IN, "A_ARG_TYPE_ConnectionID"),
                    DlnaArgument("RcsID", ArgumentDirection.OUT, "A_ARG_TYPE_RcsID"),
                    DlnaArgument("AVTransportID", ArgumentDirection.OUT, "A_ARG_TYPE_AVTransportID"),
                    DlnaArgument("ProtocolInfo", ArgumentDirection.OUT, "A_ARG_TYPE_ProtocolInfo"),
                    DlnaArgument("PeerConnectionManager", ArgumentDirection.OUT, "A_ARG_TYPE_ConnectionManager"),
                    DlnaArgument("PeerConnectionID", ArgumentDirection.OUT, "A_ARG_TYPE_ConnectionID"),
                    DlnaArgument("Direction", ArgumentDirection.OUT, "A_ARG_TYPE_Direction"),
                    DlnaArgument("Status", ArgumentDirection.OUT, "A_ARG_TYPE_ConnectionStatus"),
                ),
            ),
        ),
        stateVariables = listOf(
            DlnaStateVariable("SourceProtocolInfo", "string", sendsEvents = true),
            DlnaStateVariable("SinkProtocolInfo", "string", sendsEvents = true),
            DlnaStateVariable("CurrentConnectionIDs", "string", sendsEvents = true),
            DlnaStateVariable("A_ARG_TYPE_ConnectionID", "i4"),
            DlnaStateVariable("A_ARG_TYPE_RcsID", "i4"),
            DlnaStateVariable("A_ARG_TYPE_AVTransportID", "i4"),
            DlnaStateVariable("A_ARG_TYPE_ProtocolInfo", "string"),
            DlnaStateVariable("A_ARG_TYPE_ConnectionManager", "string"),
            DlnaStateVariable("A_ARG_TYPE_Direction", "string"),
            DlnaStateVariable("A_ARG_TYPE_ConnectionStatus", "string"),
        ),
    )

    val all = listOf(avTransport, renderingControl, connectionManager)

    /** Looks a service up by control path — what the HTTP layer needs. */
    fun byControlPath(path: String): DlnaService? = all.firstOrNull { it.controlPath == path }

    /** Looks a service up by SCPD path. */
    fun byScpdPath(path: String): DlnaService? = all.firstOrNull { it.scpdPath == path }
}
