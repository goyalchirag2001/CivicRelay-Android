package org.civicrelay.app

import android.content.Context
import android.util.AtomicFile
import org.civicrelay.app.crypto.Envelope
import org.json.JSONObject
import java.io.File

/** Pre-authorized ONE-TIME encrypted SOS. Contains NO current GPS and NO private keys.
 * Must be freshly armed from the unlocked vault; packets expire after two hours.
 * This is a convenience path and is NOT a guaranteed emergency delivery service.
 */
class EmergencyArming(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "armed-sos.json"))
    private val triggerLog = AtomicFile(File(context.filesDir, "quick-sos-trigger.json"))
    @Synchronized fun arm(packet: JSONObject) {
        require(packet.optString("type") == "sos" && Envelope.valid(packet))
        val output = file.startWrite()
        try { output.write(packet.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    @Synchronized fun clear() { file.baseFile.delete() }
    @Synchronized fun logQueued(packet: JSONObject) {
        val data = JSONObject().put("id", packet.getString("id"))
            .put("created", System.currentTimeMillis())
        val output = triggerLog.startWrite()
        try { output.write(data.toString().toByteArray(Charsets.UTF_8)); triggerLog.finishWrite(output) }
        catch (e: Exception) { triggerLog.failWrite(output); throw e }
    }
    @Synchronized fun takeQueued(): JSONObject? = try {
        JSONObject(triggerLog.openRead().bufferedReader().use { it.readText() }).also {
            triggerLog.baseFile.delete()
        }
    } catch (_: Exception) { null }
    @Synchronized fun ready(): Boolean = load() != null
    @Synchronized fun expiry(): Long = load()?.optLong("expires") ?: 0L
    @Synchronized fun consume(): JSONObject? {
        val packet = load() ?: return null
        // Delete BEFORE returning to avoid duplicate triggers from notification/tile.
        clear()
        return packet
    }
    private fun load(): JSONObject? = try {
        val result = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        if (result.optString("type") == "sos" && Envelope.valid(result)) result else null
    } catch (_: Exception) { null }
}
