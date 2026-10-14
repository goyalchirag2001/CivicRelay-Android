package org.civicrelay.app.storage

import org.civicrelay.app.crypto.Crypto
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.GCMParameterSpec

/**
 * A fully verified, short-lived in-memory representation. The UI must close it on leaving the screen.
 * Raw media is never written to an internal plaintext temp file by this reader. Java GC and codec
 * internals do not promise secure erasure, and a compromised OS can still access application memory.
 */
class VerifiedEvidence(
    val id: String,
    val kind: String,
    val created: Long,
    val sha256: String,
    val importedFrom: String?,
    val bytes: ByteArray
) : AutoCloseable {
    override fun close() { bytes.fill(0) }
}

private class ClearingOutput : ByteArrayOutputStream() {
    fun wipe() { buf.fill(0); reset() }
}

object EvidenceReader {
    /** GCM authentication and stored plaintext hash must both pass before returning any bytes. */
    fun open(vault: Vault, id: String): VerifiedEvidence {
        val (record, key) = vault.evidenceSource(id)
        val kind = record.getString("kind")
        EvidenceViewPolicy.mime(kind)
        val file = vault.mediaFile(record)
        require(file.isFile && file.length() in 29..(EvidenceViewPolicy.MAX_CLEAR_BYTES + 64).toLong()) {
            "Encrypted evidence file is missing or too large"
        }
        val plaintext = ClearingOutput()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val header = ByteArray(16)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    require(n > 0) { "Evidence header truncated" }
                    read += n
                }
                require(String(header, 0, 4, Charsets.US_ASCII) == "CRV1") { "Unknown evidence format" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, header.copyOfRange(4, 16)))
                CipherInputStream(input, cipher).use { decrypted ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0
                    try {
                        while (true) {
                            val n = decrypted.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= EvidenceViewPolicy.MAX_CLEAR_BYTES) { "Evidence exceeds safe viewing size" }
                            digest.update(buffer, 0, n)
                            plaintext.write(buffer, 0, n)
                        }
                    } finally { buffer.fill(0) }
                }
            }
            require(Crypto.hex(digest.digest()).equals(record.getString("sha256"), ignoreCase = true)) {
                "Evidence digest mismatch"
            }
            val clear = plaintext.toByteArray()
            if (!EvidenceViewPolicy.valid(kind, clear)) {
                clear.fill(0)
                error("Evidence format is invalid or unsupported")
            }
            return VerifiedEvidence(id, kind, record.optLong("created"), record.getString("sha256"),
                record.optString("importedFrom").takeIf { it.isNotBlank() }, clear)
        } finally {
            // Wipe the transient decrypt buffer. This is best-effort in a managed runtime.
            plaintext.wipe()
        }
    }
}
