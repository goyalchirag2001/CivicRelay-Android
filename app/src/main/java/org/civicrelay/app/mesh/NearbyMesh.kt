package org.civicrelay.app.mesh

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** All discovery is explicitly started/stopped by the user. No background discovery service. */
class NearbyMesh(context: Context, private val onPacket: (JSONObject) -> Unit,
                 private val pending: () -> List<JSONObject>,
                 private val identity: () -> PeerIdentity?,
                 private val trusted: (String) -> Boolean,
                 private val onImport: (File) -> Boolean,
                 private val onStored: (String, String) -> Unit) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    val evidence = DirectEvidence(context, client, identity, trusted, onImport, onStored)
    private val executor = Executors.newSingleThreadExecutor()
    private val connected = LinkedHashSet<String>()
    private val attempted = HashSet<String>()
    private val limiter = IngressLimiter()
    private val peerLimit = 8
    private val serviceId = "org.civicrelay.app.mesh.v1"
    var status by mutableStateOf("Local sharing stopped")
        private set
    var peerCount by mutableIntStateOf(0)
        private set
    var active by mutableStateOf(false)
        private set

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (!active) return
            if (payload.type == Payload.Type.FILE) {
                evidence.onFileReceived(endpointId, payload)
                return
            }
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (!limiter.allow(endpointId, bytes.size, SystemClock.elapsedRealtime())) return
            executor.execute {
                try {
                    val json = JSONObject(String(bytes, Charsets.UTF_8))
                    if (!evidence.handle(endpointId, json)) onPacket(json)
                } catch (_: Exception) { /* Ignore malformed/untrusted network content */ }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            evidence.onTransfer(endpointId, update)
        }
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Public peers are not trusted identities. Payloads have independent signatures and
            // SOS envelopes are encrypted for manually verified contacts only.
            if (!active || synchronized(connected) { connected.size >= peerLimit }) {
                client.rejectConnection(endpointId)
                return
            }
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { status = "Nearby connection could not be accepted" }
        }
        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (!active) {
                client.disconnectFromEndpoint(endpointId)
                return
            }
            if (resolution.status.isSuccess) {
                val admitted = synchronized(connected) {
                    if (connected.size >= peerLimit) false
                    else { connected.add(endpointId); peerCount = connected.size; true }
                }
                if (!admitted) { client.disconnectFromEndpoint(endpointId); return }
                status = "Sharing with $peerCount nearby device(s); delivery not guaranteed"
                evidence.connected(endpointId)
                // Bound replay volume per new connection. Previously queued traffic remains local.
                pending().asSequence().filter { it.optInt("hops") > 0 }.take(32)
                    .forEach { sendTo(endpointId, it) }
            } else attempted.remove(endpointId)
        }
        override fun onDisconnected(endpointId: String) {
            synchronized(connected) { connected.remove(endpointId); peerCount = connected.size }
            attempted.remove(endpointId)
            limiter.forget(endpointId)
            evidence.disconnected(endpointId)
            status = "Nearby devices: $peerCount"
        }
    }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!active || attempted.size >= 16) return
            if (attempted.add(endpointId)) {
                client.requestConnection("CivicRelay", endpointId, lifecycle)
                    .addOnFailureListener { attempted.remove(endpointId) }
            }
        }
        override fun onEndpointLost(endpointId: String) { attempted.remove(endpointId) }
    }
    fun start() {
        if (active) return
        active = true
        status = "Starting nearby radio discovery..."
        client.startAdvertising("CivicRelay", serviceId, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener {
                stop()
                status = "Advertising unavailable. Check Nearby permissions and enable Bluetooth/Wi-Fi."
            }
        client.startDiscovery(serviceId, discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener {
                stop()
                status = "Discovery unavailable. Check Nearby permissions and enable Bluetooth/Wi-Fi."
            }
    }
    fun broadcast(packet: JSONObject) {
        if (!active || packet.optInt("hops") <= 0) return
        synchronized(connected) { connected.toList() }.forEach { sendTo(it, packet) }
    }
    private fun sendTo(endpointId: String, packet: JSONObject) {
        val bytes = packet.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size <= 16_384) client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnFailureListener { /* Retry when another connection is established */ }
    }
    fun stop() {
        active = false
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        synchronized(connected) { connected.clear(); peerCount = 0 }
        attempted.clear()
        limiter.clear()
        evidence.stop()
        status = "Local sharing stopped"
    }
    fun shutdown() { stop(); evidence.shutdown(); executor.shutdown() }
}
