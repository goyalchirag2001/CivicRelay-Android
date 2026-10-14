package org.civicrelay.app.storage

import android.content.Context
import android.util.AtomicFile
import org.civicrelay.app.crypto.Crypto
import org.civicrelay.app.crypto.DeviceKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.PrivateKey
import javax.crypto.spec.SecretKeySpec

/** Vault metadata and identity private key are encrypted at rest with a passphrase-derived key. */
class Vault(private val context: Context) {
    private val store = AtomicFile(File(context.filesDir, "vault.crv"))
    private var key: SecretKeySpec? = null
    private var salt: ByteArray? = null
    private var state: JSONObject? = null
    val exists: Boolean get() = store.baseFile.exists()
    val unlocked: Boolean get() = key != null && state != null
    private fun current(): JSONObject = requireNotNull(state) { "Unlock vault first" }
    private fun secretKey(): SecretKeySpec = requireNotNull(key) { "Unlock vault first" }
    private fun mediaKey(): SecretKeySpec = SecretKeySpec(Crypto.decode(current().getString("mediaKey")), "AES")

    @Synchronized fun create(passphrase: CharArray) {
        require(!exists) { "Vault already exists" }
        require(passphrase.size >= 12) { "Use at least 12 characters" }
        val pair = Crypto.rsaKeyPair()
        val newState = JSONObject()
            .put("public", Crypto.b64(pair.public.encoded))
            .put("private", Crypto.b64(pair.private.encoded))
            .put("mediaKey", Crypto.b64(Crypto.randomBytes(32)))
            .put("contacts", JSONArray()).put("bulletins", JSONArray())
            .put("sosInbox", JSONArray()).put("incidents", JSONArray()).put("media", JSONArray())
            .put("transfers", JSONArray())
        val localSalt = Crypto.randomBytes(16)
        val localKey = Crypto.derive(passphrase, localSalt)
        salt = localSalt; key = localKey; state = newState
        try { save() } catch (error: Exception) {
            lock()
            throw error
        }
    }
    @Synchronized fun unlock(passphrase: CharArray) {
        val bytes = store.openRead().use { it.readBytes() }
        require(bytes.size > 44 && String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "CRV2") { "Invalid vault format" }
        val localSalt = bytes.copyOfRange(4, 20)
        val localKey = Crypto.derive(passphrase, localSalt)
        val plaintext = Crypto.unseal(localKey, DeviceKey.unseal(bytes.copyOfRange(20, bytes.size)))
        try {
            val parsed = JSONObject(String(plaintext, Charsets.UTF_8))
            require(parsed.has("private") && parsed.has("public"))
            salt = localSalt; key = localKey; state = parsed
        } finally { plaintext.fill(0) }
    }
    @Synchronized fun save() {
        val bytes = "CRV2".toByteArray() + requireNotNull(salt) + DeviceKey.seal(Crypto.seal(secretKey(), current().toString().toByteArray()))
        val output = store.startWrite()
        try { output.write(bytes); store.finishWrite(output) } catch (error: Exception) { store.failWrite(output); throw error }
    }
    @Synchronized fun lock() { state = null; key = null; salt = null }
    @Synchronized fun pub(): String = current().getString("public")
    @Synchronized fun privateKey(): PrivateKey = Crypto.privateKey(current().getString("private"))
    @Synchronized fun contacts(): List<Pair<String, String>> = current().getJSONArray("contacts").let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).getString("alias") to a.getJSONObject(i).getString("key") }
    }
    @Synchronized fun addContact(alias: String, publicKey: String) {
        require(alias.isNotBlank() && alias.length <= 48)
        val parsed = Crypto.publicKey(publicKey)
        require(parsed is java.security.interfaces.RSAPublicKey && parsed.modulus.bitLength() >= 3072) {
            "Unsupported or weak contact public key"
        }
        require(Crypto.fingerprint(publicKey) != Crypto.fingerprint(pub())) { "Cannot add yourself" }
        require(contacts().none { it.second == publicKey }) { "Contact already added" }
        current().getJSONArray("contacts").put(JSONObject().put("alias", alias.trim()).put("key", publicKey))
        save()
    }
    @Synchronized fun append(category: String, item: JSONObject, max: Int = 150) {
        require(category in setOf("bulletins", "sosInbox", "incidents", "media", "transfers"))
        val old = current().optJSONArray(category) ?: JSONArray()
        val output = JSONArray()
        for (i in maxOf(0, old.length() - max + 1) until old.length()) output.put(old.get(i))
        output.put(item)
        current().put(category, output)
        save()
    }
    @Synchronized fun records(category: String): List<JSONObject> =
        (current().optJSONArray(category) ?: JSONArray()).let { a ->
        // UI readers must not get mutable JSON objects owned by the encrypted vault state.
        (a.length() - 1 downTo 0).map { JSONObject(a.getJSONObject(it).toString()) }
    }
    @Synchronized fun logEvidenceOffer(id: String, sourceId: String, recipient: String, size: Long) {
        append("transfers", JSONObject().put("id", id).put("sourceId", sourceId)
            .put("recipientFingerprint", Crypto.fingerprint(recipient)).put("bytes", size)
            .put("created", System.currentTimeMillis()).put("status", "OFFERED_NOT_STORED"))
    }
    @Synchronized fun recordEvidenceStored(id: String, recipient: String) {
        val transfers = current().optJSONArray("transfers") ?: return
        for (index in 0 until transfers.length()) {
            val record = transfers.getJSONObject(index)
            if (record.optString("id") == id &&
                record.optString("recipientFingerprint") == Crypto.fingerprint(recipient)) {
                record.put("status", "VERIFIED_STORED_BY_TRUSTED_APP")
                record.put("receivedAt", System.currentTimeMillis())
                save()
                return
            }
        }
    }
    /** For legacy media without a per-file keyWrap, use the vault media key only locally. */
    @Synchronized fun evidenceSource(id: String): Pair<JSONObject, SecretKeySpec> {
        val item = records("media").firstOrNull { it.optString("id") == id }
            ?: throw IllegalArgumentException("Evidence not found")
        val key = if (item.has("keyWrap"))
            SecretKeySpec(Crypto.unseal(mediaKey(), Crypto.decode(item.getString("keyWrap"))), "AES")
        else mediaKey()
        return item to key
    }
    @Synchronized fun mediaFile(info: JSONObject): File {
        val name = info.getString("name")
        require(Regex("[0-9a-f-]{36}\\.crv").matches(name)) { "Invalid evidence filename" }
        return File(File(context.filesDir, "evidence"), name)
    }
    @Synchronized fun saveImportedMedia(info: JSONObject, importedFile: File, rawKey: ByteArray) {
        require(importedFile.parentFile?.canonicalFile == File(context.filesDir, "evidence").canonicalFile)
        val record = JSONObject(info.toString())
            .put("keyWrap", Crypto.b64(Crypto.seal(mediaKey(), rawKey)))
            .put("name", importedFile.name)
        append("media", record)
    }
    @Synchronized fun updateSosStatus(id: String, acknowledgingContact: String): Boolean {
        val items = current().getJSONArray("sosInbox")
        for (i in 0 until items.length()) {
            val record = items.getJSONObject(i)
            if (record.optString("id") != id || record.optString("sender") != "You") continue
            val sentTo = record.optJSONArray("recipientKeys") ?: continue
            if ((0 until sentTo.length()).none { sentTo.optString(it) == acknowledgingContact }) continue
            val confirmations = record.optJSONArray("confirmations") ?: JSONArray()
            if ((0 until confirmations.length()).any { confirmations.optString(it) == acknowledgingContact }) return false
            confirmations.put(acknowledgingContact)
            record.put("confirmations", confirmations)
            record.put("status", "RECEIVED_BY_TRUSTED_APP")
            save()
            return true
        }
        return false
    }
    @Synchronized fun saveMedia(source: File, kind: String, ocr: String = ""): JSONObject {
        require(kind == "photo" || kind == "video")
        require(source.isFile && source.length() in 1..EvidenceBundle.MAX_BYTES) {
            "Recording is empty or larger than the 50 MiB prototype limit"
        }
        val id = java.util.UUID.randomUUID().toString()
        val directory = File(context.filesDir, "evidence").apply { mkdirs() }
        val part = File(directory, "$id.partial")
        val destination = File(directory, "$id.crv")
        // Fresh per-record content key: never share the vault-wide media wrapping key.
        val rawKey = Crypto.randomBytes(32)
        val wrapped = Crypto.b64(Crypto.seal(mediaKey(), rawKey))
        val digest = try { Crypto.encryptFile(source, part, SecretKeySpec(rawKey, "AES")) } finally {
            rawKey.fill(0)
            // File deletion is best effort; flash storage does not guarantee secure erasure.
            source.delete()
        }
        check(part.renameTo(destination)) { "Could not finalize encrypted evidence" }
        val info = JSONObject().put("id", id).put("kind", kind).put("sha256", digest)
            .put("created", System.currentTimeMillis()).put("name", destination.name)
            .put("ocrSuggestion", ocr.take(1200)).put("keyWrap", wrapped)
        append("media", info)
        return info
    }
}

/** Limited on-disk relay cache. SOS contents remain encrypted, but routing metadata is visible. */
class RelayQueue(context: Context) {
    private val store = AtomicFile(File(context.filesDir, "relay-queue.json"))
    private val items = LinkedHashMap<String, JSONObject>()
    init { try {
        val a = JSONArray(store.openRead().bufferedReader().use { it.readText() })
        for (i in 0 until a.length()) { val j = a.getJSONObject(i); items[j.getString("id")] = j }
    } catch (_: Exception) { /* new queue, or damaged queue: no crash */ }
        prune()
    }
    @Synchronized private fun persist() {
        val out = store.startWrite()
        try {
            out.write(JSONArray(items.values.toList()).toString().toByteArray(Charsets.UTF_8))
            store.finishWrite(out)
        } catch (e: Exception) { store.failWrite(out); throw e }
    }
    @Synchronized private fun prune() {
        val now = System.currentTimeMillis()
        items.entries.removeAll { it.value.optLong("expires") < now }
        while (items.size > 128) items.remove(items.keys.first())
    }
    @Synchronized fun add(packet: JSONObject): Boolean {
        prune()
        val id = packet.getString("id")
        if (items.containsKey(id)) return false
        items[id] = JSONObject(packet.toString())
        prune(); persist()
        return true
    }
    @Synchronized fun all(): List<JSONObject> { prune(); return items.values.map { JSONObject(it.toString()) } }
}
