package tv.opentvcast.airplay.handshake

import tv.opentvcast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * AirPlayNtpClient — the receiver side of AirPlay 2 NTP timing.
 *
 * Unlike legacy AirPlay (where the sender probes the receiver), AirPlay 2 mirroring requires
 * the RECEIVER to actively poll the sender's timing port. macOS waits for this timing exchange
 * to begin before it will send the video stream SETUP, so without it the session stalls right
 * after the key exchange.
 *
 * We open a local UDP socket (its port is returned as `timingPort` in the SETUP response) and
 * periodically send a 32-byte NTP request to the sender, draining the replies. Precise clock
 * sync isn't required to start mirroring (frames render on arrival), so we keep this minimal —
 * the goal is to satisfy macOS that timing is live.
 *
 * Reference: RPiPlay lib/raop_ntp.c (raop_ntp_thread).
 */
class AirPlayNtpClient(
    private val remoteAddress: InetAddress,
    private val remoteTimingPort: Int,
) {
    private val socket = DatagramSocket()      // OS-assigned local port
    @Volatile private var running = false

    /** Local UDP port to advertise to macOS as the receiver's timingPort. */
    val localPort: Int get() = socket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        socket.soTimeout = RECV_TIMEOUT_MS
        scope.launch(Dispatchers.IO) { loop() }
        Logger.i("NTP client → [$remoteAddress]:$remoteTimingPort, local timing port $localPort")
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }

    private fun loop() {
        // request: [0]=0x80 (RTP), [1]=0xd2 (timing request), [2-3]=seq, [24-31]=send NTP time.
        val request = ByteArray(32)
        request[0] = 0x80.toByte()
        request[1] = 0xD2.toByte()
        request[3] = 0x07
        val response = ByteArray(128)
        var first = true
        var rxCount = 0
        while (running) {
            try {
                // Pack with the SHARED conversion (TimingHandler) so the client's
                // request timestamps and the responder's reply timestamps are the
                // same math — this used to be a hand-copied duplicate.
                val ntp = tv.opentvcast.airplay.TimingHandler.millisToNtpTimestamp(System.currentTimeMillis())
                tv.opentvcast.airplay.TimingHandler.writeUint32(request, 24, (ntp ushr 32).toInt())
                tv.opentvcast.airplay.TimingHandler.writeUint32(request, 28, (ntp and 0xFFFF_FFFFL).toInt())
                socket.send(DatagramPacket(request, request.size, remoteAddress, remoteTimingPort))
                if (first) { Logger.i("NTP: first timing request sent to macOS"); first = false }
                try {
                    val rx = DatagramPacket(response, response.size)
                    socket.receive(rx)
                    if (rxCount < 4) {
                        Logger.i("NTP RX[$rxCount] ${rx.length}B type=0x${(response[1].toInt() and 0xFF).toString(16)}: " +
                            (0 until minOf(rx.length, 32)).joinToString(" ") { "%02x".format(response[it]) })
                        rxCount++
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // SO_RCVTIMEO expiry — fine, retry next tick.
                } catch (e: Exception) {
                    // A receive failure that is NOT a timeout is real (e.g. socket
                    // closed early) — one debug line, then keep polling as before.
                    Logger.d("NTP receive error: ${e.message}")
                }
            } catch (e: Exception) {
                if (running) Logger.e("NTP client send error", e)
            }
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        private const val RECV_TIMEOUT_MS = 1000
    }
}
