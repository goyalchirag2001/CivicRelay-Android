package org.civicrelay.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.civicrelay.app.storage.EvidenceBundle
import org.civicrelay.app.storage.Vault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EvidenceBundleInstrumentedTest {
    private fun isolatedContext(tag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.filesDir, "test-${UUID.randomUUID()}-$tag").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(folder, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(folder, "cache").apply { mkdirs() }
        }
    }

    @Test fun transferReceiptsArePersistedOnlyForMatchingTrustedContact() {
        val a = isolatedContext("transferlog")
        val vault = Vault(a).apply { create("long transfer-test passphrase 2026".toCharArray()) }
        val friend = Vault(isolatedContext("friend")).apply {
            create("long friend passphrase 2026".toCharArray())
        }
        val other = Vault(isolatedContext("other")).apply {
            create("long unrelated passphrase 2026".toCharArray())
        }
        val id = UUID.randomUUID().toString()
        vault.logEvidenceOffer(id, "source-media-test", friend.pub(), 1024)
        vault.recordEvidenceStored(id, other.pub())
        assertEquals("OFFERED_NOT_STORED", vault.records("transfers").first().getString("status"))
        vault.recordEvidenceStored(id, friend.pub())
        assertEquals("VERIFIED_STORED_BY_TRUSTED_APP", vault.records("transfers").first().getString("status"))
        vault.lock()
        vault.unlock("long transfer-test passphrase 2026".toCharArray())
        assertEquals("VERIFIED_STORED_BY_TRUSTED_APP", vault.records("transfers").first().getString("status"))
    }

    @Test fun encryptedEvidenceCanBeImportedOnlyByTrustedIntendedRecipient() {
        val a = isolatedContext("sender")
        val b = isolatedContext("recipient")
        val c = isolatedContext("stranger")
        val owner = Vault(a).apply { create("sender test passphrase 2026".toCharArray()) }
        val friend = Vault(b).apply { create("recipient test passphrase 2026".toCharArray()) }
        val unrelated = Vault(c).apply { create("stranger test passphrase 2026".toCharArray()) }
        owner.addContact("Recipient", friend.pub())
        friend.addContact("Sender", owner.pub())
        val source = File(a.cacheDir, "capture.tmp").apply { writeText("HARNESS_ONLY encrypted evidence") }
        val info = owner.saveMedia(source, "photo")
        val portable = EvidenceBundle.export(a, owner, info.getString("id"), friend.pub())
        assertTrue(portable.exists())
        try {
            assertTrue(runCatching { EvidenceBundle.import(c, unrelated, portable) }.isFailure)
            val restored = EvidenceBundle.import(b, friend, portable)
            assertEquals(info.getString("sha256"), restored.getString("sha256"))
            assertEquals(1, friend.records("media").size)
        } finally { portable.delete() }
    }
}
