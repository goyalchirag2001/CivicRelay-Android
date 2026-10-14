package org.civicrelay.app

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.civicrelay.app.storage.EvidenceReader
import org.civicrelay.app.storage.Vault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Requires Android instrumentation and hardware-backed key support as available. */
@RunWith(AndroidJUnit4::class)
class EvidenceViewerInstrumentedTest {
    @Test fun authenticatesThenRejectsTamperedLocalEvidence() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "viewer-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = folder }
        val password = "synthetic-test-passphrase".toCharArray()
        val vault = Vault(context)
        try {
            vault.create(password)
            val source = File(folder, "synthetic.jpg")
            val clear = byteArrayOf(-1, -40, 0, 1, 2, -1, -39)
            source.writeBytes(clear)
            val record = vault.saveMedia(source, "photo")
            val opened = EvidenceReader.open(vault, record.getString("id"))
            assertArrayEquals(clear, opened.bytes)
            opened.close()
            assertTrue(opened.bytes.all { it == 0.toByte() })
            val encrypted = vault.mediaFile(record)
            val corrupted = encrypted.readBytes()
            corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
            encrypted.writeBytes(corrupted)
            try {
                EvidenceReader.open(vault, record.getString("id"))
                fail("Modified GCM ciphertext must be rejected")
            } catch (_: Exception) { /* authentication must fail */ }
        } finally {
            password.fill('\u0000')
            vault.lock()
            folder.deleteRecursively()
        }
    }
}
