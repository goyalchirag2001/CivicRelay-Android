package org.civicrelay.app.mesh

import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests; do not depend on an emulator or Android services. */
class IngressLimiterTest {
    @Test fun rejectsOversizeEmptyAndFloods() {
        val limiter = IngressLimiter(maxPacketsPerWindow = 2, maxBytesPerWindow = 100)
        assertFalse(limiter.allow("peer", 0, 100L))
        assertFalse(limiter.allow("peer", 16_385, 100L))
        assertTrue(limiter.allow("peer", 40, 100L))
        assertTrue(limiter.allow("peer", 40, 200L))
        assertFalse(limiter.allow("peer", 1, 300L))
        assertTrue(limiter.allow("peer", 80, 60_101L))
        assertFalse(limiter.allow("peer", 30, 60_102L))
    }

    @Test fun eachPeerHasIndependentBudgetAndCanBeForgotten() {
        val limiter = IngressLimiter(maxPacketsPerWindow = 1)
        assertTrue(limiter.allow("a", 20, 1L))
        assertFalse(limiter.allow("a", 20, 2L))
        assertTrue(limiter.allow("b", 20, 2L))
        limiter.forget("a")
        assertTrue(limiter.allow("a", 20, 3L))
    }
}
