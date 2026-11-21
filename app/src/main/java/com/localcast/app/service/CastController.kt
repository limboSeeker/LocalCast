package com.localcast.app.service

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.core.content.ContextCompat

/** UI-facing commands for the broadcast. State is observed via [CastStateStore.state]. */
object CastController {
    internal const val ACTION_START = "com.localcast.app.action.START"
    internal const val ACTION_STOP = "com.localcast.app.action.STOP"
    internal const val ACTION_REGENERATE_PIN = "com.localcast.app.action.REGENERATE_PIN"
    internal const val ACTION_PAUSE = "com.localcast.app.action.PAUSE"
    internal const val ACTION_RESUME = "com.localcast.app.action.RESUME"
    internal const val EXTRA_RESULT_CODE = "result_code"
    internal const val EXTRA_RESULT_DATA = "result_data"

    /** Intent for the system "start recording / casting?" consent dialog. Launch with StartActivityForResult. */
    fun createProjectionIntent(context: Context): Intent {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        return mpm.createScreenCaptureIntent()
    }

    /** Call with the consent result (resultCode == Activity.RESULT_OK). Starts the foreground service. */
    fun start(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, CaptureService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        if (!CastStateStore.state.value.isActive) return
        context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
    }

    /** Viewers see a "paused" card (and black frames) until [resume]. */
    fun pause(context: Context) {
        if (!CastStateStore.state.value.isActive) return
        context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_PAUSE))
    }

    fun resume(context: Context) {
        if (!CastStateStore.state.value.isActive) return
        context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_RESUME))
    }

    /** New PIN; all existing viewer tokens are revoked (viewers must enter the new PIN). */
    fun regeneratePin(context: Context) {
        if (!CastStateStore.state.value.isActive) return
        context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_REGENERATE_PIN))
    }
}
