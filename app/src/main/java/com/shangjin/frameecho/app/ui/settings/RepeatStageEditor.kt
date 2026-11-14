package com.shangjin.frameecho.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.components.TooltipWrapper
import com.shangjin.frameecho.app.ui.player.framestep.ScheduleDraft
import com.shangjin.frameecho.app.ui.player.framestep.ScheduleIssue
import com.shangjin.frameecho.app.ui.player.framestep.ScheduleProblem

@Composable
internal fun RepeatStageEditor(
    index: Int,
    stage: ScheduleDraft.StageDraft,
    issues: List<ScheduleIssue>,
    onStageChange: (ScheduleDraft.StageDraft) -> Unit,
    onRemove: (() -> Unit)?
) {
    val startIssues = issues.filter { it.problem in setOf(
        ScheduleProblem.FIRST_STAGE_NOT_AT_ZERO, ScheduleProblem.START_OUT_OF_RANGE, ScheduleProblem.START_NOT_INCREASING
    ) }
    val speedIssues = issues.filter { it.problem == ScheduleProblem.FPS_OUT_OF_RANGE }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.app_settings_stage_title, index + 1),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            val removeLabel = stringResource(R.string.app_settings_remove_stage, index + 1)
            TooltipWrapper(label = removeLabel) {
                IconButton(onClick = { onRemove?.invoke() }, enabled = onRemove != null) {
                    Icon(Icons.Outlined.Delete, contentDescription = removeLabel)
                }
            }
        }
        OutlinedTextField(
            value = stage.startAfterSeconds,
            onValueChange = { onStageChange(stage.copy(startAfterSeconds = it)) },
            label = { Text(stringResource(R.string.app_settings_stage_start_seconds)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            isError = startIssues.isNotEmpty(),
            supportingText = { startIssues.forEach { ScheduleIssueText(it) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        )
        OutlinedTextField(
            value = stage.framesPerSecond,
            onValueChange = { onStageChange(stage.copy(framesPerSecond = it)) },
            label = { Text(stringResource(R.string.app_settings_stage_frames_per_second)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            isError = speedIssues.isNotEmpty(),
            supportingText = { speedIssues.forEach { ScheduleIssueText(it) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        )
        issues.filter { it.problem == ScheduleProblem.NOT_A_NUMBER }.forEach { ScheduleIssueText(it) }
    }
}
