package org.civicrelay.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** User-added Quick Settings tile, explicitly armed from unlocked CivicRelay first. */
class EmergencyTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val armed = (application as CivicRelayApp).civic.emergency.ready()
        qsTile?.let { tile ->
            tile.label = if (armed) "CivicRelay SOS" else "SOS not armed"
            tile.state = if (armed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        val sent = runCatching {
            (application as CivicRelayApp).civic.triggerQuickSos()
        }.getOrDefault(false)
        qsTile?.let { tile ->
            tile.label = if (sent) "SOS queued" else "Open app to arm SOS"
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }
}
