package com.shangjin.frameecho.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.player.framestep.FrameStepRepeatSchedule
import com.shangjin.frameecho.app.ui.player.framestep.ScheduleDraft
import com.shangjin.frameecho.app.ui.player.framestep.ScheduleProblem

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepeatScheduleEditor(
    draft: ScheduleDraft,
    onDraftChange: (ScheduleDraft) -> Unit,
    savedSchedule: FrameStepRepeatSchedule,
    onSave: (FrameStepRepeatSchedule) -> Unit
) {
    val result = draft.toSchedule()
    val issues = when (result) {
        is ScheduleDraft.Result.Valid -> emptyList()
        is ScheduleDraft.Result.Invalid -> result.issues
    }
    val isSaved = draft == ScheduleDraft.from(savedSchedule)
    val delayIssues = issues.filter { it.stageIndex == null && it.problem in setOf(
        ScheduleProblem.NOT_A_NUMBER, ScheduleProblem.INITIAL_DELAY_OUT_OF_RANGE
    ) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSectionTitle(stringResource(R.string.app_settings_repeat_schedule))
        SettingsHint(stringResource(R.string.app_settings_repeat_schedule_hint))
        OutlinedTextField(
            value = draft.initialDelaySeconds,
            onValueChange = { onDraftChange(draft.copy(initialDelaySeconds = it)) },
            label = { Text(stringResource(R.string.app_settings_initial_delay_seconds)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            isError = delayIssues.isNotEmpty(),
            supportingText = { delayIssues.forEach { ScheduleIssueText(it) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        )
        SettingsHint(stringResource(
            R.string.app_settings_schedule_limits,
            FrameStepRepeatSchedule.MAX_STAGES,
            FrameStepRepeatSchedule.MAX_STAGE_START_MS / 1000,
            FrameStepRepeatSchedule.MIN_FPS,
            FrameStepRepeatSchedule.MAX_FPS
        ))
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            draft.stages.forEachIndexed { index, stage ->
                RepeatStageEditor(
                    index = index,
                    stage = stage,
                    issues = issues.filter { it.stageIndex == index },
                    onStageChange = { replacement ->
                        onDraftChange(draft.copy(stages = draft.stages.mapIndexed { stageIndex, current ->
                            if (stageIndex == index) replacement else current
                        }))
                    },
                    onRemove = if (draft.stages.size > 1) ({ onDraftChange(draft.withoutStage(index)) }) else null
                )
            }
        }
        issues.filter { it.stageIndex == null && it.problem !in setOf(
            ScheduleProblem.NOT_A_NUMBER, ScheduleProblem.INITIAL_DELAY_OUT_OF_RANGE
        ) }.forEach { ScheduleIssueText(it) }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onDraftChange(draft.copy(stages = draft.stages + ScheduleDraft.StageDraft("", ""))) },
                enabled = draft.stages.size < FrameStepRepeatSchedule.MAX_STAGES,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.app_settings_add_stage)) }
            TextButton(
                onClick = { onDraftChange(ScheduleDraft.from(FrameStepRepeatSchedule.DEFAULT)) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.app_settings_reset_schedule)) }
        }
        Text(
            text = stringResource(if (isSaved) R.string.app_settings_schedule_saved else R.string.app_settings_schedule_unsaved),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Button(
            onClick = {
                when (val current = draft.toSchedule()) {
                    is ScheduleDraft.Result.Valid -> onSave(current.schedule)
                    is ScheduleDraft.Result.Invalid -> Unit
                }
            },
            enabled = result is ScheduleDraft.Result.Valid && !isSaved,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(stringResource(R.string.app_settings_save_schedule)) }
    }
}
