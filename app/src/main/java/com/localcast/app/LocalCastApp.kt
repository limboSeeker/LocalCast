package com.localcast.app

import android.app.Application
import com.localcast.app.server.PinGuard
import com.localcast.app.settings.SettingsRepository

class LocalCastApp : Application() {
    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    /**
     * Process-wide so the PIN and viewer tokens survive stopping/restarting the broadcast:
     * viewers resume automatically. "New PIN" (regenerate) is the way to revoke access.
     */
    val pinGuard: PinGuard by lazy { PinGuard() }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: LocalCastApp
            private set
    }
}
