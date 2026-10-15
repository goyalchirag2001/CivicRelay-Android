package org.civicrelay.app.policy

import org.junit.Assert.*
import org.junit.Test

class DetentionMapPolicyTest {
    private val now = 100_000_000L
    private fun report(key: String, ageMinutes: Long = 45, approved: Boolean = true) =
        DetentionMapPolicy.Report(key, 28.61, 77.21, now - ageMinutes * 60_000L, approved)

    @Test fun singleOrRepeatedPersonNeverCreatesHotspot() {
        assertTrue(DetentionMapPolicy.aggregate(List(12) { report("same-key") }, now).isEmpty())
    }
    @Test fun requiresFiveKeysModeratorApprovalAndDelay() {
        assertTrue(DetentionMapPolicy.aggregate((1..4).map { report("k$it") }, now).isEmpty())
        assertTrue(DetentionMapPolicy.aggregate((1..5).map { report("k$it", 5) }, now).isEmpty())
        assertTrue(DetentionMapPolicy.aggregate((1..5).map { report("k$it", approved = false) }, now).isEmpty())
        val result = DetentionMapPolicy.aggregate((1..5).map { report("k$it") }, now)
        assertEquals(1, result.size)
        assertEquals(5, result.first().reportCount)
    }
    @Test fun expiredReportsDoNotPublish() {
        assertTrue(DetentionMapPolicy.aggregate((1..8).map { report("k$it", 500) }, now).isEmpty())
    }
}
