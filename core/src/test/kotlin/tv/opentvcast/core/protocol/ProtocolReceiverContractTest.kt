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
import org.junit.Test
import tv.opentvcast.core.net.ReceiverEnvironment

/**
 * Tests for [ProtocolReceiver]'s default [ProtocolReceiver.onNetworkChanged].
 *
 * The default is real production behaviour, not a placeholder: a network change
 * tears the receiver down and brings it back up. Two things about it are worth
 * pinning down.
 *
 * First, the ORDER. `stop()` must precede `start()`. Reversed, the receiver would
 * try to advertise while the old sockets are still bound, and the failure is a
 * port conflict — intermittent, platform-dependent, and painful to attribute.
 *
 * Second, that the default is a *fallback*. Protocols that can migrate a live
 * connection are supposed to override it, and if the interface default were ever
 * made final or non-overridable, that would silently become impossible.
 */
class ProtocolReceiverContractTest {

    /**
     * A receiver that does NOT override [onNetworkChanged], so these tests exercise
     * the interface's own default body.
     */
    private class DefaultBehaviourReceiver : ProtocolReceiver {
        override val capabilities = ProtocolCapabilities(
            kind = ProtocolKind.AIRPLAY,
            displayName = "AirPlay",
            supportsVideo = true,
            supportsAudio = true,
            supportsPhoto = true,
            supportsRemoteControl = true,
        )

        override val state: StateFlow<ProtocolState> = MutableStateFlow(ProtocolState.DISABLED)
        override val session: StateFlow<CastSession?> = MutableStateFlow(null)

        /** Ordered record of lifecycle calls, so order can be asserted. */
        val calls = mutableListOf<String>()

        override suspend fun start(environment: ReceiverEnvironment) {
            calls += "start"
        }

        override suspend fun stop() {
            calls += "stop"
        }
    }

    /** A receiver that overrides the default, to prove the default is replaceable. */
    private class MigratingReceiver : ProtocolReceiver {
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

        val calls = mutableListOf<String>()

        override suspend fun start(environment: ReceiverEnvironment) { calls += "start" }
        override suspend fun stop() { calls += "stop" }

        // Smooth migration: re-advertise without tearing the session down.
        override suspend fun onNetworkChanged(environment: ReceiverEnvironment) {
            calls += "reAdvertiseOnly"
        }
    }

    @Test
    fun `default onNetworkChanged stops before it starts`() = runBlocking {
        val receiver = DefaultBehaviourReceiver()

        receiver.onNetworkChanged(UnusedEnvironment)

        assertEquals(
            "A network change must tear down before re-advertising, or the old " +
                "sockets are still bound when the new ones try to bind",
            listOf("stop", "start"),
            receiver.calls,
        )
    }

    @Test
    fun `default onNetworkChanged passes the environment through to start`() = runBlocking {
        val receiver = EnvironmentRecordingReceiver()

        receiver.onNetworkChanged(UnusedEnvironment)

        assertEquals(
            "The new environment carries the new address; dropping it would " +
                "re-advertise the stale one",
            listOf(UnusedEnvironment),
            receiver.environmentsSeenByStart,
        )
    }

    /** Records which environment instance reached [start]. */
    private class EnvironmentRecordingReceiver : ProtocolReceiver {
        override val capabilities = ProtocolCapabilities(
            kind = ProtocolKind.DLNA,
            displayName = "DLNA",
            supportsVideo = true,
            supportsAudio = true,
            supportsPhoto = false,
            supportsRemoteControl = false,
        )

        override val state: StateFlow<ProtocolState> = MutableStateFlow(ProtocolState.DISABLED)
        override val session: StateFlow<CastSession?> = MutableStateFlow(null)

        val environmentsSeenByStart = mutableListOf<ReceiverEnvironment>()

        override suspend fun start(environment: ReceiverEnvironment) {
            environmentsSeenByStart += environment
        }

        override suspend fun stop() = Unit
    }

    @Test
    fun `a receiver may override the default to migrate instead of restarting`() = runBlocking {
        val receiver = MigratingReceiver()

        receiver.onNetworkChanged(UnusedEnvironment)

        assertEquals(
            "Protocols that can migrate must be able to bypass the stop/start default",
            listOf("reAdvertiseOnly"),
            receiver.calls,
        )
    }

    /**
     * The fakes never read the environment, so this exists only to satisfy the type.
     * Anything actually touching it is a test that needs a real implementation.
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
