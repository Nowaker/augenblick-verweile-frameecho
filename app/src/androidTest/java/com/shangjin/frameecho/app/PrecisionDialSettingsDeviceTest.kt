package com.shangjin.frameecho.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.settings.AppSettings
import com.shangjin.frameecho.app.settings.AppSettingsStore
import com.shangjin.frameecho.app.ui.settings.AppSettingsScreen
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Launches a UI; run only on a device reserved for foreground QA. */
@RunWith(AndroidJUnit4::class)
class PrecisionDialSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File
    private lateinit var storageScope: CoroutineScope
    private lateinit var store: AppSettingsStore

    @Before
    fun openStore() {
        file = File(temporary.root, "dial.preferences_pb")
        storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = AppSettingsStore(PreferenceDataStoreFactory.create(scope = storageScope, produceFile = { file }))
        compose.setContent {
            val settings by store.settings.collectAsState(initial = AppSettings())
            MaterialTheme {
                AppSettingsScreen(
                    settings = settings,
                    onStartScreenChange = {},
                    onHoldToRepeatEnabledChange = {},
                    onRepeatScheduleChange = {},
                    onPrecisionDialChange = { runBlocking { store.setPrecisionDialSettings(it) } },
                    onNavigateToAbout = {},
                    onNavigateBack = {}
                )
            }
        }
    }

    @After
    fun closeStore() {
        storageScope.cancel()
        runBlocking { storageScope.coroutineContext.job.join() }
    }

    @Test
    fun validPrefixFollowedByInvalidInputDoesNotChangeSavedSettings() {
        val frames = compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.precision_dial_frames_per_turn)))
        frames.performScrollTo().performTextReplacement("99")
        compose.mainClock.advanceTimeBy(1_000)

        frames.performTextReplacement("999")
        compose.mainClock.advanceTimeBy(1_000)

        assertEquals(60, runBlocking { store.settings.first().precisionDial.framesPerTurn })
    }

    @Test
    fun savedDialValueSurvivesReopeningThePreferenceStore() {
        val frames = compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.precision_dial_frames_per_turn)))
        frames.performScrollTo().performTextReplacement("30")
        compose.onNodeWithText(context.getString(R.string.precision_dial_save)).performScrollTo().performClick()
        compose.waitForIdle()
        storageScope.cancel()
        runBlocking { storageScope.coroutineContext.job.join() }

        storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = AppSettingsStore(PreferenceDataStoreFactory.create(scope = storageScope, produceFile = { file }))

        assertEquals(30, runBlocking { store.settings.first().precisionDial.framesPerTurn })
    }
}
