package org.civicrelay.app.mesh

import org.junit.Assert.*
import org.junit.Test

class EvidenceTransferPolicyTest {
    private val id = "2d5f1c00-1234-4abc-8fed-123456789abc"
    private val hash = "0".repeat(64)

    @Test fun validatesBoundedSignedOfferMetadata() {
        assertTrue(EvidenceTransferPolicy.validOffer(id, 1024, hash))
        assertFalse(EvidenceTransferPolicy.validOffer(id, 0, hash))
        assertFalse(EvidenceTransferPolicy.validOffer(id, EvidenceTransferPolicy.MAX_ARCHIVE + 1, hash))
        assertFalse(EvidenceTransferPolicy.validOffer("../../etc/passwd", 1024, hash))
        assertFalse(EvidenceTransferPolicy.validOffer(id, 1024, "not-a-sha256"))
    }
    @Test fun requiresDiskForBothStagingAndVerification() {
        val bytes = 1024L * 1024L
        assertFalse(EvidenceTransferPolicy.freeSpaceEnough(bytes, bytes))
        assertTrue(EvidenceTransferPolicy.freeSpaceEnough(bytes * 2 + EvidenceTransferPolicy.MIN_FREE_BUFFER, bytes))
    }
    @Test fun receiptMustBindOriginalTransferAndContent() {
        assertTrue(EvidenceTransferPolicy.matches(id, 123, hash, id, 123, hash))
        assertFalse(EvidenceTransferPolicy.matches(id, 123, hash, "other", 123, hash))
        assertFalse(EvidenceTransferPolicy.matches(id, 123, hash, id, 124, hash))
        assertFalse(EvidenceTransferPolicy.matches(id, 123, hash, id, 123, "1".repeat(64)))
    }
    @Test fun boundedProgress() {
        assertEquals(0, EvidenceTransferPolicy.percent(0, 0))
        assertEquals(30, EvidenceTransferPolicy.percent(30, 100))
        assertEquals(100, EvidenceTransferPolicy.percent(110, 100))
        assertEquals(0, EvidenceTransferPolicy.percent(-100, 100))
    }
}
