/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [PortBindException].
 *
 * Small class, high leverage. It is the one failure the registry is expected to
 * recognise and act on: [tv.opentvcast.core.protocol.ProtocolReceiver.start] is
 * documented to throw it when a preferred port cannot be bound, and the retry
 * policy lives in the registry rather than in each protocol. That only works if
 * the exception carries enough for the caller to decide what to do — which is why
 * the port is a field and not just text in a message.
 *
 * Upstream hardcoded 6001/6002 in three separate files and let `BindException`
 * escape as a raw `java.io.IOException`, so nothing downstream could tell "this
 * port is taken" from "the network is down".
 */
class NetworkContractTest {

    @Test
    fun `carries the port that could not be bound`() {
        val failure = PortBindException(port = 7000, message = "RTSP port 7000 is taken")

        assertEquals(7000, failure.port)
        assertEquals("RTSP port 7000 is taken", failure.message)
    }

    @Test
    fun `accepts a null port for a purely dynamic allocation`() {
        // PortAllocator.allocate(preferred = null) asks for any free port. If even
        // that fails there is no port to report, and inventing one would be worse
        // than admitting it.
        val failure = PortBindException(port = null, message = "no free port")

        assertNull(failure.port)
    }

    @Test
    fun `is unchecked so protocol code needs no throws clause`() {
        // ProtocolReceiver.start is suspend and shared by every protocol; forcing a
        // checked exception through it would leak socket concerns into the contract.
        assertTrue(
            "PortBindException must remain unchecked",
            RuntimeException::class.java.isAssignableFrom(PortBindException::class.java),
        )
    }

    @Test
    fun `preserves the underlying cause for diagnostics`() {
        val bind = java.net.BindException("Address already in use")
        val failure = PortBindException(port = 7000, message = "cannot bind", cause = bind)

        assertSame(
            "The native BindException text is the only clue for a user reporting a " +
                "port conflict, so it must survive",
            bind,
            failure.cause,
        )
    }

    @Test
    fun `is catchable by its own type through the ProtocolReceiver contract`() {
        // Mirrors how ReceiverRegistry calls start(): runCatching around the whole
        // call, then the caller inspects the throwable.
        val caught = runCatching {
            throw PortBindException(port = 7000, message = "cannot bind")
        }.exceptionOrNull()

        when (caught) {
            is PortBindException -> assertEquals(7000, caught.port)
            else -> fail("Expected a PortBindException, got ${caught?.javaClass?.name}")
        }
    }

    // NOTE: there is deliberately no test here for PortAllocator's semantics beyond
    // the exception type. An earlier revision defined an anonymous fake allocator and
    // asserted against it, which tested the fake rather than the product. The real
    // behaviour now lives in ServerSocketPortAllocatorTest, against
    // ServerSocketPortAllocator itself.
}
