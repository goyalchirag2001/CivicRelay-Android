package org.civicrelay.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsDraftTest {
    @Test fun coordinatesContainOpenableMapLink() {
        val draft = SmsDraft.build("Need help", 28.6139, 77.209, 14f, "current GPS", true)
        assertTrue(draft.contains("https://maps.google.com/?q=28.6139,77.209"))
        assertTrue(draft.contains("approx. 14m accuracy"))
    }
    @Test fun unavailableLocationIsNeverPresentedAsCoordinates() {
        val draft = SmsDraft.build("Need help", null, null, null, "unavailable", true)
        assertTrue(draft.contains("Location unavailable"))
        assertFalse(draft.contains("maps.google.com"))
    }
    @Test fun locationOptOutNeverSharesCoordinates() {
        val draft = SmsDraft.build("Need help", 28.6139, 77.209, 14f, "current GPS", false)
        assertFalse(draft.contains("28.6139"))
        assertFalse(draft.contains("maps.google.com"))
    }
    @Test fun invalidCoordinatesDoNotLeakAsRealLocation() {
        val draft = SmsDraft.build("Need help", 100.0, 222.0, null, "GPS", true)
        assertTrue(draft.contains("Location unavailable"))
    }
}
