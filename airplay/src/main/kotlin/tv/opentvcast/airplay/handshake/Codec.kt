package tv.opentvcast.airplay.handshake

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared wire-format helpers for the AirPlay handshake and stream paths.
 *
 * These were previously duplicated as private helpers in four classes
 * ([PairingStore], [PairingKeys], [FairPlay], [NowPlayingInfo]/[TimingHandler]).
 * One copy here means one place to fix, and one place to test.
 */
internal object Codec {

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /** Lower-case hex encoding, two characters per byte. */
    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }

    /** Inverse of [hex]. Requires an even-length, hex-only string — throws otherwise. */
    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex string must have even length: ${s.length}" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = digit(s[2 * i])
            val lo = digit(s[2 * i + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("invalid hex digit '$c'")
    }

    /** Reads an unsigned 32-bit big-endian integer at [offset]. */
    fun be32(b: ByteArray, offset: Int = 0): Long {
        require(offset + 4 <= b.size) { "need 4 bytes at offset $offset, have ${b.size}" }
        return ((b[offset].toLong() and 0xFF) shl 24) or
            ((b[offset + 1].toLong() and 0xFF) shl 16) or
            ((b[offset + 2].toLong() and 0xFF) shl 8) or
            (b[offset + 3].toLong() and 0xFF)
    }

    /**
     * RAOP audio decryption: AES-128-CBC over whole 16-byte blocks; a trailing sub-block
     * remainder stays cleartext, and the IV is re-applied per call (no chaining across packets) —
     * matching how AirPlay senders encrypt. The caller owns [cipher] (thread-confined reuse).
     */
    fun cbcDecrypt(cipher: Cipher, key: SecretKeySpec, iv: IvParameterSpec, data: ByteArray): ByteArray {
        val encryptedLen = (data.size / 16) * 16
        if (encryptedLen == 0) return data
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        val out = data.copyOf()
        cipher.doFinal(data, 0, encryptedLen, out, 0)
        return out
    }
}
