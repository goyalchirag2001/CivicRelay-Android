package org.civicrelay.app.storage

import org.junit.Assert.*
import org.junit.Test

class EvidenceViewPolicyTest {
    @Test fun acceptsJpegEndMarkers() {
        assertTrue(EvidenceViewPolicy.valid("photo", byteArrayOf(-1, -40, 0, 0, -1, -39)))
        assertFalse(EvidenceViewPolicy.valid("photo", byteArrayOf(-1, -40, 0, 0)))
        assertFalse(EvidenceViewPolicy.valid("photo", byteArrayOf(0, 0, 0, 0)))
    }
    @Test fun acceptsMp4FtypOnlyForVideo() {
        val header = byteArrayOf(0, 0, 0, 20, 'f'.code.toByte(), 't'.code.toByte(),
            'y'.code.toByte(), 'p'.code.toByte(), 0, 0, 0, 0)
        assertTrue(EvidenceViewPolicy.valid("video", header))
        assertFalse(EvidenceViewPolicy.valid("photo", header))
        assertFalse(EvidenceViewPolicy.valid("unknown", header))
    }
    @Test fun boundingAndMime() {
        assertEquals("video/mp4", EvidenceViewPolicy.mime("video"))
        assertEquals("image/jpeg", EvidenceViewPolicy.mime("photo"))
        assertEquals(".jpg", EvidenceViewPolicy.fileExtension("photo"))
        assertTrue(EvidenceViewPolicy.previewSampleSize(5000, 4000) >= 2)
        try {
            EvidenceViewPolicy.previewSampleSize(20000, 20000)
            fail("Should reject enormous image")
        } catch (_: IllegalArgumentException) { }
    }
}
