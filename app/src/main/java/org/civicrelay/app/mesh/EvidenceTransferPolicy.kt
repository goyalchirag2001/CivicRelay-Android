package org.civicrelay.app.mesh

/** Pure data bounds for the single-inflight, consent-based encrypted file transfer. */
object EvidenceTransferPolicy {
    const val MAX_ARCHIVE = 50L * 1024 * 1024 + 65_536L
    const val MIN_FREE_BUFFER = 10L * 1024 * 1024L
    private val uuid = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private val sha256 = Regex("[a-f0-9]{64}")

    fun validOffer(id: String, size: Long, hash: String): Boolean =
        uuid.matches(id) && size in 1..MAX_ARCHIVE && sha256.matches(hash)

    fun freeSpaceEnough(free: Long, size: Long): Boolean =
        size in 1..MAX_ARCHIVE && free >= size * 2 + MIN_FREE_BUFFER

    fun matches(id: String, size: Long, hash: String,
                incomingId: String, incomingSize: Long, incomingHash: String): Boolean =
        id == incomingId && size == incomingSize && hash == incomingHash && validOffer(id, size, hash)

    fun percent(sent: Long, total: Long): Int =
        if (total <= 0) 0 else ((sent.coerceIn(0, total) * 100L) / total).toInt()
}
