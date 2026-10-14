package org.civicrelay.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.civicrelay.app.storage.RelayQueue
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RelayQueueInstrumentedTest {
    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.filesDir, "relay-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getFilesDir(): File = folder
        }
    }

    @Test fun duplicateIdsAndExpiredPacketsAreNotReplayed() {
        val context = isolatedContext()
        val queue = RelayQueue(context)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val valid = JSONObject().put("id", id).put("expires", now + 60_000)
            .put("hops", 2).put("type", "bulletin")
        assertTrue(queue.add(valid))
        assertFalse(queue.add(valid))
        val expired = JSONObject().put("id", UUID.randomUUID().toString())
            .put("expires", now - 1).put("hops", 2).put("type", "bulletin")
        queue.add(expired)
        assertEquals(1, queue.all().size)
        assertEquals(id, RelayQueue(context).all().single().getString("id"))
    }

    @Test fun cacheIsBoundedAt128Packets() {
        val queue = RelayQueue(isolatedContext())
        val expiry = System.currentTimeMillis() + 60_000L
        repeat(160) {
            queue.add(JSONObject().put("id", "test-${UUID.randomUUID()}")
                .put("expires", expiry).put("hops", 1).put("type", "bulletin"))
        }
        assertEquals(128, queue.all().size)
    }
}
