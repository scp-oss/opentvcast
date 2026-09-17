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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.opentvcast.core.net.ReceiverEnvironment

/**
 * Tests for the [CastEventBus] contract, exercised through its real implementation
 * [RegistryEventBus].
 *
 * WHY THIS MATTERS: [CastEvent] exists because these occurrences must be consumed
 * exactly once. Upstream modelled the pairing PIN as a `StateFlow`, so every time
 * the Activity was recreated — which on TV happens on screensaver, focus loss or
 * rotation — the PIN dialog reappeared. The fix is that [CastEventBus.events] has
 * no replay, and "no replay" is a property that is invisible until it regresses:
 * a `replay = 1` would look harmless in review and produce a dialog that reopens
 * every time the user looks away.
 *
 * So the second test below is the important one. It asserts that a subscriber
 * attaching *after* an event was emitted does not receive it.
 */
class CastEventBusTest {

    @Test
    fun `events carry no replay cache`() = runBlocking {
        val bus = RegistryEventBus(ReceiverRegistry())

        bus.emit(CastEvent.PinRequested("1234"))

        assertTrue(
            "A non-empty replay cache means a late subscriber sees stale one-shot " +
                "events — the exact bug this type replaced StateFlow to fix",
            bus.events.replayCache.isEmpty(),
        )
    }

    @Test
    fun `a subscriber attaching after an emit does not receive it`() = runBlocking {
        val bus = RegistryEventBus(ReceiverRegistry())

        // Emitted with nobody listening. This is the "the user was not looking" case.
        bus.emit(CastEvent.PinRequested("1111"))

        val seen = mutableListOf<CastEvent>()
        // UNDISPATCHED so the collector is subscribed before this line returns;
        // otherwise the test races the scheduler instead of testing the contract.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.events.collect { seen += it }
        }

        bus.emit(CastEvent.PinRequested("2222"))
        bus.emit(CastEvent.PinDismissed)
        withTimeout(2_000) { while (seen.size < 2) yield() }
        job.cancel()

        assertEquals(
            "Only events emitted after subscription may be seen",
            listOf<CastEvent>(CastEvent.PinRequested("2222"), CastEvent.PinDismissed),
            seen,
        )
    }

    @Test
    fun `emitting with no subscriber does not throw`() = runBlocking {
        val bus = RegistryEventBus(ReceiverRegistry())

        // A receiver can fail, or ask for a PIN, before the UI has bound.
        bus.emit(CastEvent.PinDismissed)
        bus.emit(CastEvent.Error(kind = null, code = ErrorCode.UNKNOWN, detail = "boom"))
        bus.emit(CastEvent.SessionTerminated(SessionId("s1"), TerminationReason.SENDER_DISCONNECTED))
    }

    @Test
    fun `the bus and the registry share one stream`() = runBlocking {
        val registry = ReceiverRegistry()
        val bus = RegistryEventBus(registry)

        assertSame(
            "The service and the protocol modules must observe the same events, " +
                "not two independently buffered streams",
            registry.events,
            bus.events,
        )

        val received = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

        // Emitted through the registry, observed through the bus.
        registry.emit(CastEvent.PhotoCleared)

        assertEquals(CastEvent.PhotoCleared, withTimeout(2_000) { received.await() })
    }

    @Test
    fun `an event emitted by a registry failure reaches the bus`() = runBlocking {
        val registry = ReceiverRegistry()
        val bus = RegistryEventBus(registry)
        registry.register(FailingReceiver())

        val received = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

        registry.startAll(UnusedEnvironment)

        val event = withTimeout(2_000) { received.await() }
        assertTrue("startAll must report a failing receiver, not swallow it", event is CastEvent.Error)
        assertEquals(ProtocolKind.AIRPLAY, (event as CastEvent.Error).kind)
    }

    // ─── payload fidelity ────────────────────────────────────────────────────
    //
    // These are data classes, so the risk is not "does equality work" but "did
    // anyone change a field's meaning". Each is asserted through the type the UI
    // actually pattern-matches on.

    @Test
    fun `PinRequested carries the PIN`() {
        val event: CastEvent = CastEvent.PinRequested("4821")

        assertEquals("4821", (event as CastEvent.PinRequested).pin)
    }

    @Test
    fun `Error may have no protocol, for service-wide failures`() {
        val event: CastEvent = CastEvent.Error(
            kind = null,
            code = ErrorCode.PERMISSION_MISSING,
            detail = "no network",
        )

        val error = event as CastEvent.Error
        assertEquals(null, error.kind)
        assertEquals(ErrorCode.PERMISSION_MISSING, error.code)
        assertEquals("no network", error.detail)
    }

    @Test
    fun `SessionEstablished and SessionTerminated carry distinct payloads`() {
        val id = SessionId("session-7")

        val established = CastEvent.SessionEstablished(id, ProtocolKind.DLNA)
        val terminated = CastEvent.SessionTerminated(id, TerminationReason.NETWORK_CHANGED)

        assertEquals(id, established.id)
        assertEquals(ProtocolKind.DLNA, established.kind)
        assertEquals(TerminationReason.NETWORK_CHANGED, terminated.reason)
    }

    @Test
    fun `the singleton events are distinct`() {
        assertNotEquals(CastEvent.PinDismissed, CastEvent.PhotoCleared)
        assertEquals(CastEvent.PinDismissed, CastEvent.PinDismissed)
        assertEquals(CastEvent.PhotoCleared, CastEvent.PhotoCleared)
    }

    @Test
    fun `ErrorCode covers the failure classes the UI words`() {
        assertEquals(
            listOf(
                "PORT_UNAVAILABLE",
                "DISCOVERY_FAILED",
                "HANDSHAKE_FAILED",
                "UNSUPPORTED_MEDIA",
                "STREAM_INTERRUPTED",
                "PERMISSION_MISSING",
                "UNKNOWN",
            ),
            ErrorCode.entries.map { it.name },
        )
    }

    // ─── fakes ───────────────────────────────────────────────────────────────

    private class FailingReceiver : ProtocolReceiver {
        override val capabilities = ProtocolCapabilities(
            kind = ProtocolKind.AIRPLAY,
            displayName = "AirPlay",
            supportsVideo = true,
            supportsAudio = true,
            supportsPhoto = false,
            supportsRemoteControl = false,
        )

        override val state: StateFlow<ProtocolState> = MutableStateFlow(ProtocolState.DISABLED)
        override val session: StateFlow<CastSession?> = MutableStateFlow(null)

        override suspend fun start(environment: ReceiverEnvironment) {
            throw IllegalStateException("port 7000 already in use")
        }

        override suspend fun stop() = Unit
    }

    private object UnusedEnvironment : ReceiverEnvironment {
        override val scope get() = throw UnsupportedOperationException("not used by fakes")
        override val localAddress get() = throw UnsupportedOperationException("not used by fakes")
        override val allAddresses get() = throw UnsupportedOperationException("not used by fakes")
        override val displayName get() = "test"
        override val surfaceSink get() = throw UnsupportedOperationException("not used by fakes")
        override val multicastLock get() = throw UnsupportedOperationException("not used by fakes")
        override val eventBus get() = throw UnsupportedOperationException("not used by fakes")
        override fun allocatePort(preferred: Int?): Int = throw UnsupportedOperationException("not used by fakes")
        override fun releasePort(port: Int) = throw UnsupportedOperationException("not used by fakes")
    }
}
