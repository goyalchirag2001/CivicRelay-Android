package org.civicrelay.app.mesh

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import org.civicrelay.app.crypto.Crypto
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.UUID
import java.util.concurrent.Executors

/** Keys are loaded only while the owning vault is unlocked. */
data class PeerIdentity(val publicKey: String, val privateKey: PrivateKey)

/**
 * Direct, explicitly approved, recipient-encrypted archive transfer.
 * No FILE payload is relayed, copied to untrusted peers, or accepted without an approved offer.
 * The Nearby service temporarily writes encrypted ciphertext into system-managed storage.
 * Experimental protocol: NOT independently security-audited.
 */
class DirectEvidence(
    private val context: Context,
    private val client: ConnectionsClient,
    private val identity: () -> PeerIdentity?,
    private val isTrusted: (String) -> Boolean,
    private val importArchive: (File) -> Boolean,
    private val onVerifiedStored: (String, String) -> Unit
) {
    companion object {
        const val MAX_ARCHIVE_BYTES = 50L * 1024 * 1024 + 65_536
        private const val PROTOCOL = "CRX1"
        private const val CONTROL_LIMIT = 8192
        private const val OFFER_TTL = 5 * 60 * 1000L

        private fun digest(file: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(32_768)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    sha.update(bytes, 0, n)
                }
            }
            return Crypto.hex(sha.digest())
        }
        /** Canonical, field-delimited signature input avoids JSON member ordering differences. */
        private fun body(j: JSONObject): ByteArray = listOf(
            PROTOCOL, j.getString("kind"), j.getString("id"), j.getString("sender"),
            j.getString("recipient"), j.getLong("created").toString(),
            j.optLong("size", 0L).toString(), j.optString("hash"),
            j.optLong("payloadId", 0L).toString()
        ).joinToString("|").toByteArray(Charsets.UTF_8)
    }

    data class IncomingOffer(val id: String, val sender: String, val bytes: Long)
    private data class Outgoing(
        val id: String, val endpoint: String, val recipient: String, val archive: File,
        val size: Long, val hash: String, val created: Long,
        var payload: Payload? = null, var started: Boolean = false
    )
    private data class Incoming(
        val id: String, val endpoint: String, val sender: String, val size: Long,
        val hash: String, val created: Long, var approved: Boolean = false,
        var payloadId: Long? = null, var payload: Payload? = null
    )

    var status by mutableStateOf("No evidence transfer active")
        private set
    var progress by mutableIntStateOf(0)
        private set
    var offer by mutableStateOf<IncomingOffer?>(null)
        private set
    var trustedNearby by mutableIntStateOf(0)
        private set

    private val worker = Executors.newSingleThreadExecutor()
    private val challenges = HashMap<String, String>()
    private val challengeAnswered = HashSet<String>()
    private val verified = HashMap<String, String>()
    private var outbound: Outgoing? = null
    private var inbound: Incoming? = null
    private var busy = false

    private fun send(endpoint: String, j: JSONObject) {
        val bytes = j.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size <= CONTROL_LIMIT) {
            client.sendPayload(endpoint, Payload.fromBytes(bytes))
                .addOnFailureListener { status = "Could not send evidence transfer control message" }
        }
    }
    private fun signed(kind: String, id: String, peer: String, size: Long = 0,
                       hash: String = "", payloadId: Long = 0): JSONObject {
        val me = requireNotNull(identity()) { "Unlock vault before sharing evidence" }
        val j = JSONObject().put("wire", PROTOCOL).put("kind", kind).put("id", id)
            .put("sender", me.publicKey).put("recipient", peer)
            .put("created", System.currentTimeMillis()).put("size", size)
            .put("hash", hash).put("payloadId", payloadId)
        return j.put("sig", Crypto.sign(me.privateKey, body(j)))
    }
    private fun authenticated(j: JSONObject, peer: String, kind: String): Boolean {
        return try {
            val me = identity() ?: return false
            val created = j.getLong("created")
            j.getString("wire") == PROTOCOL && j.getString("kind") == kind &&
                j.getString("sender") == peer && j.getString("recipient") == me.publicKey &&
                kotlin.math.abs(System.currentTimeMillis() - created) <= OFFER_TTL &&
                Crypto.verify(Crypto.publicKey(peer), body(j), j.getString("sig"))
        } catch (_: Exception) { false }
    }

    /** Challenge contains no persistent identity; the signed response does reveal one. */
    @Synchronized fun connected(endpoint: String) {
        if (identity() == null) return
        val nonce = Crypto.hex(Crypto.randomBytes(24))
        challenges[endpoint] = nonce
        challengeAnswered.remove(endpoint)
        send(endpoint, JSONObject().put("wire", PROTOCOL).put("kind", "challenge")
            .put("nonce", nonce))
    }
    @Synchronized fun disconnected(endpoint: String) {
        challenges.remove(endpoint)
        challengeAnswered.remove(endpoint)
        verified.remove(endpoint)
        trustedNearby = verified.size
        if (outbound?.endpoint == endpoint || inbound?.endpoint == endpoint) {
            fail("Trusted peer disconnected; encrypted transfer incomplete")
        }
    }
    @Synchronized fun trustedEndpointFor(publicKey: String): Boolean = verified.values.any { it == publicKey }

    /** Called for BYTES payloads. Only controls with the protocol marker are consumed. */
    @Synchronized fun handle(endpoint: String, json: JSONObject): Boolean {
        if (json.optString("wire") != PROTOCOL) return false
        try {
            if (json.toString().toByteArray(Charsets.UTF_8).size > CONTROL_LIMIT) return true
            when (val kind = json.getString("kind")) {
                "challenge" -> {
                    val me = identity() ?: return true
                    val nonce = json.getString("nonce")
                    if (nonce.length != 48 || !nonce.matches(Regex("[0-9a-f]{48}")) ||
                        !challengeAnswered.add(endpoint)) return true
                    val j = JSONObject().put("wire", PROTOCOL).put("kind", "proof")
                        .put("nonce", nonce).put("sender", me.publicKey)
                    val bytes = "$PROTOCOL|proof|$nonce|${me.publicKey}".toByteArray(Charsets.UTF_8)
                    send(endpoint, j.put("sig", Crypto.sign(me.privateKey, bytes)))
                }
                "proof" -> {
                    val nonce = challenges[endpoint] ?: return true
                    val key = json.getString("sender")
                    if (nonce != json.optString("nonce") || !isTrusted(key)) return true
                    val bytes = "$PROTOCOL|proof|$nonce|$key".toByteArray(Charsets.UTF_8)
                    if (Crypto.verify(Crypto.publicKey(key), bytes, json.getString("sig"))) {
                        verified[endpoint] = key
                        trustedNearby = verified.size
                        status = "Authenticated nearby trusted contact available"
                    }
                }
                else -> {
                    val peer = verified[endpoint] ?: return true
                    if (!authenticated(json, peer, kind)) return true
                    when (kind) {
                        "offer" -> onOffer(endpoint, peer, json)
                        "accept" -> onAccept(endpoint, json)
                        "reject" -> onReject(json)
                        "start" -> onStart(endpoint, json)
                        "ready" -> onReady(endpoint, json)
                        "stored" -> onStored(json)
                    }
                }
            }
        } catch (_: Exception) { /* Bad or stale peer message: never crash UI */ }
        return true
    }

    /** Expensive bundle export is performed by CivicService on an I/O thread first. */
    @Synchronized fun offerArchive(recipient: String, archive: File): String {
        try {
            check(identity() != null) { "Unlock vault before transferring evidence" }
            check(isTrusted(recipient)) { "Select a verified trusted contact" }
            check(inbound == null && outbound == null && !busy) { "Another transfer is active" }
            val endpoint = verified.entries.firstOrNull { it.value == recipient }?.key
                ?: error("That trusted contact is not directly connected nearby")
            require(archive.isFile && archive.length() in 1..MAX_ARCHIVE_BYTES) { "Invalid encrypted archive size" }
            val id = UUID.randomUUID().toString()
            val item = Outgoing(id, endpoint, recipient, archive, archive.length(), digest(archive),
                System.currentTimeMillis())
            outbound = item
            progress = 0
            send(endpoint, signed("offer", id, recipient, item.size, item.hash))
            status = "Encrypted copy offered; waiting for recipient approval"
            return id
        } catch (e: Exception) {
            archive.delete()
            throw e
        }
    }

    private fun onOffer(endpoint: String, peer: String, j: JSONObject) {
        if (outbound != null || inbound != null || busy) return
        val size = j.getLong("size")
        val hash = j.getString("hash")
        val id = j.getString("id")
        if (!EvidenceTransferPolicy.validOffer(id, size, hash)) return
        inbound = Incoming(id, endpoint, peer, size, hash, System.currentTimeMillis())
        offer = IncomingOffer(id, peer, size)
        progress = 0
        status = "Trusted contact offered encrypted evidence; your approval is required"
    }
    @Synchronized fun approve(id: String) {
        val entry = inbound ?: error("No incoming offer")
        check(entry.id == id && !entry.approved && identity() != null)
        check(EvidenceTransferPolicy.freeSpaceEnough(context.filesDir.usableSpace, entry.size)) {
            "Insufficient local storage for verifying encrypted evidence"
        }
        entry.approved = true
        offer = null
        send(entry.endpoint, signed("accept", id, entry.sender))
        status = "Approved; awaiting encrypted evidence"
    }
    @Synchronized fun decline(id: String) {
        val entry = inbound ?: return
        if (entry.id == id) {
            send(entry.endpoint, signed("reject", id, entry.sender))
            inbound = null
            offer = null
            status = "Encrypted evidence offer declined"
        }
    }
    private fun onAccept(endpoint: String, j: JSONObject) {
        val entry = outbound ?: return
        if (entry.endpoint != endpoint || entry.id != j.optString("id") || entry.payload != null ||
            System.currentTimeMillis() - entry.created > OFFER_TTL) return
        val payload = Payload.fromFile(entry.archive)
        entry.payload = payload
        send(endpoint, signed("start", entry.id, entry.recipient, entry.size, entry.hash, payload.id))
        status = "Recipient accepted. Preparing encrypted file transfer"
    }
    private fun onReject(j: JSONObject) {
        val entry = outbound ?: return
        if (entry.id == j.optString("id")) fail("Recipient declined evidence transfer")
    }
    private fun onStart(endpoint: String, j: JSONObject) {
        val entry = inbound ?: return
        if (!entry.approved || entry.endpoint != endpoint ||
            !EvidenceTransferPolicy.matches(entry.id, entry.size, entry.hash,
                j.optString("id"), j.optLong("size"), j.optString("hash")) ||
            entry.payloadId != null ||
            System.currentTimeMillis() - entry.created > OFFER_TTL) return
        entry.payloadId = j.getLong("payloadId")
        send(endpoint, signed("ready", entry.id, entry.sender, entry.size, entry.hash, entry.payloadId!!))
        status = "Receiving encrypted file from trusted contact"
    }
    private fun onReady(endpoint: String, j: JSONObject) {
        val entry = outbound ?: return
        val payload = entry.payload ?: return
        if (entry.endpoint != endpoint || entry.started ||
            !EvidenceTransferPolicy.matches(entry.id, entry.size, entry.hash,
                j.optString("id"), j.optLong("size"), j.optString("hash")) ||
            payload.id != j.optLong("payloadId")) return
        entry.started = true
        client.sendPayload(endpoint, payload).addOnFailureListener {
            fail("Nearby rejected encrypted file transfer")
        }
        status = "Transferring recipient-encrypted archive"
    }

    /** Reject unsolicited FILE payloads before allowing any large-data processing. */
    @Synchronized fun onFileReceived(endpoint: String, payload: Payload) {
        val entry = inbound
        if (identity() == null || entry == null || !entry.approved ||
            entry.endpoint != endpoint || entry.payloadId != payload.id || entry.payload != null ||
            payload.asFile()?.size?.let { it > MAX_ARCHIVE_BYTES || it != entry.size } != false) {
            client.cancelPayload(payload.id)
            cleanupReceived(payload)
            return
        }
        entry.payload = payload
        status = "Receiving encrypted archive (not yet verified)"
    }
    @Synchronized fun onTransfer(endpoint: String, update: PayloadTransferUpdate) {
        val outgoing = outbound
        val incoming = inbound
        val isOutgoing = outgoing?.endpoint == endpoint && outgoing.payload?.id == update.payloadId
        val isIncoming = incoming?.endpoint == endpoint && incoming.payloadId == update.payloadId
        if (!isOutgoing && !isIncoming) return
        if (update.totalBytes > 0L) {
            progress = EvidenceTransferPolicy.percent(update.bytesTransferred, update.totalBytes)
        }
        when (update.status) {
            PayloadTransferUpdate.Status.SUCCESS -> {
                if (isIncoming) {
                    val copy = incoming ?: return
                    val received = copy.payload ?: run { fail("Received incomplete transfer metadata"); return }
                    if (busy) return
                    busy = true
                    worker.execute { verifyAndStore(copy, received) }
                } else {
                    status = "Radio transfer complete; waiting for verified storage receipt"
                }
            }
            PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                fail("Encrypted transfer interrupted or cancelled")
            }
        }
    }
    private fun verifyAndStore(entry: Incoming, payload: Payload) {
        val temp = File(context.cacheDir, "${UUID.randomUUID()}.received-crb")
        try {
            val uri: Uri = requireNotNull(payload.asFile()?.asUri())
            var total = 0L
            val hash = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32_768)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_ARCHIVE_BYTES) { "Archive exceeds local limit" }
                        hash.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("Cannot open received encrypted archive")
            check(total == entry.size && Crypto.hex(hash.digest()) == entry.hash) {
                "Encrypted transfer length or checksum mismatch"
            }
            check(importArchive(temp)) { "Vault locked before archive import" }
            synchronized(this) {
                // Never issue a receipt if this offer has been cancelled or the vault locked.
                if (inbound?.id == entry.id && identity() != null) {
                    send(entry.endpoint, signed("stored", entry.id, entry.sender, entry.size, entry.hash))
                    status = "Verified encrypted evidence stored on this device; not human-reviewed"
                    inbound = null
                    offer = null
                }
            }
        } catch (_: Exception) {
            synchronized(this) { status = "Evidence transfer failed verification or import"; inbound = null; offer = null }
        } finally {
            cleanupReceived(payload)
            temp.delete()
            synchronized(this) { busy = false }
        }
    }
    private fun onStored(j: JSONObject) {
        val entry = outbound ?: return
        if (!EvidenceTransferPolicy.matches(entry.id, entry.size, entry.hash,
                j.optString("id"), j.optLong("size"), j.optString("hash"))) return
        status = "Trusted contact app verified and stored this encrypted copy; not proof of human review"
        try { onVerifiedStored(entry.id, entry.recipient) } catch (_: Exception) { }
        outbound = null
        entry.payload?.close()
        entry.archive.delete()
    }
    private fun cleanupReceived(payload: Payload) {
        try { payload.asFile()?.asUri()?.let { context.contentResolver.delete(it, null, null) } }
        catch (_: Exception) { /* Android/provider may remove this Uri asynchronously */ }
        try { payload.close() } catch (_: Exception) { }
    }
    @Synchronized private fun fail(reason: String) {
        status = reason
        val outgoing = outbound
        outbound = null
        incomingCleanup()
        if (outgoing != null) {
            outgoing.payload?.let { try { client.cancelPayload(it.id); it.close() } catch (_: Exception) {} }
            outgoing.archive.delete()
        }
        progress = 0
    }
    private fun incomingCleanup() {
        val received = inbound?.payload
        inbound = null; offer = null
        received?.let { client.cancelPayload(it.id); cleanupReceived(it) }
    }
    @Synchronized fun stop() {
        fail("Evidence transfer stopped: app locked or nearby sharing disabled")
        challenges.clear(); challengeAnswered.clear(); verified.clear(); trustedNearby = 0
    }
    fun shutdown() { stop(); worker.shutdown() }
}
