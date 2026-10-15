package org.civicrelay.app.mesh

/** Prioritize short-lived private emergencies over older public news. Not a delivery guarantee. */
object PacketReplayPolicy {
    data class Item(val id: String, val type: String, val created: Long, val expires: Long, val hops: Int)
    private fun priority(type: String): Int = when (type) {
        "sos" -> 0
        "receipt" -> 1
        "checkin" -> 2
        "bulletin" -> 3
        else -> 4
    }
    fun select(items: List<Item>, now: Long, limit: Int = 64): List<Item> = items
        .asSequence().filter { it.hops > 0 && it.expires > now && it.created <= now + 120_000 }
        .distinctBy { it.id }
        .sortedWith(compareBy<Item> { priority(it.type) }.thenByDescending { it.created })
        .take(limit.coerceIn(0, 128)).toList()
}
