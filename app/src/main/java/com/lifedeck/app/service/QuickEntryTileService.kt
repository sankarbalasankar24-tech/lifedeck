package com.lifedeck.app.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class QuickEntryTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "LifeDeck Quick Entry"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, FloatingBubbleService::class.java).apply {
            action = FloatingBubbleService.ACTION_SHOW_QUICK_ENTRY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
