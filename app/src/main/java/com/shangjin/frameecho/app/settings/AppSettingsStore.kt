package com.shangjin.frameecho.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    val recentsOnlyWithSavedFrames: Boolean = false,
    val precisionDial: PrecisionDialSettings = PrecisionDialSettings.DEFAULT
)

private val Context.appSettingsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "app_settings")

/**
 * App-wide preferences that are not export options: start screen, frame-step repeat, the
 * recent-files filter and the precision dial. Export options stay in `PlayerPreferencesStore`.
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
            recentsOnlyWithSavedFrames = prefs[KEY_RECENTS_SAVED_ONLY] ?: false,
            precisionDial = PrecisionDialSettings.fromStored(
                initialGuideOpacity = prefs[KEY_DIAL_INITIAL_OPACITY],
                restingGuideOpacity = prefs[KEY_DIAL_RESTING_OPACITY],
                draggingGuideOpacity = prefs[KEY_DIAL_DRAGGING_OPACITY],
                guideReturnDelayMs = prefs[KEY_DIAL_RETURN_DELAY_MS],
                guideColorArgb = prefs[KEY_DIAL_GUIDE_COLOR],
                framesPerTurn = prefs[KEY_DIAL_FRAMES_PER_TURN]
            )
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

    /** Invalid dial settings are rejected rather than stored, like repeat schedules. */
    suspend fun setPrecisionDialSettings(settings: PrecisionDialSettings) {
        require(settings.isValid) { "Refusing to store invalid precision dial settings" }
        dataStore.edit { prefs ->
            prefs[KEY_DIAL_INITIAL_OPACITY] = settings.initialGuideOpacity
            prefs[KEY_DIAL_RESTING_OPACITY] = settings.restingGuideOpacity
            prefs[KEY_DIAL_DRAGGING_OPACITY] = settings.draggingGuideOpacity
            prefs[KEY_DIAL_RETURN_DELAY_MS] = settings.guideReturnDelayMs
            prefs[KEY_DIAL_FRAMES_PER_TURN] = settings.framesPerTurn
            val color = settings.guideColorArgb
            if (color == null) prefs.remove(KEY_DIAL_GUIDE_COLOR) else prefs[KEY_DIAL_GUIDE_COLOR] = color
        }
    }

    private companion object {
        val KEY_START_SCREEN = stringPreferencesKey("start_screen")
        val KEY_HOLD_TO_REPEAT = booleanPreferencesKey("frame_step_hold_to_repeat")
        val KEY_REPEAT_SCHEDULE = stringPreferencesKey("frame_step_repeat_schedule")
        val KEY_RECENTS_SAVED_ONLY = booleanPreferencesKey("recents_only_with_saved_frames")
        val KEY_DIAL_INITIAL_OPACITY = floatPreferencesKey("precision_dial_initial_opacity")
        val KEY_DIAL_RESTING_OPACITY = floatPreferencesKey("precision_dial_resting_opacity")
        val KEY_DIAL_DRAGGING_OPACITY = floatPreferencesKey("precision_dial_dragging_opacity")
        val KEY_DIAL_RETURN_DELAY_MS = longPreferencesKey("precision_dial_return_delay_ms")
        val KEY_DIAL_GUIDE_COLOR = intPreferencesKey("precision_dial_guide_color")
        val KEY_DIAL_FRAMES_PER_TURN = intPreferencesKey("precision_dial_frames_per_turn")
    }
}
