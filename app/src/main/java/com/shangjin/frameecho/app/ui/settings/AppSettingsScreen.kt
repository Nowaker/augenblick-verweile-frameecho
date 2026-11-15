package com.shangjin.frameecho.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.settings.AppSettings
import com.shangjin.frameecho.app.settings.StartScreen
import com.shangjin.frameecho.app.settings.PrecisionDialSettings
import com.shangjin.frameecho.app.ui.components.TooltipWrapper
import com.shangjin.frameecho.app.ui.player.framestep.FrameStepRepeatSchedule
import com.shangjin.frameecho.app.ui.player.framestep.ScheduleDraft

private enum class SettingsDestination { BACK, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    settings: AppSettings,
    onStartScreenChange: (StartScreen) -> Unit,
    onHoldToRepeatEnabledChange: (Boolean) -> Unit,
    onRepeatScheduleChange: (FrameStepRepeatSchedule) -> Unit,
    onPrecisionDialChange: (PrecisionDialSettings) -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateBack: () -> Unit
) {
    var draft by rememberSaveable(stateSaver = ScheduleDraftSaver) {
        mutableStateOf(ScheduleDraft.from(settings.repeatSchedule))
    }
    var observedDraft by rememberSaveable(stateSaver = ScheduleDraftSaver) {
        mutableStateOf(ScheduleDraft.from(settings.repeatSchedule))
    }
    var pendingDestination by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }
    val hasUnsavedChanges = draft != ScheduleDraft.from(settings.repeatSchedule)
    LaunchedEffect(settings.repeatSchedule) {
        val current = ScheduleDraft.from(settings.repeatSchedule)
        draft = draft.reconcileSavedDraft(observedDraft, current)
        observedDraft = current
    }

    fun navigate(destination: SettingsDestination) {
        when (destination) {
            SettingsDestination.BACK -> onNavigateBack()
            SettingsDestination.ABOUT -> onNavigateToAbout()
        }
    }
    fun requestNavigation(destination: SettingsDestination) {
        if (hasUnsavedChanges) pendingDestination = destination else navigate(destination)
    }
    BackHandler { requestNavigation(SettingsDestination.BACK) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_settings_title)) },
                navigationIcon = {
                    TooltipWrapper(label = stringResource(R.string.go_back)) {
                        IconButton(onClick = { requestNavigation(SettingsDestination.BACK) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
                .imePadding().verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 16.dp, vertical = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StartScreenSelector(settings.startScreen, onStartScreenChange)
            HoldToRepeatSwitch(settings.holdToRepeatEnabled, onHoldToRepeatEnabledChange)
            if (!settings.holdToRepeatEnabled) SettingsHint(stringResource(R.string.app_settings_repeat_disabled_hint))
            HorizontalDivider()
            RepeatScheduleEditor(draft, { draft = it }, settings.repeatSchedule) { schedule ->
                onRepeatScheduleChange(schedule)
                draft = ScheduleDraft.from(schedule)
            }
            HorizontalDivider()
            PrecisionDialSettingsSection(settings.precisionDial, onPrecisionDialChange)
            HorizontalDivider()
            SettingsAboutRow { requestNavigation(SettingsDestination.ABOUT) }
        }
    }
    pendingDestination?.let { destination ->
        DiscardScheduleDialog(
            onKeepEditing = { pendingDestination = null },
            onDiscard = {
                draft = ScheduleDraft.from(settings.repeatSchedule)
                pendingDestination = null
                navigate(destination)
            }
        )
    }
}
