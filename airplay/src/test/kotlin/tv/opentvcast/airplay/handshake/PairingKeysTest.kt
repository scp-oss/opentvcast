package tv.opentvcast.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Tests for [PairingKeys], the receiver's long-lived Ed25519 identity.
 *
 * The identity IS the device as far as a sender is concerned: the public key
 * goes into /info and pair-verify, and signatures answer the SRP-adjacent
 * proof. A regression here means senders silently stop trusting the TV after
 * an update (or worse, accept impersonations).
 */
class PairingKeysTest {

    /** Any fixed 32 bytes — Ed25519 seeds are uniformly random, shape is all that matters. */
    private val seed = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun `public key is 32 bytes and derived deterministically from the seed`() {
        val keys = PairingKeys.create(seed)

        assertEquals(32, keys.edPublic.size)
        val again = PairingKeys.create(seed)
        assertArrayEquals("same seed must give the same identity", keys.edPublic, again.edPublic)
    }

    @Test
    fun `different seeds give different public keys`() {
        val otherSeed = ByteArray(32) { (it * 11 + 5).toByte() }
        assertNotEquals(
            "distinct seeds must not collide into one identity",
            PairingKeys.create(seed).edPublic.toList(),
            PairingKeys.create(otherSeed).edPublic.toList(),
        )
    }

    @Test
    fun `signature verifies against the derived public key`() {
        val keys = PairingKeys.create(seed)
        val message = "pair-verify-client-proof".toByteArray()

        val signature = keys.sign(message)

        assertEquals(64, signature.size)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(keys.edPublic, 0))
            update(message, 0, message.size)
        }
        assertTrue("signature must verify", verifier.verifySignature(signature))
    }

    @Test
    fun `a tampered message does not verify`() {
        val keys = PairingKeys.create(seed)
        val signature = keys.sign("legitimate".toByteArray())

        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(keys.edPublic, 0))
            update("tampered".toByteArray(), 0, "tampered".length)
        }
        assertTrue("tampered message must fail verification", !verifier.verifySignature(signature))
    }
}
