package org.civicrelay.app.mesh

/**
 * Lightweight protection against accidental or deliberate Nearby packet floods.
 * It is NOT a Sybil defense: untrusted nearby users can reconnect under new endpoint IDs.
 * No endpoint identifier is written to persistent storage.
 */
class IngressLimiter(
    private val maxPacketsPerWindow: Int = 24,
    private val maxBytesPerWindow: Int = 96 * 1024,
    private val windowMillis: Long = 60_000L,
    private val maxPeers: Int = 32
) {
    private data class Budget(var start: Long, var packets: Int, var bytes: Int)
    private val budgets = LinkedHashMap<String, Budget>()

    @Synchronized fun allow(peer: String, bytes: Int, now: Long): Boolean {
        if (peer.isBlank() || bytes <= 0 || bytes > 16_384) return false
        // An internal monotonic time source is provided by the transport.
        // If a clock jumps backwards, restart the peer's window safely.
        budgets.entries.removeAll { now < it.value.start || now - it.value.start >= windowMillis }
        var budget = budgets[peer]
        if (budget == null) {
            if (budgets.size >= maxPeers) budgets.remove(budgets.keys.first())
            budget = Budget(now, 0, 0)
            budgets[peer] = budget
        }
        if (budget.packets >= maxPacketsPerWindow || budget.bytes + bytes > maxBytesPerWindow) return false
        budget.packets++
        budget.bytes += bytes
        return true
    }

    @Synchronized fun forget(peer: String) { budgets.remove(peer) }
    @Synchronized fun clear() { budgets.clear() }
}
