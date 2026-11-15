package com.shangjin.frameecho.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shangjin.frameecho.app.ui.player.framestep.FrameStepRepeatSchedule
import com.shangjin.frameecho.app.ui.player.framestep.RepeatStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testScope = TestScope(UnconfinedTestDispatcher() + Job())
    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tmpFolder.newFile("app_settings_test.preferences_pb") }
        )
    }
    private val store by lazy { AppSettingsStore(dataStore) }

    @Test
    fun `defaults start on the welcome screen with the default repeat curve`() = testScope.runTest {
        assertEquals(AppSettings(), store.settings.first())
    }

    @Test
    fun `start screen, repeat and recents filter persist`() = testScope.runTest {
        val schedule = FrameStepRepeatSchedule(300, listOf(RepeatStage(0, 8f), RepeatStage(2_000, 24f)))

        store.setStartScreen(StartScreen.IN_APP_GALLERY)
        store.setHoldToRepeatEnabled(false)
        store.setRepeatSchedule(schedule)
        store.setRecentsOnlyWithSavedFrames(true)

        assertEquals(
            AppSettings(StartScreen.IN_APP_GALLERY, false, schedule, true),
            store.settings.first()
        )
    }

    @Test
    fun `invalid schedules are refused and corrupt stored values fall back to the default`() = testScope.runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.setRepeatSchedule(FrameStepRepeatSchedule(500, emptyList()))
            }
        }
        dataStore.edit { it[stringPreferencesKey("frame_step_repeat_schedule")] = "v1;500;100:5" }
        dataStore.edit { it[stringPreferencesKey("start_screen")] = "SPLASH" }

        val settings = store.settings.first()
        assertEquals(FrameStepRepeatSchedule.DEFAULT, settings.repeatSchedule)
        assertEquals(StartScreen.WELCOME, settings.startScreen)
    }

    @Test
    fun `precision dial settings persist alongside the other settings`() = testScope.runTest {
        val dial = PrecisionDialSettings(
            initialGuideOpacity = 0.8f,
            restingGuideOpacity = 0.3f,
            draggingGuideOpacity = 0.1f,
            guideReturnDelayMs = 2_500L,
            guideColorArgb = 0xFFFFEB3B.toInt(),
            framesPerTurn = 120
        )
        store.setRecentsOnlyWithSavedFrames(true)
        store.setPrecisionDialSettings(dial)

        val settings = store.settings.first()
        assertEquals(dial, settings.precisionDial)
        assertEquals(true, settings.recentsOnlyWithSavedFrames)

        store.setPrecisionDialSettings(dial.copy(guideColorArgb = null))
        assertEquals(null, store.settings.first().precisionDial.guideColorArgb)
    }

    @Test
    fun `invalid dial settings are refused and corrupt stored fields fall back one by one`() = testScope.runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.setPrecisionDialSettings(PrecisionDialSettings(initialGuideOpacity = 0.3f, restingGuideOpacity = 0.3f))
            }
        }
        dataStore.edit {
            it[floatPreferencesKey("precision_dial_initial_opacity")] = 0.9f
            it[floatPreferencesKey("precision_dial_resting_opacity")] = 0.95f
            it[floatPreferencesKey("precision_dial_dragging_opacity")] = Float.NaN
            it[longPreferencesKey("precision_dial_return_delay_ms")] = -5L
            it[intPreferencesKey("precision_dial_frames_per_turn")] = 0
        }

        val dial = store.settings.first().precisionDial
        assertEquals(
            PrecisionDialSettings(initialGuideOpacity = 0.9f, restingGuideOpacity = PrecisionDialSettings.DEFAULT_RESTING_OPACITY),
            dial
        )
        assertEquals(true, dial.isValid)
    }

    @Test
    fun `a stored resting opacity at or above a low initial opacity is halved from it`() = testScope.runTest {
        dataStore.edit {
            it[floatPreferencesKey("precision_dial_initial_opacity")] = 0.2f
            it[floatPreferencesKey("precision_dial_resting_opacity")] = 0.2f
        }

        val dial = store.settings.first().precisionDial
        assertEquals(0.1f, dial.restingGuideOpacity)
        assertEquals(true, dial.isValid)
    }
}
