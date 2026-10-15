package org.civicrelay.app.crypto

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import java.security.spec.MGF1ParameterSpec

/** Experimental crypto primitives: require independent security audit before at-risk use. */
object Crypto {
    private val random = SecureRandom()
    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)
    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun fingerprint(key: String): String = hex(sha256(decode(key))).take(40)

    fun derive(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, 310_000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }

    fun seal(key: SecretKey, clear: ByteArray): ByteArray {
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(clear)
    }
    fun unseal(key: SecretKey, sealed: ByteArray): ByteArray {
        require(sealed.size >= 29) { "Encrypted record is incomplete" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return cipher.doFinal(sealed.copyOfRange(12, sealed.size))
    }

    fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072, random) }.generateKeyPair()
    fun publicKey(encoded: String): PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decode(encoded)))
    fun privateKey(encoded: String): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decode(encoded)))
    private fun oaep() = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
    fun wrapKey(recipient: PublicKey, rawKey: ByteArray): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, recipient, oaep())
        return b64(cipher.doFinal(rawKey))
    }
    fun unwrapKey(privateKey: PrivateKey, wrapped: String): SecretKeySpec {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey, oaep())
        return SecretKeySpec(cipher.doFinal(decode(wrapped)), "AES")
    }

    fun sign(privateKey: PrivateKey, bytes: ByteArray): String = Signature.getInstance("SHA256withRSA").run {
        initSign(privateKey)
        update(bytes)
        b64(sign())
    }
    fun verify(key: PublicKey, bytes: ByteArray, signature: String): Boolean = try {
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key); update(bytes); verify(decode(signature))
        }
    } catch (_: Exception) { false }

    /** Stream-encrypt large media, use a fresh IV and single-use ciphertext file. */
    fun encryptFile(source: File, destination: File, key: SecretKey): String {
        val iv = randomBytes(12)
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(destination).use { output ->
            output.write("CRV1".toByteArray(Charsets.US_ASCII))
            output.write(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            CipherOutputStream(output, cipher).use { encrypted ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        encrypted.write(buffer, 0, count)
                    }
                }
            }
        }
        return hex(digest.digest())
    }
}

/** Fields excluded from signature: hop count (relays can decrement it). */
object Envelope {
    private fun canonical(j: JSONObject): ByteArray {
        val recipients = j.optJSONObject("recipients") ?: JSONObject()
        val ordered = recipients.keys().asSequence().sorted().joinToString("|") { "$it=${recipients.getString(it)}" }
        return listOf("CR1", j.getString("id"), j.getString("type"), j.getLong("created").toString(),
            j.getLong("expires").toString(), j.getString("sender"), j.getString("payload"), ordered)
            .joinToString("|").toByteArray(Charsets.UTF_8)
    }
    fun compose(type: String, clearText: String, pub: String, privateKey: PrivateKey, recipientKeys: List<String>): JSONObject {
        require(type == "bulletin" || type == "sos" || type == "receipt" || type == "checkin")
        require(clearText.toByteArray().size <= 2048)
        require(recipientKeys.size <= 10)
        val now = System.currentTimeMillis()
        val messageId = java.util.UUID.randomUUID().toString()
        val expires = now + if (type != "bulletin") 2 * 60 * 60 * 1000L else 60 * 60 * 1000L
        val wrapped = JSONObject()
        // Fresh outer signing identity per message: public posts and relay-observable SOS
        // packets do NOT reveal the user's persistent trusted-contact identity.
        val outer = Crypto.rsaKeyPair()
        val outerPub = Crypto.b64(outer.public.encoded)
        val payload = if (type == "sos" || type == "receipt" || type == "checkin") {
            require(recipientKeys.isNotEmpty())
            // Sender identity and proof are inside recipient-encrypted data only.
            val proofBytes = "$messageId|$now|$expires|$clearText".toByteArray(Charsets.UTF_8)
            val inner = JSONObject().put("sender", pub).put("body", clearText)
                .put("proof", Crypto.sign(privateKey, proofBytes))
            val bytes = Crypto.randomBytes(32)
            try {
                recipientKeys.forEachIndexed { index, recipient ->
                    wrapped.put("r$index", Crypto.wrapKey(Crypto.publicKey(recipient), bytes))
                }
                Crypto.b64(Crypto.seal(SecretKeySpec(bytes, "AES"), inner.toString().toByteArray(Charsets.UTF_8)))
            } finally { bytes.fill(0) }
        } else Crypto.b64(clearText.toByteArray(Charsets.UTF_8))
        val message = JSONObject().put("id", messageId)
            .put("type", type).put("created", now)
            .put("expires", expires)
            .put("hops", 3).put("sender", outerPub).put("recipients", wrapped).put("payload", payload)
        return message.put("sig", Crypto.sign(outer.private, canonical(message)))
    }
    fun valid(packet: JSONObject): Boolean = try {
        val now = System.currentTimeMillis()
        val created = packet.getLong("created")
        val expires = packet.getLong("expires")
        val hops = packet.getInt("hops")
        val type = packet.getString("type")
        if (type !in setOf("bulletin", "sos", "receipt", "checkin") || created > now + 120_000 ||
            expires <= now || expires - created > 7_200_000 || hops !in 0..3 ||
            packet.getJSONObject("recipients").length() > 10 ||
            packet.getString("id").length > 64 || packet.getString("sender").length > 1024 ||
            packet.getString("payload").length > 4096) false
        else Crypto.verify(Crypto.publicKey(packet.getString("sender")), canonical(packet), packet.getString("sig"))
    } catch (_: Exception) { false }
    fun readBulletin(packet: JSONObject): String = String(Crypto.decode(packet.getString("payload")), Charsets.UTF_8).take(2048)
    data class VerifiedSos(val senderPublicKey: String, val body: String)
    fun tryOpenPrivate(packet: JSONObject, privateKey: PrivateKey): VerifiedSos? {
        val recipients = try { packet.getJSONObject("recipients") } catch (_: Exception) { return null }
        // Anonymous envelope slots prevent revealing recipient public-key fingerprints to relays.
        val entries = recipients.keys().asSequence().take(10).toList()
        for (slot in entries) {
            try {
                val key = Crypto.unwrapKey(privateKey, recipients.getString(slot))
                val clear = Crypto.unseal(key, Crypto.decode(packet.getString("payload")))
                val inner = JSONObject(String(clear, Charsets.UTF_8))
                val body = inner.getString("body")
                val sender = inner.getString("sender")
                val proofBytes = "${packet.getString("id")}|${packet.getLong("created")}|${packet.getLong("expires")}|$body".toByteArray(Charsets.UTF_8)
                if (Crypto.verify(Crypto.publicKey(sender), proofBytes, inner.getString("proof")))
                    return VerifiedSos(sender, body)
            } catch (_: Exception) { /* Another recipient's key; try the next slot */ }
        }
        return null
    }
    fun tryOpenSos(packet: JSONObject, privateKey: PrivateKey): VerifiedSos? =
        if (packet.optString("type") == "sos") tryOpenPrivate(packet, privateKey) else null
}
