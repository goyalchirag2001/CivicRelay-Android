package org.civicrelay.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.civicrelay.app.crypto.Crypto
import org.civicrelay.app.crypto.Envelope
import org.civicrelay.app.mesh.NearbyMesh
import org.civicrelay.app.mesh.PeerIdentity
import org.civicrelay.app.storage.RelayQueue
import org.civicrelay.app.storage.Vault
import org.civicrelay.app.storage.EvidenceBundle
import org.civicrelay.app.storage.EvidenceReader
import org.civicrelay.app.storage.VerifiedEvidence
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class CivicService(private val context: Context) {
    val vault = Vault(context)
    private val queue = RelayQueue(context)
    val mesh = NearbyMesh(context, ::receivePacket, queue::all,
        { if (vault.unlocked) PeerIdentity(vault.pub(), vault.privateKey()) else null },
        { key -> vault.unlocked && vault.contacts().any { it.second == key } },
        ::importNearbyArchive, ::onEvidenceStored)
    var revision by mutableStateOf(0)
        private set
    var notice by mutableStateOf("Prototype: no guaranteed delivery. Never rely on it as your only emergency option.")
        private set
    @Volatile private var lockGeneration = 0L
    private var lastPost = 0L
    // Deliberately independent of a Compose screen's cancellable coroutine scope.
    private val captureWriter = Executors.newSingleThreadExecutor()

    @Synchronized fun unlock(passphrase: String): Boolean = try {
        val chars = passphrase.toCharArray()
        try { if (vault.exists) vault.unlock(chars) else vault.create(chars) }
        finally { chars.fill('\u0000') }
        revision++
        true
    } catch (_: Exception) { notice = "Could not open vault. Check passphrase and storage integrity."; false }

    @Synchronized fun lock() { lockGeneration++; mesh.stop(); vault.lock(); revision++ }

    /** Fully authenticates GCM and SHA-256 before handing any media to a viewer. */
    fun openVerifiedEvidence(mediaId: String): VerifiedEvidence {
        val generation = lockGeneration
        check(vault.unlocked) { "Unlock the vault before viewing evidence" }
        val opened = EvidenceReader.open(vault, mediaId)
        if (!vault.unlocked || generation != lockGeneration) {
            opened.close()
            error("Vault locked during evidence verification")
        }
        return opened
    }

    /** Explicit user-directed SAF export. Never stages a plaintext file in app cache. */
    fun exportVerifiedPlaintext(mediaId: String, destination: Uri) {
        val generation = lockGeneration
        val opened = openVerifiedEvidence(mediaId)
        var completed = false
        try {
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                var offset = 0
                while (offset < opened.bytes.size) {
                    check(vault.unlocked && generation == lockGeneration) {
                        "Vault locked; decrypted export interrupted"
                    }
                    val count = minOf(32 * 1024, opened.bytes.size - offset)
                    output.write(opened.bytes, offset, count)
                    offset += count
                }
                output.flush()
            } ?: error("Could not open selected destination")
            check(vault.unlocked && generation == lockGeneration) { "Vault locked during export" }
            completed = true
            notice = "Decrypted copy exported to your selected location. Outside the vault it is unprotected."
        } finally {
            opened.close()
            if (!completed) {
                // SAF CreateDocument made a fresh destination. Remove incomplete plaintext if possible.
                try { context.contentResolver.delete(destination, null, null) } catch (_: Exception) { }
            }
        }
    }
    @Synchronized fun addContact(alias: String, code: String) {
        val key = code.trim().removePrefix("CRCONTACT1:")
        vault.addContact(alias, key)
        revision++
        notice = "Trusted key saved. Verify fingerprint with your contact in person."
    }
    fun contactCode(): String = "CRCONTACT1:${vault.pub()}"
    @Synchronized fun post(text: String) {
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
    @Synchronized fun sendSos(message: String, latitude: Double?, longitude: Double?, accuracy: Float?) {
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
    @Synchronized private fun receivePacket(packet: JSONObject) {
        if (!vault.unlocked || !Envelope.valid(packet)) return
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
    @Synchronized fun saveIncident(notes: String, officerId: String, location: String) {
        require(notes.isNotBlank())
        vault.append("incidents", JSONObject().put("notes", notes.take(3000))
            .put("officerDetails", officerId.take(500)).put("location", location.take(500))
            .put("time", System.currentTimeMillis()).put("verification", "USER_RECORDED_NOT_VERIFIED"))
        revision++
        notice = "Incident recorded in encrypted local vault."
    }
    @Synchronized fun addMedia(source: File, kind: String, ocr: String = "") {
        // If background locking beats the CameraX finalize/OCR callbacks, discard plaintext.
        try {
            check(vault.unlocked) { "Vault locked before capture could be encrypted" }
            vault.saveMedia(source, kind, ocr)
            revision++
            notice = "$kind encrypted locally. Not copied to a trusted device."
        } finally { source.delete() }
    }
    fun persistCapturedMedia(source: File, kind: String, ocr: String = "") {
        // A scope tied to the Evidence screen is cancelled as soon as the app locks.
        // Always run (or safely discard) a capture on a lifecycle-independent executor.
        try {
            captureWriter.execute {
                try { addMedia(source, kind, ocr) }
                catch (_: Exception) {
                    source.delete()
                    notice = "Capture was not saved (vault locked or storage unavailable)."
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            source.delete()
        }
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
    @Synchronized fun acceptPendingEvidenceImport() {
        check(importStage.exists()) { "No pending file" }
        try {
            val record = EvidenceBundle.import(context, vault, importStage)
            notice = "Encrypted evidence from verified contact imported: ${record.optString("kind")}."
            revision++
        } finally { importStage.delete() }
    }
    /** Called after a directly paired trusted peer's approved encrypted FILE transfer completes. */
    @Synchronized private fun importNearbyArchive(file: File): Boolean {
        if (!vault.unlocked) return false
        val record = EvidenceBundle.import(context, vault, file) // verifies recipient/signature/GCM/hash
        revision++
        notice = "Trusted contact evidence encrypted copy verified locally: ${record.optString("kind")}."
        return true
    }
    private fun onEvidenceStored(id: String, recipient: String) {
        if (!vault.unlocked) return
        vault.recordEvidenceStored(id, recipient)
        revision++
        notice = "Trusted contact app confirmed verified local storage of an encrypted copy."
    }
    fun offerNearbyEvidence(mediaId: String, contactKey: String) {
        check(mesh.active) { "Start nearby sharing on both phones first" }
        check(mesh.evidence.trustedEndpointFor(contactKey)) {
            "Selected trusted contact is not authenticated directly nearby"
        }
        val archive = prepareEvidenceBundle(mediaId, contactKey)
        val transferId = mesh.evidence.offerArchive(contactKey, archive)
        vault.logEvidenceOffer(transferId, mediaId, contactKey, archive.length())
        revision++
    }
    @Synchronized fun shutdown() { lockGeneration++; mesh.shutdown(); captureWriter.shutdown(); vault.lock() }
}
