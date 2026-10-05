package de.droh.stempeluhr.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import de.droh.stempeluhr.engine.StampEngine

/**
 * Kachel in den Schnelleinstellungen: ein Tipp stempelt manuell Kommen bzw. Gehen
 * (je nach letztem NFC-/Manuell-Eintrag des Tages). Funktioniert auch auf dem Sperrbildschirm.
 */
class StampTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val type = StampEngine.precise(this, StampSource.MANUELL, StampEngine.Mode.TOGGLE)
        if (type != null) {
            Toast.makeText(applicationContext, "${type.label} erfasst (Kachel)", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val next = StampEngine.nextToggleType(this)
        tile.label = "Stempeln: ${next.label}"
        tile.subtitle = if (next == StampType.OUT) "Anwesend" else "Abwesend"
        tile.state = if (next == StampType.OUT) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
