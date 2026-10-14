package org.civicrelay.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.civicrelay.app.crypto.Crypto
import org.civicrelay.app.crypto.Envelope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnvelopeInstrumentedTest {
    @Test fun onlyIntendedRecipientCanOpenSos() {
        val sender = Crypto.rsaKeyPair()
        val recipient = Crypto.rsaKeyPair()
        val other = Crypto.rsaKeyPair()
        val senderPub = Crypto.b64(sender.public.encoded)
        val packet = Envelope.compose("sos", "{\"message\":\"Help\"}", senderPub,
            sender.private, listOf(Crypto.b64(recipient.public.encoded)))
        assertTrue(Envelope.valid(packet))
        val opened = Envelope.tryOpenSos(packet, recipient.private)
        assertNotNull(opened)
        assertEquals(senderPub, opened?.senderPublicKey)
        assertEquals("{\"message\":\"Help\"}", opened?.body)
        assertNull(Envelope.tryOpenSos(packet, other.private))
        assertFalse(packet.toString().contains(senderPub)) // stable sender identity is inside ciphertext
    }

    @Test fun alteredPacketIsRejected() {
        val sender = Crypto.rsaKeyPair()
        val original = Envelope.compose("bulletin", "Local road blocked (unverified)",
            Crypto.b64(sender.public.encoded), sender.private, emptyList())
        val altered = JSONObject(original.toString()).put("payload", Crypto.b64("fabricated".toByteArray()))
        assertTrue(Envelope.valid(original))
        assertFalse(Envelope.valid(altered))
    }

    @Test fun passphraseDerivedKeyCanOpenOwnRecord() {
        val salt = Crypto.randomBytes(16)
        val password = "test passphrase 16 chars".toCharArray()
        val key = Crypto.derive(password, salt)
        val encrypted = Crypto.seal(key, "example".toByteArray())
        assertEquals("example", String(Crypto.unseal(Crypto.derive(password, salt), encrypted)))
        password.fill('\u0000')
    }
    @Test fun receiptCanOnlyBeReadBySender() {
        val sender = Crypto.rsaKeyPair()
        val trusted = Crypto.rsaKeyPair()
        val stranger = Crypto.rsaKeyPair()
        val reply = Envelope.compose("receipt", "{\"for\":\"example-id\"}",
            Crypto.b64(trusted.public.encoded), trusted.private,
            listOf(Crypto.b64(sender.public.encoded)))
        assertTrue(Envelope.valid(reply))
        assertEquals(Crypto.b64(trusted.public.encoded),
            Envelope.tryOpenPrivate(reply, sender.private)?.senderPublicKey)
        assertNull(Envelope.tryOpenPrivate(reply, stranger.private))
    }

}
