package com.focus.app.recovery

import com.focus.app.domain.PinCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real PBKDF2 hasher on the JVM, with a small iteration count to stay fast. Written in Phase 6, run in Phase 8. */
class Pbkdf2PinHasherTest {
    private val hasher = Pbkdf2PinHasher(iterations = 1_000)

    @Test
    fun rightPinVerifiesWrongPinDoesNot() {
        val credential = hasher.hash("482915")
        assertTrue(hasher.verify("482915", credential))
        assertFalse(hasher.verify("482916", credential))
        assertFalse(hasher.verify("", credential))
        assertFalse(hasher.verify("4829150", credential))
    }

    @Test
    fun theHashDoesNotContainThePin() {
        val credential = hasher.hash("482915")
        assertFalse(credential.hashHex.contains("482915"))
        assertFalse(credential.saltHex.contains("482915"))
        assertEquals(1_000, credential.iterations)
        assertEquals(64, credential.hashHex.length) // 256 bits as hex
        assertEquals(32, credential.saltHex.length) // 128 bits as hex
    }

    @Test
    fun everyHashGetsItsOwnSalt() {
        val a = hasher.hash("482915")
        val b = hasher.hash("482915")
        assertNotEquals(a.saltHex, b.saltHex)
        assertNotEquals(a.hashHex, b.hashHex)
        assertTrue(hasher.verify("482915", a))
        assertTrue(hasher.verify("482915", b))
    }

    @Test
    fun theStoredIterationCountIsUsedNotTheCurrentOne() {
        val old = Pbkdf2PinHasher(iterations = 500).hash("482915")
        assertTrue(Pbkdf2PinHasher(iterations = 2_000).verify("482915", old))
    }

    @Test
    fun damagedCredentialsFailClosed() {
        val good = hasher.hash("482915")
        assertFalse(hasher.verify("482915", good.copy(saltHex = "zz")))
        assertFalse(hasher.verify("482915", good.copy(saltHex = "abc"))) // odd length
        assertFalse(hasher.verify("482915", good.copy(hashHex = "")))
        assertFalse(hasher.verify("482915", good.copy(saltHex = "")))
        assertFalse(hasher.verify("482915", good.copy(iterations = 0)))
        assertFalse(hasher.verify("482915", PinCredential(1_000, good.saltHex, good.hashHex.reversed())))
    }
}
