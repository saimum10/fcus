package com.focus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The stored form of the previous Do Not Disturb state. Written in Phase 5, run in Phase 8. */
class InterruptionSnapshotTest {
    @Test
    fun roundTrips() {
        val s = InterruptionSnapshot(1, 12, 2, 3, 4, 5)
        assertEquals(s, InterruptionSnapshot.fromStorage(s.toStorage()))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(InterruptionSnapshot.fromStorage(""))
        assertNull(InterruptionSnapshot.fromStorage("1,2,3"))
        assertNull(InterruptionSnapshot.fromStorage("1,2,3,4,5,x"))
    }
}
