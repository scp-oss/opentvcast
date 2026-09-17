package tv.opentvcast.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the DACP wire contract in [DacpClient.commandUrl].
 *
 * The full client is NSD-bound (Android service discovery) and lives on real
 * sockets; what is pure — and what the sender's DACP server actually parses —
 * is the URL. A regression in the shape here is a TV remote that silently
 * does nothing.
 */
class DacpClientTest {

    @Test
    fun `command URL has the ctrl-int shape`() {
        assertEquals(
            "http://192.168.1.10:3689/ctrl-int/1/playpause",
            DacpClient.commandUrl("192.168.1.10", 3689, DacpClient.CMD_PLAY_PAUSE),
        )
    }

    @Test
    fun `every documented command lands on its own path segment`() {
        for (command in listOf(
            DacpClient.CMD_PLAY_PAUSE, DacpClient.CMD_NEXT, DacpClient.CMD_PREV,
            DacpClient.CMD_FF, DacpClient.CMD_REW,
        )) {
            assertEquals(
                "http://10.0.0.2:7000/ctrl-int/1/$command",
                DacpClient.commandUrl("10.0.0.2", 7000, command),
            )
        }
    }
}
