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
    val biometrics = org.civicrelay.app.crypto.BiometricVaultKey(context)
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
        replayPrivateInbox()
        mesh.refreshTrustedHandshakes()
        revision++
        true
    } catch (_: Exception) { notice = "Could not open vault. Check passphrase and storage integrity."; false }

    /** Lock sensitive data without switching off the separately authorized Nearby radio service. */
    @Synchronized fun lock() { lockGeneration++; vault.lock(); revision++ }

    @Synchronized fun unlockWithBiometric(cipher: javax.crypto.Cipher): Boolean = try {
        val (derived, salt) = biometrics.completeUnlock(cipher)
        try { vault.unlockDerived(derived, salt) }
        finally { derived.fill(0); salt.fill(0) }
        replayPrivateInbox()
        mesh.refreshTrustedHandshakes()
        revision++
        true
    } catch (_: Exception) {
        notice = "Fingerprint unlock unavailable. Use your vault passphrase."; false
    }

    @Synchronized fun enableBiometric(cipher: javax.crypto.Cipher) {
        check(vault.unlocked)
        val (derived, salt) = vault.currentKeyForBiometrics()
        try { biometrics.completeEnroll(cipher, derived, salt) }
        finally { derived.fill(0); salt.fill(0) }
        revision++
    }

    @Synchronized fun disableBiometric() { biometrics.disable(); revision++ }

    /** When locked, only bounded encrypted public relay storage/forwarding is allowed. */
    private fun replayPrivateInbox() {
        if (!vault.unlocked) return
        queue.all().filter { it.optString("type") in setOf("sos", "receipt", "checkin") }
            .takeLast(150).forEach { processPrivate(it) }
    }

    private fun processPrivate(packet: JSONObject) {
        if (!vault.unlocked || !Envelope.valid(packet)) return
        try {
            when (packet.getString("type")) {
                "sos", "checkin" -> {
                    val clear = Envelope.tryOpenPrivate(packet, vault.privateKey()) ?: return
                    val trusted = vault.contacts().find { it.second == clear.senderPublicKey } ?: return
                    if (vault.records("sosInbox").any { it.optString("id") == packet.optString("id") }) return
                    val body = JSONObject(clear.body)
                    vault.append("sosInbox", JSONObject().put("id", packet.getString("id"))
                        .put("sender", trusted.first).put("message", body.optString("message"))
                        .put("kind", packet.optString("type"))
                        .put("location", if (body.has("latitude"))
                            "${body.optDouble("latitude")},${body.optDouble("longitude")}" else "Unavailable")
                        .put("time", body.optLong("time")).put("status", "RECEIVED_ON_THIS_DEVICE"))
                    val receipt = Envelope.compose("receipt",
                        JSONObject().put("for", packet.getString("id"))
                            .put("receivedAt", System.currentTimeMillis()).toString(),
                        vault.pub(), vault.privateKey(), listOf(clear.senderPublicKey))
                    queue.add(receipt); mesh.broadcast(receipt)
                }
                "receipt" -> {
                    val clear = Envelope.tryOpenPrivate(packet, vault.privateKey()) ?: return
                    if (!vault.contacts().any { it.second == clear.senderPublicKey }) return
                    if (vault.updateSosStatus(JSONObject(clear.body).getString("for"), clear.senderPublicKey))
                        notice = "Trusted app confirmed SOS receipt; this does not mean anyone read it."
                }
            }
        } catch (_: Exception) { /* Malformed encrypted payload must not break receiver. */ }
    }


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
    @Synchronized fun savePublicProfile(name: String, role: String, region: String) {
        vault.savePublicProfile(name, role, region)
        revision++
        notice = "Posting profile saved locally. Future public posts include these details."
    }
    @Synchronized fun post(text: String, category: String = "Community") {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 280)
        val now = System.currentTimeMillis()
        require(now - lastPost >= 20_000) { "Please wait before posting again" }
        require(category in setOf("Community", "Safety", "Help"))
        val profile = vault.publicProfile()
        val displayName = profile.optString("name").trim()
        require(displayName.length in 2..48) { "Set a public display name in More before posting" }
        val body = JSONObject().put("v", 1).put("text", trimmed).put("displayName", displayName)
            .put("role", profile.optString("role").take(32)).put("region", profile.optString("region").take(40))
            .put("category", category)
        val (postingPublic, postingPrivate) = vault.postingIdentity()
        val serializedBody = body.toString()
        val signedPost = JSONObject().put("v", 2).put("body", serializedBody)
            .put("authorPub", postingPublic)
            .put("authorSig", Crypto.sign(postingPrivate, ("CR-PUBLIC-v2|" + serializedBody).toByteArray(Charsets.UTF_8)))
        val p = Envelope.compose("bulletin", signedPost.toString(), vault.pub(), vault.privateKey(), emptyList())
        queue.add(p)
        vault.append("bulletins", JSONObject().put("text", trimmed).put("id", p.getString("id"))
            .put("sender", "You").put("time", now).put("unverified", true)
            .put("displayName", displayName).put("role", profile.optString("role"))
            .put("region", profile.optString("region")).put("category", category)
            .put("postingKeySignatureVerified", true))
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
            .put("time", System.currentTimeMillis()).put("kind", "sos")
            .put("status", "QUEUED_NOT_DELIVERED")
            .put("recipientKeys", org.json.JSONArray(contacts.map { it.second })))
        mesh.broadcast(packet)
        revision++
        notice = "SOS encrypted and queued. A trusted contact app may return an encrypted receipt when a route exists."
    }
    /** A separate, explicit consent action. Only verified contacts can decrypt. */
    @Synchronized fun sendLocationCheckIn(latitude: Double, longitude: Double, accuracy: Float?) {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        val recipients = vault.contacts()
        require(recipients.isNotEmpty()) { "Add a trusted contact first" }
        val now = System.currentTimeMillis()
        val content = JSONObject().put("message", "Voluntary location check-in")
            .put("time", now).put("latitude", latitude).put("longitude", longitude)
        if (accuracy != null && accuracy.isFinite()) content.put("accuracyMeters", accuracy.toDouble())
        val packet = Envelope.compose("checkin", content.toString(), vault.pub(),
            vault.privateKey(), recipients.map { it.second })
        queue.add(packet)
        vault.append("sosInbox", JSONObject().put("id", packet.getString("id"))
            .put("sender", "You").put("message", "Voluntary location check-in")
            .put("kind", "checkin").put("location", "$latitude,$longitude")
            .put("time", now).put("status", "QUEUED_NOT_DELIVERED")
            .put("recipientKeys", org.json.JSONArray(recipients.map { it.second })))
        mesh.broadcast(packet)
        revision++
        notice = "Location check-in encrypted for trusted contacts. It may remain queued if no route exists."
    }

    @Synchronized private fun receivePacket(packet: JSONObject) {
        if (!Envelope.valid(packet)) return
        val remaining = packet.optInt("hops") - 1
        val forwarded = JSONObject(packet.toString()).put("hops", remaining.coerceAtLeast(0))
        if (!queue.add(forwarded)) return
        // Vault can be locked and radio can keep forwarding signed public posts / encrypted SOS.
        // Decryption, identity, receipts and incident storage require an unlocked vault.
        if (vault.unlocked) {
            try {
                when (packet.optString("type")) {
                    "bulletin" -> {
                        val body = Envelope.readBulletin(packet)
                        val envelope = runCatching { JSONObject(body) }.getOrNull()
                        // Older text bulletins are displayed without claimed identity.
                        val isSignedProfile = envelope?.optInt("v") == 2
                        val posted = if (isSignedProfile) {
                            val signedBody = envelope!!.getString("body")
                            val authorKey = envelope.getString("authorPub")
                            val signature = envelope.getString("authorSig")
                            check(Crypto.verify(Crypto.publicKey(authorKey),
                                ("CR-PUBLIC-v2|" + signedBody).toByteArray(Charsets.UTF_8), signature)) {
                                "Invalid public posting signature"
                            }
                            JSONObject(signedBody)
                        } else null
                        val content = posted?.optString("text") ?: body
                        val signingKey = if (isSignedProfile) envelope!!.getString("authorPub")
                            else packet.getString("sender")
                        vault.append("bulletins", JSONObject().put("text", content.take(280))
                            .put("id", packet.getString("id"))
                            .put("sender", Crypto.fingerprint(signingKey).take(12))
                            .put("displayName", posted?.optString("displayName", "")?.take(48) ?: "")
                            .put("role", posted?.optString("role", "")?.take(32) ?: "")
                            .put("region", posted?.optString("region", "")?.take(40) ?: "")
                            .put("category", posted?.optString("category", "Community") ?: "Community")
                            .put("time", packet.getLong("created")).put("unverified", true)
                            .put("postingKeySignatureVerified", isSignedProfile))
                    }
                    "sos", "receipt", "checkin" -> processPrivate(packet)
                }
            } catch (_: Exception) { }
        }
        if (remaining > 0) mesh.broadcast(forwarded)
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
