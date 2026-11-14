package com.shangjin.frameecho.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shangjin.frameecho.app.ui.player.framestep.FrameStepRepeatSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the app shows on launch when it was not opened with a file. */
enum class StartScreen { WELCOME, IN_APP_GALLERY }

data class AppSettings(
    val startScreen: StartScreen = StartScreen.WELCOME,
    val holdToRepeatEnabled: Boolean = true,
    val repeatSchedule: FrameStepRepeatSchedule = FrameStepRepeatSchedule.DEFAULT,
    val recentsOnlyWithSavedFrames: Boolean = false
)

private val Context.appSettingsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "app_settings")

/**
 * App-wide preferences that are not export options: start screen, frame-step repeat and
 * the recent-files filter. Export options stay in `PlayerPreferencesStore`.
 */
class AppSettingsStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.appSettingsDataStore)

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            startScreen = prefs[KEY_START_SCREEN]
                ?.let { name -> StartScreen.entries.firstOrNull { it.name == name } }
                ?: StartScreen.WELCOME,
            holdToRepeatEnabled = prefs[KEY_HOLD_TO_REPEAT] ?: true,
            repeatSchedule = FrameStepRepeatSchedule.deserialize(prefs[KEY_REPEAT_SCHEDULE])
                ?: FrameStepRepeatSchedule.DEFAULT,
            recentsOnlyWithSavedFrames = prefs[KEY_RECENTS_SAVED_ONLY] ?: false
        )
    }.distinctUntilChanged()

    suspend fun setStartScreen(screen: StartScreen) {
        dataStore.edit { it[KEY_START_SCREEN] = screen.name }
    }

    suspend fun setHoldToRepeatEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_HOLD_TO_REPEAT] = enabled }
    }

    /** Invalid schedules are rejected rather than stored, so a bad edit can't break stepping. */
    suspend fun setRepeatSchedule(schedule: FrameStepRepeatSchedule) {
        require(schedule.isValid) { "Refusing to store an invalid repeat schedule" }
        dataStore.edit { it[KEY_REPEAT_SCHEDULE] = schedule.serialize() }
    }

    suspend fun setRecentsOnlyWithSavedFrames(enabled: Boolean) {
        dataStore.edit { it[KEY_RECENTS_SAVED_ONLY] = enabled }
    }

    private companion object {
        val KEY_START_SCREEN = stringPreferencesKey("start_screen")
        val KEY_HOLD_TO_REPEAT = booleanPreferencesKey("frame_step_hold_to_repeat")
        val KEY_REPEAT_SCHEDULE = stringPreferencesKey("frame_step_repeat_schedule")
        val KEY_RECENTS_SAVED_ONLY = booleanPreferencesKey("recents_only_with_saved_frames")
    }
}
