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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.opentvcast.core.net.ReceiverEnvironment

/**
 * Tests for [ReceiverRegistry]'s session arbitration.
 *
 * This policy is the reason the registry exists. opentvcast v1 serves one sender
 * at a time, but "refuse the second sender" is not good enough on its own: a
 * sender that reconnects after a drop is *also* a second sender, and treating it
 * as a conflict would leave the user unable to resume. Getting this wrong is
 * either invisible (silent refusal) or infuriating (stuck session), so each
 * branch gets an explicit test.
 */
class ReceiverRegistryTest {

    /** Minimal [CastSession] — arbitration only reads [CastSession.sender]. */
    private class FakeSession(
        override val id: SessionId,
        override val kind: ProtocolKind,
        senderName: String,
        override val phase: MutableStateFlow<SessionPhase> = MutableStateFlow(SessionPhase.STREAMING),
    ) : CastSession {
        override val sender: StateFlow<SenderInfo> = MutableStateFlow(SenderInfo(name = senderName))
        override val media: StateFlow<MediaState?> = MutableStateFlow(null)
        override val crypto: SessionCrypto? = null
        var terminatedWith: TerminationReason? = null
        override suspend fun terminate(reason: TerminationReason) {
            terminatedWith = reason
            phase.value = SessionPhase.ENDED
        }
    }

    private class FakeReceiver(
        override val capabilities: ProtocolCapabilities,
        override val state: MutableStateFlow<ProtocolState> = MutableStateFlow(ProtocolState.DISABLED),
    ) : ProtocolReceiver {
        override val session: StateFlow<CastSession?> = MutableStateFlow(null)
        var startCount = 0
        var stopCount = 0
        var failOnStart = false

        override suspend fun start(environment: ReceiverEnvironment) {
            if (failOnStart) throw IllegalStateException("cannot bind")
            startCount++
            state.value = ProtocolState.ADVERTISING
        }

        override suspend fun stop() {
            stopCount++
            state.value = ProtocolState.DISABLED
        }
    }

    private fun caps(kind: ProtocolKind) = ProtocolCapabilities(
        kind = kind,
        displayName = kind.id,
        supportsVideo = true,
        supportsAudio = true,
        supportsPhoto = false,
        supportsRemoteControl = false,
    )

    private fun session(kind: ProtocolKind, name: String, id: String = "s-$name") =
        FakeSession(SessionId(id), kind, name)

    // ─── lifecycle (D7) ──────────────────────────────────────────────────────

    @Test
    fun `close cancels the state observers - a discarded registry goes deaf`() = runBlocking {
        val registry = ReceiverRegistry()
        val receiver = FakeReceiver(caps(ProtocolKind.AIRPLAY))
        registry.register(receiver)
        receiver.state.value = ProtocolState.ADVERTISING
        // Let the launched observer run (it races this thread on a private scope).
        Thread.sleep(100)
        assertEquals(ProtocolState.ADVERTISING, registry.states.value[ProtocolKind.AIRPLAY])

        registry.close()
        receiver.state.value = ProtocolState.CONNECTED
        Thread.sleep(100)

        // Without close(), the collector launched in register() keeps the
        // coroutine scope (and the receiver) alive forever and keeps writing
        // into a registry nobody uses any more.
        assertEquals(
            "a closed registry must no longer observe receivers",
            ProtocolState.ADVERTISING,
            registry.states.value[ProtocolKind.AIRPLAY],
        )
    }

    // ─── arbitration ─────────────────────────────────────────────────────────

    @Test
    fun `accepts the first session when idle`() = runBlocking {
        val registry = ReceiverRegistry()
        assertTrue(registry.requestSession(session(ProtocolKind.AIRPLAY, "iPhone"), false))
        assertEquals("iPhone", registry.activeSession.value!!.sender.value.name)
    }

    @Test
    fun `accepts a reconnect from the same sender`() = runBlocking {
        val registry = ReceiverRegistry()
        registry.requestSession(session(ProtocolKind.AIRPLAY, "iPhone"), false)

        // Same device name, new session id — a reconnect after a dropped link.
        assertTrue(registry.requestSession(session(ProtocolKind.AIRPLAY, "iPhone", id = "s-2"), false))
        assertEquals("s-2", registry.activeSession.value!!.id.value)
    }

    @Test
    fun `rejects a different sender by default`() = runBlocking {
        val registry = ReceiverRegistry()
        registry.requestSession(session(ProtocolKind.AIRPLAY, "iPhone"), false)

        assertFalse(registry.requestSession(session(ProtocolKind.DLNA, "Windows PC"), false))
        assertEquals("iPhone", registry.activeSession.value!!.sender.value.name)
    }

    @Test
    fun `preemption replaces the incumbent when allowed`() = runBlocking {
        val registry = ReceiverRegistry()
        registry.requestSession(session(ProtocolKind.AIRPLAY, "iPhone"), false)

        assertTrue(registry.requestSession(session(ProtocolKind.DLNA, "Windows PC"), preemptionAllowed = true))
        assertEquals("Windows PC", registry.activeSession.value!!.sender.value.name)
    }

    @Test
    fun `an ended session does not block a new sender`() = runBlocking {
        val registry = ReceiverRegistry()
        val old = session(ProtocolKind.AIRPLAY, "iPhone")
        registry.requestSession(old, false)
        old.phase.value = SessionPhase.ENDED

        assertTrue(registry.requestSession(session(ProtocolKind.DLNA, "Windows PC"), false))
    }

    // ─── teardown ────────────────────────────────────────────────────────────

    @Test
    fun `clearSession releases the slot and emits a termination event`() = runBlocking {
        val registry = ReceiverRegistry()
        val s = session(ProtocolKind.AIRPLAY, "iPhone")
        registry.requestSession(s, false)

        registry.clearSession(s, TerminationReason.SENDER_DISCONNECTED)
        assertNull(registry.activeSession.value)
    }

    @Test
    fun `clearSession ignores a session that is no longer active`() = runBlocking {
        val registry = ReceiverRegistry()
        val current = session(ProtocolKind.AIRPLAY, "iPhone")
        registry.requestSession(current, false)

        // A stale session finishing late must not evict the live one.
        registry.clearSession(session(ProtocolKind.DLNA, "stale", id = "s-stale"), TerminationReason.PROTOCOL_ERROR)
        assertEquals("iPhone", registry.activeSession.value!!.sender.value.name)
    }

    @Test
    fun `stopAll clears the active session`() = runBlocking {
        val registry = ReceiverRegistry()
        registry.requestSession(session(ProtocolKind.AIRPLAY, "iPhone"), false)

        registry.stopAll()
        assertNull(registry.activeSession.value)
    }

    // ─── registration and lifecycle fan-out ──────────────────────────────────

    @Test
    fun `capabilities lists every registered receiver`() {
        val registry = ReceiverRegistry()
        registry.register(FakeReceiver(caps(ProtocolKind.AIRPLAY)))
        registry.register(FakeReceiver(caps(ProtocolKind.DLNA)))

        val kinds = registry.capabilities().map { it.kind }.toSet()
        assertEquals(setOf(ProtocolKind.AIRPLAY, ProtocolKind.DLNA), kinds)
    }

    @Test
    fun `re-registering the same kind replaces the previous receiver`() {
        val registry = ReceiverRegistry()
        registry.register(FakeReceiver(caps(ProtocolKind.AIRPLAY)))
        registry.register(FakeReceiver(caps(ProtocolKind.AIRPLAY)))

        assertEquals(1, registry.capabilities().size)
    }

    @Test
    fun `startAll starts every receiver`() = runBlocking {
        val registry = ReceiverRegistry()
        val a = FakeReceiver(caps(ProtocolKind.AIRPLAY))
        val d = FakeReceiver(caps(ProtocolKind.DLNA))
        registry.register(a)
        registry.register(d)

        // The registry only forwards; the environment is unused by the fakes, so a
        // null-ish stand-in is acceptable here and keeps this test focused.
        registry.startAll(UnusedEnvironment)

        assertEquals(1, a.startCount)
        assertEquals(1, d.startCount)
    }

    @Test
    fun `a receiver that fails to start does not prevent the others`() = runBlocking {
        val registry = ReceiverRegistry()
        val broken = FakeReceiver(caps(ProtocolKind.AIRPLAY)).apply { failOnStart = true }
        val healthy = FakeReceiver(caps(ProtocolKind.DLNA))
        registry.register(broken)
        registry.register(healthy)

        registry.startAll(UnusedEnvironment)

        assertEquals(0, broken.startCount)
        assertEquals(1, healthy.startCount)
    }

    @Test
    fun `stopAll stops every receiver`() = runBlocking {
        val registry = ReceiverRegistry()
        val a = FakeReceiver(caps(ProtocolKind.AIRPLAY))
        registry.register(a)

        registry.stopAll()
        assertEquals(1, a.stopCount)
    }

    /**
     * The fakes never touch the environment, so this exists only to satisfy the
     * parameter type. It carries no behaviour on purpose — a partial mock here
     * would hide a real dependency if one were ever introduced.
     */
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
