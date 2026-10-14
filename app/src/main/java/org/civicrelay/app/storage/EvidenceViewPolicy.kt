package org.civicrelay.app.storage

/** File signatures are only a coarse media type check, NOT a substitute for verification. */
object EvidenceViewPolicy {
    const val MAX_CLEAR_BYTES = 50 * 1024 * 1024
    const val MAX_DECODE_PIXELS = 16_000_000L

    fun valid(kind: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_CLEAR_BYTES) return false
        return when (kind) {
            "photo" -> bytes.size >= 4 &&
                (bytes[0].toInt() and 255) == 0xFF &&
                (bytes[1].toInt() and 255) == 0xD8 &&
                (bytes[bytes.lastIndex - 1].toInt() and 255) == 0xFF &&
                (bytes.last().toInt() and 255) == 0xD9
            "video" -> bytes.size >= 12 &&
                bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
            else -> false
        }
    }

    fun mime(kind: String): String = when (kind) {
        "photo" -> "image/jpeg"
        "video" -> "video/mp4"
        else -> throw IllegalArgumentException("Unsupported media type")
    }

    fun fileExtension(kind: String) = if (kind == "photo") ".jpg" else if (kind == "video") ".mp4" else
        throw IllegalArgumentException("Unsupported media type")

    fun previewSampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "Unrecognized image dimensions" }
        require(width.toLong() * height.toLong() <= 100_000_000L) { "Image dimensions exceed limit" }
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > MAX_DECODE_PIXELS ||
            width / sample > 2400 || height / sample > 2400) sample *= 2
        return sample
    }
}
