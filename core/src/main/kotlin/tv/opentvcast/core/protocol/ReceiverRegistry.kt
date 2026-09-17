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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.opentvcast.core.net.ReceiverEnvironment

/**
 * Owns the set of protocol receivers and is the only place that knows all of
 * them.
 *
 * Adding a protocol means calling [register] once at composition time; nothing
 * in this class, in the service, or in the UI changes. That is the whole point
 * of the interface layer. Upstream, by contrast, hardcoded three receivers in
 * the service's `onCreate` and switched on a sealed enum everywhere else.
 *
 * The registry also owns two policies that must be global rather than
 * per-protocol:
 *
 * - **Session arbitration.** opentvcast v1 serves one sender at a time. Which
 *   sender wins is decided here, not inside a protocol that only sees its own
 *   connection attempts.
 * - **Network migration.** Every receiver is told about a network change in
 *   turn, so re-advertising is coordinated rather than racing.
 */
class ReceiverRegistry : AutoCloseable {

    private val mutex = Mutex()
    private val receivers = LinkedHashMap<ProtocolKind, ProtocolReceiver>()

    private val _states = MutableStateFlow<Map<ProtocolKind, ProtocolState>>(emptyMap())
    val states: StateFlow<Map<ProtocolKind, ProtocolState>> = _states.asStateFlow()

    private val _activeSession = MutableStateFlow<CastSession?>(null)
    val activeSession: StateFlow<CastSession?> = _activeSession.asStateFlow()

    private val _events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob())

    /**
     * Cancels the state observers launched by [register]. Without this, a
     * discarded registry leaks: every collector keeps running — and keeps
     * writing into flows nobody reads — for the life of the process.
     */
    override fun close() {
        scope.cancel()
    }

    /** Registers a receiver. Idempotent per [ProtocolReceiver.capabilities] kind. */
    fun register(receiver: ProtocolReceiver) {
        receivers[receiver.capabilities.kind] = receiver
        scope.launch { observe(receiver) }
    }

    /** Every registered receiver, for settings screens and diagnostics. */
    fun capabilities(): List<ProtocolCapabilities> = receivers.values.map { it.capabilities }

    private suspend fun observe(receiver: ProtocolReceiver) {
        receiver.state.collect { state ->
            mutex.withLock {
                _states.value = _states.value + (receiver.capabilities.kind to state)
            }
        }
    }

    /**
     * Starts every receiver.
     *
     * Uses a `SupervisorJob` parent so that one protocol failing to bind does
     * not prevent the others from advertising — a receiver that cannot start
     * simply settles into [ProtocolState.ERROR].
     */
    suspend fun startAll(environment: ReceiverEnvironment) {
        receivers.values.forEach { receiver ->
            runCatching { receiver.start(environment) }
                .onFailure { throwable ->
                    _events.emit(
                        CastEvent.Error(
                            kind = receiver.capabilities.kind,
                            code = ErrorCode.UNKNOWN,
                            detail = throwable.message ?: "failed to start",
                        )
                    )
                }
        }
    }

    suspend fun stopAll() {
        receivers.values.forEach { receiver ->
            runCatching { receiver.stop() }
        }
        mutex.withLock { _activeSession.value = null }
    }

    /** Propagates a network change to every receiver. */
    suspend fun onNetworkChanged(environment: ReceiverEnvironment) {
        receivers.values.forEach { receiver ->
            runCatching { receiver.onNetworkChanged(environment) }
        }
    }

    /**
     * Decides whether [candidate] may take over from any live session.
     *
     * v1 policy:
     * - no active session -> accept
     * - same sender -> accept (it is a reconnect, so drop the stale session)
     * - otherwise -> reject, and emit [CastEvent.SessionRequested] so the UI can
     *   offer the user a preemption prompt rather than a bare failure.
     *
     * The upstream behaviour was to answer `503 Service Unavailable` with no
     * body, leaving the sender to time out silently.
     */
    suspend fun requestSession(
        candidate: CastSession,
        preemptionAllowed: Boolean,
    ): Boolean {
        return mutex.withLock {
            val current = _activeSession.value
            when {
                current == null || current.phase.value == SessionPhase.ENDED -> {
                    _activeSession.value = candidate
                    true
                }

                current.sender.value.name == candidate.sender.value.name -> {
                    _activeSession.value = candidate
                    true
                }

                preemptionAllowed -> {
                    _activeSession.value = candidate
                    true
                }

                else -> false
            }
        }
    }

    suspend fun clearSession(session: CastSession, reason: TerminationReason) {
        mutex.withLock {
            if (_activeSession.value?.id == session.id) {
                _activeSession.value = null
            }
        }
        _events.emit(CastEvent.SessionTerminated(session.id, reason))
    }

    suspend fun emit(event: CastEvent) = _events.emit(event)
}

/**
 * [tv.opentvcast.core.protocol.CastEventBus] implementation backed by the
 * registry, so protocol modules and the service share one stream.
 */
class RegistryEventBus(private val registry: ReceiverRegistry) : CastEventBus {
    override val events: SharedFlow<CastEvent> = registry.events
    override suspend fun emit(event: CastEvent) = registry.emit(event)
}
