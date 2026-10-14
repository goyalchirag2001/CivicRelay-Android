package org.civicrelay.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.civicrelay.app.crypto.Crypto
import org.civicrelay.app.crypto.Envelope
import org.civicrelay.app.mesh.NearbyMesh
import org.civicrelay.app.storage.RelayQueue
import org.civicrelay.app.storage.Vault
import org.civicrelay.app.storage.EvidenceBundle
import org.json.JSONObject
import java.io.File

class CivicService(private val context: Context) {
    val vault = Vault(context)
    private val queue = RelayQueue(context)
    val mesh = NearbyMesh(context, ::receivePacket, queue::all)
    var revision by mutableStateOf(0)
        private set
    var notice by mutableStateOf("Prototype: no guaranteed delivery. Never rely on it as your only emergency option.")
        private set
    private var lastPost = 0L

    fun unlock(passphrase: String): Boolean = try {
        val chars = passphrase.toCharArray()
        try { if (vault.exists) vault.unlock(chars) else vault.create(chars) }
        finally { chars.fill('\u0000') }
        revision++
        true
    } catch (_: Exception) { notice = "Could not open vault. Check passphrase and storage integrity."; false }

    fun lock() { mesh.stop(); vault.lock(); revision++ }
    fun addContact(alias: String, code: String) {
        val key = code.trim().removePrefix("CRCONTACT1:")
        vault.addContact(alias, key)
        revision++
        notice = "Trusted key saved. Verify fingerprint with your contact in person."
    }
    fun contactCode(): String = "CRCONTACT1:${vault.pub()}"
    fun post(text: String) {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 280)
        val now = System.currentTimeMillis()
        require(now - lastPost >= 20_000) { "Please wait before posting again" }
        val p = Envelope.compose("bulletin", trimmed, vault.pub(), vault.privateKey(), emptyList())
        queue.add(p)
        vault.append("bulletins", JSONObject().put("text", trimmed).put("id", p.getString("id"))
            .put("sender", "You").put("time", now).put("unverified", true))
        mesh.broadcast(p)
        lastPost = now; revision++
        notice = "Update saved and offered to connected peers; not globally delivered."
    }
    fun sendSos(message: String, latitude: Double?, longitude: Double?, accuracy: Float?) {
        val contacts = vault.contacts()
        require(contacts.isNotEmpty()) { "Add and verify at least one trusted contact first" }
        val body = JSONObject().put("message", message.take(500))
            .put("time", System.currentTimeMillis())
        if (latitude != null && longitude != null) {
            body.put("latitude", latitude).put("longitude", longitude)
            if (accuracy != null) body.put("accuracyMeters", accuracy.toDouble())
        }
        val packet = Envelope.compose("sos", body.toString(), vault.pub(), vault.privateKey(), contacts.map { it.second })
        queue.add(packet)
        vault.append("sosInbox", JSONObject().put("id", packet.getString("id")).put("message", message).put("sender", "You")
            .put("location", if (latitude != null) "$latitude,$longitude" else "Unavailable")
            .put("time", System.currentTimeMillis()).put("status", "QUEUED_NOT_DELIVERED")
            .put("recipientKeys", org.json.JSONArray(contacts.map { it.second })))
        mesh.broadcast(packet)
        revision++
        notice = "SOS encrypted and queued. A trusted contact app may return an encrypted receipt when a route exists."
    }
    private fun receivePacket(packet: JSONObject) {
        if (!Envelope.valid(packet)) return
        val remaining = packet.getInt("hops") - 1
        val forwardingCopy = JSONObject(packet.toString()).put("hops", remaining.coerceAtLeast(0))
        if (!queue.add(forwardingCopy)) return
        try {
            when (packet.getString("type")) {
                "bulletin" -> {
                    val text = Envelope.readBulletin(packet)
                    vault.append("bulletins", JSONObject().put("text", text).put("id", packet.getString("id"))
                        .put("sender", Crypto.fingerprint(packet.getString("sender")).take(12))
                        .put("time", packet.getLong("created")).put("unverified", true))
                }
                "sos" -> {
                    val clear = Envelope.tryOpenSos(packet, vault.privateKey())
                    val trusted = clear?.let { verified ->
                        vault.contacts().find { it.second == verified.senderPublicKey }
                    }
                    if (clear != null && trusted != null) {
                        val body = JSONObject(clear.body)
                        vault.append("sosInbox", JSONObject().put("sender", trusted.first)
                            .put("message", body.optString("message"))
                            .put("location", if (body.has("latitude"))
                                "${body.optDouble("latitude")},${body.optDouble("longitude")}" else "Unavailable")
                            .put("time", body.optLong("time")).put("status", "RECEIVED_ON_THIS_DEVICE"))
                        // Receipt signed by the verified recipient and encrypted only to the sender.
                        val receipt = Envelope.compose("receipt",
                            JSONObject().put("for", packet.getString("id"))
                                .put("receivedAt", System.currentTimeMillis()).toString(),
                            vault.pub(), vault.privateKey(), listOf(clear.senderPublicKey))
                        queue.add(receipt)
                        mesh.broadcast(receipt)
                    }
                }
                "receipt" -> {
                    val clear = Envelope.tryOpenPrivate(packet, vault.privateKey())
                    if (clear != null && vault.contacts().any { it.second == clear.senderPublicKey }) {
                        val ack = JSONObject(clear.body)
                        val id = ack.getString("for")
                        if (vault.updateSosStatus(id, clear.senderPublicKey)) {
                            notice = "A trusted contact's app cryptographically confirmed SOS receipt. This does not mean it was read."
                        }
                    }
                }
            }
        } catch (_: Exception) { /* Never crash receiving a malformed peer packet */ }
        if (remaining > 0) mesh.broadcast(forwardingCopy)
        revision++
    }
    fun saveIncident(notes: String, officerId: String, location: String) {
        require(notes.isNotBlank())
        vault.append("incidents", JSONObject().put("notes", notes.take(3000))
            .put("officerDetails", officerId.take(500)).put("location", location.take(500))
            .put("time", System.currentTimeMillis()).put("verification", "USER_RECORDED_NOT_VERIFIED"))
        revision++
        notice = "Incident recorded in encrypted local vault."
    }
    fun addMedia(source: File, kind: String, ocr: String = "") {
        vault.saveMedia(source, kind, ocr)
        revision++
        notice = "$kind encrypted locally. Not copied to a trusted device."
    }
    private val importStage = File(context.filesDir, "pending-evidence.crb")
    fun hasPendingImport(): Boolean = importStage.exists()
    fun prepareEvidenceBundle(mediaId: String, contactKey: String): File =
        EvidenceBundle.export(context, vault, mediaId, contactKey)
    fun savePreparedBundle(uri: Uri, file: File) {
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("Could not open destination")
            notice = "Encrypted evidence file saved. Transfer it to the trusted contact. This is NOT a delivery receipt."
        } finally { file.delete() }
    }
    fun stageEvidenceImport(uri: Uri) {
        val part = File(context.filesDir, "pending-evidence.partial")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(32768)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= EvidenceBundle.MAX_BYTES + 65_536) { "Evidence bundle too large" }
                        output.write(buf, 0, n)
                    }
                }
            } ?: error("Could not open selected file")
            check(part.renameTo(importStage)) { "Cannot stage encrypted import" }
            notice = "Encrypted file staged. Unlock and explicitly import it under Evidence."
        } finally { part.delete() }
    }
    fun acceptPendingEvidenceImport() {
        check(importStage.exists()) { "No pending file" }
        try {
            val record = EvidenceBundle.import(context, vault, importStage)
            notice = "Encrypted evidence from verified contact imported: ${record.optString("kind")}."
            revision++
        } finally { importStage.delete() }
    }
    fun shutdown() { mesh.shutdown(); vault.lock() }
}
