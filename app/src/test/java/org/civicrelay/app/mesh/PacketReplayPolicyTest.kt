package org.civicrelay.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class PacketReplayPolicyTest {
    @Test fun newestSosBeatsOlderPosts() {
        val now = 1_000_000L
        val posts = (0..70).map { PacketReplayPolicy.Item("post-$it", "bulletin", now - it, now+10000, 3) }
        val urgent = PacketReplayPolicy.Item("sos", "sos", now - 50, now+10000, 3)
        val output = PacketReplayPolicy.select(posts + urgent, now, 32)
        assertEquals(32, output.size)
        assertEquals("sos", output.first().id)
    }
    @Test fun expiredAndNonRelayPacketsAreSkipped() {
        val now = 1000L
        val items = listOf(PacketReplayPolicy.Item("expired","sos",500,900,3),
            PacketReplayPolicy.Item("zerohops","sos",500,2000,0),
            PacketReplayPolicy.Item("valid","bulletin",500,2000,1))
        assertEquals(listOf("valid"), PacketReplayPolicy.select(items, now).map { it.id })
    }
}
