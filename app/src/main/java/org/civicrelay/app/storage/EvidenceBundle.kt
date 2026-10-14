package org.civicrelay.app.storage

import android.content.Context
import org.civicrelay.app.crypto.Crypto
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Recipient-encrypted portable evidence bundle. Never stores a decrypted media file.
 * NOT a background mesh replication protocol. Recipients must explicitly import the file.
 * This format is experimental and must be independently audited before high-risk use.
 */
object EvidenceBundle {
    const val MAX_BYTES = 50L * 1024 * 1024
    private const val SCHEMA = "CRB1"
    private const val HEADER = "header.json"
    private const val MEDIA = "evidence.crv"

    private fun digestFile(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha.update(buffer, 0, n)
            }
        }
        return Crypto.hex(sha.digest())
    }

    private fun signatureText(j: JSONObject): ByteArray = listOf(SCHEMA,
        j.getString("sourceId"), j.getString("kind"), j.getLong("created").toString(),
        j.getString("plainHash"), j.getString("cipherHash"),
        Crypto.fingerprint(j.getString("recipient")),
        Crypto.fingerprint(j.getString("sender")),
        Crypto.hex(Crypto.sha256(j.optString("ocrSuggestion").toByteArray(Charsets.UTF_8))))
        .joinToString("|").toByteArray(Charsets.UTF_8)

    /** Re-encrypts in a streaming pipeline. The decryption must authenticate before the result is accepted. */
    private fun reencrypt(original: File, newCiphertext: File, oldKey: SecretKey, newKey: SecretKey): String {
        val plainHash = MessageDigest.getInstance("SHA-256")
        FileInputStream(original).use { input ->
            val prefix = ByteArray(16)
            require(input.read(prefix) == prefix.size &&
                String(prefix.copyOfRange(0, 4), Charsets.US_ASCII) == "CRV1") { "Invalid encrypted evidence" }
            val decipher = Cipher.getInstance("AES/GCM/NoPadding")
            decipher.init(Cipher.DECRYPT_MODE, oldKey, GCMParameterSpec(128, prefix.copyOfRange(4, 16)))
            val iv = Crypto.randomBytes(12)
            val encipher = Cipher.getInstance("AES/GCM/NoPadding")
            encipher.init(Cipher.ENCRYPT_MODE, newKey, GCMParameterSpec(128, iv))
            FileOutputStream(newCiphertext).use { output ->
                output.write("CRV1".toByteArray(Charsets.US_ASCII))
                output.write(iv)
                CipherInputStream(input, decipher).use { decrypted ->
                    CipherOutputStream(output, encipher).use { encrypted ->
                        val buffer = ByteArray(32768)
                        var total = 0L
                        while (true) {
                            val n = decrypted.read(buffer)
                            if (n == -1) break
                            total += n
                            require(total <= MAX_BYTES - 64) { "Evidence too large" }
                            plainHash.update(buffer, 0, n)
                            encrypted.write(buffer, 0, n)
                        }
                    }
                }
            }
        }
        return Crypto.hex(plainHash.digest())
    }

    fun export(context: Context, vault: Vault, mediaId: String, recipient: String): File {
        require(vault.contacts().any { it.second == recipient }) { "Recipient must be a saved trusted contact" }
        val (info, originalKey) = vault.evidenceSource(mediaId)
        val original = vault.mediaFile(info)
        require(original.isFile && original.length() in 29..MAX_BYTES) { "Evidence missing or larger than 50 MB" }
        val keyBytes = Crypto.randomBytes(32)
        val transportBytes = Crypto.randomBytes(32)
        val encryptedTemp = File(context.cacheDir, "${UUID.randomUUID()}.working-crv")
        val bundle = File(context.cacheDir, "${UUID.randomUUID()}.crb")
        try {
            val actualHash = reencrypt(original, encryptedTemp, originalKey, SecretKeySpec(keyBytes, "AES"))
            require(actualHash == info.getString("sha256")) { "Existing evidence checksum mismatch" }
            val manifest = JSONObject()
                .put("schema", SCHEMA).put("sourceId", mediaId)
                .put("kind", info.getString("kind"))
                .put("created", info.getLong("created"))
                .put("plainHash", actualHash)
                .put("cipherHash", digestFile(encryptedTemp))
                .put("sender", vault.pub())
                .put("recipient", recipient)
                .put("evidenceKey", Crypto.b64(keyBytes))
                .put("ocrSuggestion", info.optString("ocrSuggestion").take(1200))
            manifest.put("sig", Crypto.sign(vault.privateKey(), signatureText(manifest)))
            val header = JSONObject()
                .put("schema", SCHEMA)
                .put("wrapped", Crypto.wrapKey(Crypto.publicKey(recipient), transportBytes))
                .put("manifest", Crypto.b64(Crypto.seal(SecretKeySpec(transportBytes, "AES"),
                    manifest.toString().toByteArray(Charsets.UTF_8))))
            ZipOutputStream(FileOutputStream(bundle).buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(HEADER))
                zip.write(header.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(MEDIA))
                encryptedTemp.inputStream().buffered().use { it.copyTo(zip, 32768) }
                zip.closeEntry()
            }
            require(bundle.length() <= MAX_BYTES + 65_536) { "Encrypted bundle too large" }
            return bundle
        } catch (error: Exception) {
            bundle.delete()
            throw error
        } finally {
            keyBytes.fill(0)
            transportBytes.fill(0)
            encryptedTemp.delete()
        }
    }

    private fun cappedRead(input: InputStream, maximum: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            require(output.size().toLong() + n <= maximum) { "Manifest too large" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    /** Validate the trusted sender's signature, recipient key, GCM tag and both hashes before committing. */
    fun import(context: Context, vault: Vault, bundle: File): JSONObject {
        require(bundle.isFile && bundle.length() in 1..(MAX_BYTES + 65_536)) { "Invalid bundle size" }
        val folder = File(context.filesDir, "evidence").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val partial = File(folder, "$id.partial")
        val final = File(folder, "$id.crv")
        var committed = false
        try {
            var header: JSONObject? = null
            var seenMedia = false
            val cipherHash = MessageDigest.getInstance("SHA-256")
            ZipInputStream(bundle.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                require(entry?.name == HEADER) { "Header missing or wrong entry order" }
                header = JSONObject(String(cappedRead(zip, 16_384), Charsets.UTF_8))
                zip.closeEntry()
                entry = zip.nextEntry
                require(entry?.name == MEDIA) { "Evidence missing or wrong entry order" }
                FileOutputStream(partial).use { out ->
                    val buffer = ByteArray(32768)
                    var bytes = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n == -1) break
                        bytes += n
                        require(bytes in 1..MAX_BYTES) { "Evidence too large" }
                        cipherHash.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                    require(bytes >= 29) { "Evidence incomplete" }
                    seenMedia = true
                }
                zip.closeEntry()
                require(zip.nextEntry == null) { "Unexpected additional archive entry" }
            }
            check(seenMedia)
            val outer = requireNotNull(header)
            require(outer.getString("schema") == SCHEMA)
            val transportKey = Crypto.unwrapKey(vault.privateKey(), outer.getString("wrapped"))
            val inner = JSONObject(String(Crypto.unseal(transportKey,
                Crypto.decode(outer.getString("manifest"))), Charsets.UTF_8))
            require(inner.getString("schema") == SCHEMA && inner.getString("recipient") == vault.pub()) {
                "Bundle is not addressed to this vault"
            }
            val sender = inner.getString("sender")
            require(vault.contacts().any { it.second == sender }) { "Sender is not a verified trusted contact" }
            require(Crypto.verify(Crypto.publicKey(sender), signatureText(inner), inner.getString("sig"))) {
                "Invalid evidence signature"
            }
            require(inner.getString("cipherHash") == Crypto.hex(cipherHash.digest())) { "Ciphertext hash mismatch" }
            val rawKey = Crypto.decode(inner.getString("evidenceKey"))
            try {
                require(rawKey.size == 32) { "Invalid evidence key" }
                val plainDigest = MessageDigest.getInstance("SHA-256")
                FileInputStream(partial).use { input ->
                    val prefix = ByteArray(16)
                    require(input.read(prefix) == prefix.size &&
                        String(prefix.copyOfRange(0, 4), Charsets.US_ASCII) == "CRV1") { "Invalid encrypted media" }
                    val decipher = Cipher.getInstance("AES/GCM/NoPadding")
                    decipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"),
                        GCMParameterSpec(128, prefix.copyOfRange(4, 16)))
                    CipherInputStream(input, decipher).use { decrypted ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val n = decrypted.read(buffer)
                            if (n < 0) break
                            plainDigest.update(buffer, 0, n)
                        }
                    }
                }
                require(inner.getString("plainHash") == Crypto.hex(plainDigest.digest())) {
                    "Original evidence hash mismatch"
                }
                check(partial.renameTo(final)) { "Cannot finalize encrypted evidence" }
                val record = JSONObject()
                    .put("id", id).put("name", final.name)
                    .put("kind", inner.getString("kind"))
                    .put("created", inner.getLong("created"))
                    .put("sha256", inner.getString("plainHash"))
                    .put("ocrSuggestion", inner.optString("ocrSuggestion").take(1200))
                    .put("sourceId", inner.getString("sourceId"))
                    .put("importedFrom", Crypto.fingerprint(sender))
                vault.saveImportedMedia(record, final, rawKey)
                committed = true
                return record
            } finally { rawKey.fill(0) }
        } finally {
            partial.delete()
            if (!committed) final.delete()
        }
    }
}
