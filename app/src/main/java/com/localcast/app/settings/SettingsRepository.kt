package com.localcast.app.settings

import java.io.IOException
import kotlinx.coroutines.flow.catch
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ResolutionMode { P720, P1080, NATIVE }

enum class FpsMode { AUTO, FPS_30, FPS_60 }

enum class Priority {
    /** Keep frame rate, lower resolution/quality under pressure. */
    SMOOTH,

    /** Keep resolution (sharp text), drop frame rate under pressure. */
    SHARP,
}

data class StreamSettings(
    val resolution: ResolutionMode = ResolutionMode.P1080,
    val fps: FpsMode = FpsMode.AUTO,
    /** 0 = automatic, otherwise Mbit/s (2..30). */
    val bitrateMbps: Int = 0,
    /** Sharp by default: slides, documents and app screens matter more than motion. */
    val priority: Priority = Priority.SHARP,
    val requirePin: Boolean = true,
    val port: Int = DEFAULT_PORT,
    /** Pause the broadcast while the screen is off or locked (the lock screen shows notifications). */
    val pauseOnLock: Boolean = true,
) {
    companion object {
        const val DEFAULT_PORT = 8080
        val BITRATE_CHOICES = listOf(0, 2, 4, 6, 8, 12, 16, 20, 30)
    }
}

// A corrupted file (e.g. power loss during a write) is replaced with defaults instead of crashing
// every start of the app and of casting.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val resolution = stringPreferencesKey("resolution")
        val fps = stringPreferencesKey("fps")
        val bitrate = intPreferencesKey("bitrate_mbps")
        val priority = stringPreferencesKey("priority")
        val requirePin = booleanPreferencesKey("require_pin")
        val port = intPreferencesKey("port")
        val pauseOnLock = booleanPreferencesKey("pause_on_lock")
    }

    val settings: Flow<StreamSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val d = StreamSettings()
            StreamSettings(
                resolution = p[Keys.resolution].toEnum(d.resolution),
                fps = p[Keys.fps].toEnum(d.fps),
                bitrateMbps = (p[Keys.bitrate] ?: d.bitrateMbps).coerceIn(0, 30),
                priority = p[Keys.priority].toEnum(d.priority),
                requirePin = p[Keys.requirePin] ?: d.requirePin,
                port = (p[Keys.port] ?: d.port).coerceIn(1024, 65535),
                pauseOnLock = p[Keys.pauseOnLock] ?: d.pauseOnLock,
            )
        }
        .distinctUntilChanged()

    suspend fun current(): StreamSettings = settings.first()

    suspend fun update(transform: (StreamSettings) -> StreamSettings) {
        context.dataStore.edit { p ->
            val d = StreamSettings()
            val old = StreamSettings(
                resolution = p[Keys.resolution].toEnum(d.resolution),
                fps = p[Keys.fps].toEnum(d.fps),
                bitrateMbps = p[Keys.bitrate] ?: d.bitrateMbps,
                priority = p[Keys.priority].toEnum(d.priority),
                requirePin = p[Keys.requirePin] ?: d.requirePin,
                port = p[Keys.port] ?: d.port,
                pauseOnLock = p[Keys.pauseOnLock] ?: d.pauseOnLock,
            )
            val new = transform(old)
            p[Keys.resolution] = new.resolution.name
            p[Keys.fps] = new.fps.name
            p[Keys.bitrate] = new.bitrateMbps.coerceIn(0, 30)
            p[Keys.priority] = new.priority.name
            p[Keys.requirePin] = new.requirePin
            p[Keys.port] = new.port.coerceIn(1024, 65535)
            p[Keys.pauseOnLock] = new.pauseOnLock
        }
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
