package org.civicrelay.app.mesh

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.common.api.ApiException
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
    private var session = 0L
    private val serviceId = "org.civicrelay.app.mesh.v1"
    enum class Mode { ADVERTISE, DISCOVER }
    var mode by mutableStateOf<Mode?>(null)
        private set
    var foundCount by mutableIntStateOf(0)
        private set
    var diagnostics by mutableStateOf("No radio activity yet")
        private set
    private fun explain(error: Exception): String = when (error) {
        is ApiException -> "Nearby code ${error.statusCode}: ${error.localizedMessage ?: "Google Play services rejected operation"}"
        else -> "${error.javaClass.simpleName}: ${error.localizedMessage ?: "Unknown error"}"
    }
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
                .addOnFailureListener { status = "Connection acceptance failed"; diagnostics = explain(it) }
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
            } else { attempted.remove(endpointId); diagnostics = "Connection rejected: code ${resolution.status.statusCode}"; status = diagnostics }
        }
        override fun onDisconnected(endpointId: String) {
            synchronized(connected) { connected.remove(endpointId); peerCount = connected.size }
            attempted.remove(endpointId)
            limiter.forget(endpointId)
            evidence.disconnected(endpointId)
            status = "Connected peers: $peerCount"
        }
    }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!active || mode != Mode.DISCOVER || attempted.size >= 16) return
            foundCount++
            diagnostics = "Found ${info.endpointName}. Requesting connection…"
            if (attempted.add(endpointId)) {
                client.requestConnection("CivicRelay", endpointId, lifecycle)
                    .addOnSuccessListener { status = "Pairing with nearby peer…" }
                    .addOnFailureListener { error ->
                        attempted.remove(endpointId)
                        diagnostics = "Connection request: ${explain(error)}"
                        status = "Could not connect to found device"
                    }
            }
        }
        override fun onEndpointLost(endpointId: String) { attempted.remove(endpointId) }
    }
    /** A single, explicit radio role avoids simultaneous start failures on some devices.
     * Once linked, communication is bidirectional regardless of the initial role.
     */
    fun startAsAdvertiser() = start(Mode.ADVERTISE)
    fun startAsDiscoverer() = start(Mode.DISCOVER)
    private fun start(selected: Mode) {
        if (active) stop()
        active = true
        val currentSession = ++session
        mode = selected
        foundCount = 0
        diagnostics = "Starting ${selected.name.lowercase()} on Google Play services…"
        status = if (selected == Mode.ADVERTISE) "Making phone discoverable…" else "Searching nearby phones…"
        if (selected == Mode.ADVERTISE) {
            client.startAdvertising("CivicRelay", serviceId, lifecycle,
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnSuccessListener {
                    if (!active || session != currentSession) return@addOnSuccessListener
                    diagnostics = "Advertising active; start Find on the second phone"
                    status = "Discoverable to CivicRelay phones nearby"
                }
                .addOnFailureListener { error ->
                    if (!active || session != currentSession) return@addOnFailureListener
                    diagnostics = "Advertising: ${explain(error)}"
                    stop()
                    status = "Advertising failed — see diagnostic below"
                }
        } else {
            client.startDiscovery(serviceId, discovery,
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnSuccessListener {
                    if (!active || session != currentSession) return@addOnSuccessListener
                    diagnostics = "Discovery active; waiting for advertising peers"
                    status = "Scanning nearby…"
                }
                .addOnFailureListener { error ->
                    if (!active || session != currentSession) return@addOnFailureListener
                    diagnostics = "Discovery: ${explain(error)}"
                    stop()
                    status = "Discovery failed — see diagnostic below"
                }
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
        session++
        mode = null
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        synchronized(connected) { connected.clear(); peerCount = 0 }
        attempted.clear()
        limiter.clear()
        evidence.stop()
        status = "Local sharing stopped"
    }
    fun shutdown() { stop(); evidence.shutdown(); executor.shutdown() }
}
