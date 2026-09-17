package tv.opentvcast.airplay.handshake

import android.content.Context
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * PairingKeys — the receiver's long-lived Ed25519 identity used by AirPlay pair-setup /
 * pair-verify. The 32-byte seed is persisted in app-private prefs so the device keeps a
 * stable identity across restarts (matching RPiPlay/UxPlay behaviour).
 */
class PairingKeys private constructor(private val edPrivate: Ed25519PrivateKeyParameters) {

    /** 32-byte raw Ed25519 public key. */
    val edPublic: ByteArray by lazy { edPrivate.generatePublicKey().encoded }

    /** Ed25519 signature (64 bytes) over [message]. */
    fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, edPrivate)
        update(message, 0, message.size)
        generateSignature()
    }

    companion object {
        /**
         * Shared preferences file holding the long-term Ed25519 pairing seed.
         *
         * Deliberately the same file as [tv.opentvcast.util.NetworkUtils]'s device
         * identity: both are small, device-scoped values, and keeping them in one
         * file means one file to reason about when clearing device state.
         *
         * Renamed from upstream's "phairplay_prefs". No released version wrote the
         * old name, so the seed simply regenerates on first run; existing pairings
         * with senders would need re-pairing anyway because the seed is the
         * identity the sender remembers.
         */
        private const val PREFS = "opentvcast_prefs"
        private const val KEY_SEED = "pairing_ed25519_seed"

        @Volatile private var instance: PairingKeys? = null

        fun get(context: Context): PairingKeys =
            instance ?: synchronized(this) { instance ?: load(context).also { instance = it } }

        /** Builds keys from a raw 32-byte Ed25519 seed (used by tests). */
        fun create(seed: ByteArray): PairingKeys =
            PairingKeys(Ed25519PrivateKeyParameters(seed, 0))

        private fun load(context: Context): PairingKeys {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seed = prefs.getString(KEY_SEED, null)?.let(Codec::unhex)
                ?: ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                    prefs.edit().putString(KEY_SEED, Codec.hex(it)).apply()
                }
            return create(seed)
        }
    }
}
