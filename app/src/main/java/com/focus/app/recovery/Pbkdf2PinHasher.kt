package com.focus.app.recovery

import com.focus.app.domain.PinCredential
import com.focus.app.domain.PinHasher
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashes the trusted person's PIN with PBKDF2-HMAC-SHA256 and a random 16-byte salt, so the
 * PIN itself is never stored and cannot be shown to anyone, including the device owner.
 * `PBKDF2WithHmacSHA256` is available from Android 8.0 (API 26), the app's minimum.
 *
 * The work is deliberately slow (about a second on a modest phone): callers run it off the
 * main thread. The iteration count is stored next to each hash, so it can be raised later
 * without invalidating existing PINs. Tests pass a small count to stay fast.
 *
 * Honest limit: a 6-digit PIN has only a million values. The hash slows an attacker who
 * could read fOCUS's private storage (which already needs root); against everyone else the
 * protection is the wrong-PIN lockout (see `AttemptPolicy`).
 */
class Pbkdf2PinHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) : PinHasher {

    init {
        require(iterations >= 1) { "iterations must be positive" }
    }

    override fun hash(pin: String): PinCredential {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        return PinCredential(iterations, salt.toHex(), derive(pin, salt, iterations).toHex())
    }

    override fun verify(pin: String, credential: PinCredential): Boolean {
        if (credential.iterations < 1) return false
        val salt = credential.saltHex.fromHex() ?: return false
        val expected = credential.hashHex.fromHex() ?: return false
        if (salt.isEmpty() || expected.isEmpty()) return false
        // Constant-time comparison.
        return MessageDigest.isEqual(derive(pin, salt, credential.iterations, expected.size * 8), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int, keyBits: Int = KEY_BITS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, keyBits)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Null if this is not an even-length string of hex digits. */
    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        val bytes = ByteArray(length / 2)
        for (i in bytes.indices) {
            val value = substring(2 * i, 2 * i + 2).toIntOrNull(16) ?: return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    companion object {
        /** Chosen for roughly a second on a mid-range phone; stored per hash, so it can change later. */
        const val DEFAULT_ITERATIONS = 310_000
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}
