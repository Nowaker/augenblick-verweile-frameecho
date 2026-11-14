package com.shangjin.frameecho.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
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
}
