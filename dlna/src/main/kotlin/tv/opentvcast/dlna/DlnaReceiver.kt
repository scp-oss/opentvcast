/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.dlna

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import tv.opentvcast.core.net.ReceiverEnvironment
import tv.opentvcast.core.protocol.CastSession
import tv.opentvcast.core.protocol.MediaState
import tv.opentvcast.core.protocol.ProtocolCapabilities
import tv.opentvcast.core.protocol.ProtocolKind
import tv.opentvcast.core.protocol.ProtocolReceiver
import tv.opentvcast.core.protocol.ProtocolState
import tv.opentvcast.core.protocol.SenderInfo
import tv.opentvcast.core.protocol.SessionId
import tv.opentvcast.core.protocol.SessionPhase
import tv.opentvcast.core.protocol.TerminationReason
import tv.opentvcast.core.protocol.TransportState
import tv.opentvcast.dlna.http.GenaSubscriptionManager
import tv.opentvcast.dlna.http.UpnpHttpServer
import tv.opentvcast.dlna.http.UpnpRouter
import tv.opentvcast.core.surface.SurfaceSink
import tv.opentvcast.dlna.player.AndroidMediaPlayer
import tv.opentvcast.dlna.soap.DlnaControlDispatcher
import tv.opentvcast.dlna.soap.DlnaRendererState
import tv.opentvcast.dlna.soap.TransportPhase
import tv.opentvcast.dlna.soap.DlnaPlayerPort
import tv.opentvcast.dlna.ssdp.DeviceDescription
import tv.opentvcast.dlna.ssdp.SsdpMessage
import tv.opentvcast.dlna.ssdp.SsdpResponder
import tv.opentvcast.util.Logger
import java.util.UUID

/**
 * The DLNA / UPnP AV MediaRenderer (FR-32 … FR-38).
 *
 * Lifecycle follows [ProtocolReceiver]: `start` advertises and begins serving,
 * `stop` sends `ssdp:byebye` and releases both ports.
 *
 * Everything protocol-shaped lives in testable pieces — SSDP matching, the UPnP
 * documents, the SOAP dispatcher, the router — so this class is wiring: ports
 * from the environment, a surface lease for the player, and one state flow the
 * UI renders.
 *
 * Ports are **allocated, not assumed** (FR-20 / FR-33): the port we get is the
 * port we publish in `LOCATION` and in every document URL.
 */
class DlnaReceiver(
    private val udn: String = UUID.randomUUID().toString(),
    /**
     * Builds the player that drives playback. Injected so the receiver's
     * lifecycle is testable without Android: the default is the real
     * `MediaPlayer` adapter, and a test passes a fake.
     *
     * The same reasoning as [DlnaPlayerPort] itself — the receiver should not
     * know how media is rendered, only when to start and stop it.
     */
    private val playerFactory: (CoroutineScope, SurfaceSink) -> DlnaPlayerPort = { scope, sink ->
        AndroidMediaPlayer(scope, sink)
    },
    /** Port to request for HTTP; the allocator may grant a different one. */
    private val preferredHttpPort: Int = DEFAULT_HTTP_PORT,
    /** SSDP socket factory, forwarded so a test can run without multicast. */
    private val ssdpSocketProvider: (() -> java.net.MulticastSocket)? = null,
    private val ssdpGroupJoin: ((java.net.MulticastSocket) -> Unit)? = null,
) : ProtocolReceiver {

    override val capabilities = ProtocolCapabilities(
        kind = ProtocolKind.DLNA,
        displayName = "DLNA",
        supportsVideo = true,
        supportsAudio = true,
        supportsPhoto = false,
        supportsRemoteControl = false,
        preferredPorts = setOf(DEFAULT_HTTP_PORT),
    )

    private val _state = MutableStateFlow(ProtocolState.DISABLED)
    override val state: StateFlow<ProtocolState> = _state.asStateFlow()

    private val _session = MutableStateFlow<CastSession?>(null)
    override val session: StateFlow<CastSession?> = _session.asStateFlow()

    private val rendererState = DlnaRendererState()
    private val subscriptions = GenaSubscriptionManager()

    /** The environment of the last [start]; [stop] needs it to release ports and the lock. */
    private var environment: ReceiverEnvironment? = null

    private var player: DlnaPlayerPort? = null
    private var httpServer: UpnpHttpServer? = null
    private var ssdpResponder: SsdpResponder? = null
    private var httpPort: Int = 0

    override suspend fun start(environment: ReceiverEnvironment) {
        if (_state.value != ProtocolState.DISABLED && _state.value != ProtocolState.ERROR) return
        this.environment = environment
        _state.value = ProtocolState.ADVERTISING

        // One owner tag per concern, so releasing DLNA never drops AirPlay's lock.
        environment.multicastLock.acquire(MULTICAST_OWNER)

        httpPort = environment.allocatePort(preferredHttpPort)
        val address = environment.localAddress.value
        val baseUrl = "http://${address?.hostAddress ?: FALLBACK_HOST}:$httpPort"

        player = playerFactory(environment.scope, environment.surfaceSink)
        val dispatcher = DlnaControlDispatcher(rendererState, player!!)
        val router = UpnpRouter(
            udn = udn,
            friendlyName = environment.displayName,
            baseUrl = baseUrl,
            dispatcher = dispatcher,
            subscriptions = subscriptions,
        )

        httpServer = UpnpHttpServer(environment.scope, httpPort, router) { serviceId, _ ->
            notifySubscribers(serviceId)
        }.also { it.start() }

        ssdpResponder = SsdpResponder(
            scope = environment.scope,
            udn = udn,
            descriptionUrl = DeviceDescription.locationFor(baseUrl),
            socketProvider = ssdpSocketProvider
                ?: { java.net.MulticastSocket(SsdpMessage.MULTICAST_PORT).apply { reuseAddress = true } },
            groupJoin = ssdpGroupJoin
                ?: { socket -> socket.joinGroup(java.net.InetAddress.getByName(SsdpMessage.MULTICAST_GROUP)) },
        ).also { it.start() }

        Logger.i("DLNA receiver advertising as '${environment.displayName}' on $baseUrl")
    }

    override suspend fun stop() {
        ssdpResponder?.stop()
        httpServer?.stop()
        player?.release()
        environment?.let { env ->
            if (httpPort != 0) env.releasePort(httpPort)
            env.multicastLock.release(MULTICAST_OWNER)
        }
        environment = null

        ssdpResponder = null
        httpServer = null
        player = null
        httpPort = 0

        _session.value?.let { endSession(it, TerminationReason.SERVICE_STOPPING) }
        _state.value = ProtocolState.DISABLED
        Logger.i("DLNA receiver stopped")
    }

    /** Called by the service when a sender's session should end. */
    suspend fun onSenderGone(reason: TerminationReason = TerminationReason.SENDER_DISCONNECTED) {
        _session.value?.let { endSession(it, reason) }
    }

    private fun notifySubscribers(serviceId: String) {
        val variables = when (serviceId) {
            DlnaServicesIds.AV_TRANSPORT -> mapOf("TransportState" to rendererState.phase.name)
            DlnaServicesIds.RENDERING_CONTROL -> mapOf(
                "Volume" to rendererState.volume.toString(),
                "Mute" to if (rendererState.muted) "1" else "0",
            )
            else -> return
        }
        val body = GenaSubscriptionManager.notifyBody(serviceId, variables)
        for (subscriber in subscriptions.subscribers(serviceId)) {
            Logger.d("DLNA notify ${subscriber.callbackUrl}: $variables")
            // Delivery is best-effort: a control point that has gone away must
            // not take the renderer down with it.
            runCatching { sendNotify(subscriber.callbackUrl, body) }
                .onFailure { Logger.d("DLNA notify failed: ${it.message}") }
        }
    }

    private fun sendNotify(callbackUrl: String, body: String) {
        val connection = java.net.URL(callbackUrl).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "NOTIFY"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("NT", "upnp:event")
            connection.setRequestProperty("NTS", "upnp:propchange")
            connection.connectTimeout = NOTIFY_TIMEOUT_MS
            connection.readTimeout = NOTIFY_TIMEOUT_MS
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun endSession(current: CastSession, reason: TerminationReason) {
        current.terminate(reason)
        _session.value = null
    }

    /** The session object the UI reads; driven by the transport phase. */
    private fun sessionFor(phase: TransportPhase, sender: String): CastSession? =
        if (phase == TransportPhase.STOPPED) null else DlnaSession(phase, sender)

    // ─── session ─────────────────────────────────────────────────────────────

    private inner class DlnaSession(
        transportPhase: TransportPhase,
        senderName: String,
    ) : CastSession {
        override val id = SessionId(UUID.randomUUID().toString())
        override val kind = ProtocolKind.DLNA
        override val sender = MutableStateFlow(SenderInfo(name = senderName))
        override val phase = MutableStateFlow(SessionPhase.STREAMING)
        override val media = MutableStateFlow(
            MediaState(
                transport = when (transportPhase) {
                    TransportPhase.PLAYING -> TransportState.PLAYING
                    TransportPhase.PAUSED -> TransportState.PAUSED
                    else -> TransportState.STOPPED
                },
                positionMs = 0L,
                durationMs = -1L,
                volume = rendererState.volume / 100f,
                muted = rendererState.muted,
                seekable = true,
            ),
        )
        override val crypto = null
        override suspend fun terminate(reason: TerminationReason) {
            this.phase.value = SessionPhase.ENDED
            Logger.i("DLNA session ended: $reason")
        }
    }

    companion object {
        /** Requested, not guaranteed — the allocator may substitute. */
        const val DEFAULT_HTTP_PORT = 8200
        const val MULTICAST_OWNER = "dlna"
        private const val FALLBACK_HOST = "127.0.0.1"
        private const val NOTIFY_TIMEOUT_MS = 3_000
    }
}

/** Service IDs referenced by the notification path. */
private object DlnaServicesIds {
    const val AV_TRANSPORT = "urn:upnp-org:serviceId:AVTransport"
    const val RENDERING_CONTROL = "urn:upnp-org:serviceId:RenderingControl"
}
