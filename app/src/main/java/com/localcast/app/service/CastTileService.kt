package com.localcast.app.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.localcast.app.R
import com.localcast.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile "LocalCast": active while broadcasting (tap stops), inactive otherwise
 * (tap opens the app, which asks for screen-capture consent straight away).
 *
 * Shares the process with [CaptureService], so it simply observes [CastStateStore] while visible.
 */
class CastTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { CastStateStore.state.collect(::render) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val state = CastStateStore.state.value
        when {
            state.isActive -> CastController.stop(this)
            state.phase == CastPhase.STOPPING -> Unit
            isLocked -> unlockAndRun { openAndStart() }
            else -> openAndStart()
        }
    }

    private fun openAndStart() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTOSTART, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, REQUEST_OPEN, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: CastState) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_cast)
        tile.state = when {
            state.isActive -> Tile.STATE_ACTIVE
            state.phase == CastPhase.STOPPING -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when (state.phase) {
            CastPhase.LIVE -> if (state.viewers.isEmpty()) {
                getString(R.string.tile_subtitle_live)
            } else {
                getString(R.string.tile_subtitle_viewers, state.viewers.size)
            }
            CastPhase.STARTING -> getString(R.string.tile_subtitle_starting)
            else -> getString(R.string.tile_subtitle_off)
        }
        tile.updateTile()
    }

    private companion object {
        const val REQUEST_OPEN = 10
    }
}
