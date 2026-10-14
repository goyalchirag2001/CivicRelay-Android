package org.civicrelay.app.mesh

import android.content.Context
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
import java.util.concurrent.Executors

/** All discovery is explicitly started/stopped by the user. No background discovery service. */
class NearbyMesh(context: Context, private val onPacket: (JSONObject) -> Unit,
                 private val pending: () -> List<JSONObject>) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val connected = LinkedHashSet<String>()
    private val attempted = HashSet<String>()
    private val serviceId = "org.civicrelay.app.mesh.v1"
    var status by mutableStateOf("Local sharing stopped")
        private set
    var peerCount by mutableIntStateOf(0)
        private set
    var active by mutableStateOf(false)
        private set

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (bytes.size > 16_384) return
            executor.execute {
                try { onPacket(JSONObject(String(bytes, Charsets.UTF_8))) }
                catch (_: Exception) { /* Ignore malformed/untrusted network content */ }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Public peers are not trusted identities. Payloads have independent signatures and
            // SOS envelopes are encrypted for manually verified contacts only.
            client.acceptConnection(endpointId, payloadCallback).addOnFailureListener { status = "Connection rejected" }
        }
        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                synchronized(connected) { connected.add(endpointId); peerCount = connected.size }
                status = "Sharing with $peerCount nearby device(s); delivery not guaranteed"
                pending().forEach { packet -> if (packet.optInt("hops") > 0) sendTo(endpointId, packet) }
            } else attempted.remove(endpointId)
        }
        override fun onDisconnected(endpointId: String) {
            synchronized(connected) { connected.remove(endpointId); peerCount = connected.size }
            attempted.remove(endpointId)
            status = "Nearby devices: $peerCount"
        }
    }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
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
            .addOnFailureListener { status = "Advertising unavailable: ${it.localizedMessage}" }
        client.startDiscovery(serviceId, discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { status = "Discovery unavailable: ${it.localizedMessage}" }
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
        status = "Local sharing stopped"
    }
    fun shutdown() { stop(); executor.shutdown() }
}
