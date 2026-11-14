package com.shangjin.frameecho.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.player.framestep.FrameStepRepeatSchedule
import com.shangjin.frameecho.app.ui.player.framestep.ScheduleIssue
import com.shangjin.frameecho.app.ui.player.framestep.ScheduleProblem

@Composable
internal fun ScheduleIssueText(issue: ScheduleIssue) {
    val message = when (issue.problem) {
        ScheduleProblem.INITIAL_DELAY_OUT_OF_RANGE -> stringResource(
            R.string.app_settings_error_delay_range, FrameStepRepeatSchedule.MAX_INITIAL_DELAY_MS / 1000
        )
        ScheduleProblem.NO_STAGES -> stringResource(R.string.app_settings_error_no_stages)
        ScheduleProblem.TOO_MANY_STAGES -> stringResource(
            R.string.app_settings_error_max_stages, FrameStepRepeatSchedule.MAX_STAGES
        )
        ScheduleProblem.FIRST_STAGE_NOT_AT_ZERO -> stringResource(R.string.app_settings_error_first_zero)
        ScheduleProblem.START_OUT_OF_RANGE -> stringResource(
            R.string.app_settings_error_start_range, FrameStepRepeatSchedule.MAX_STAGE_START_MS / 1000
        )
        ScheduleProblem.START_NOT_INCREASING -> stringResource(R.string.app_settings_error_stage_order)
        ScheduleProblem.FPS_OUT_OF_RANGE -> stringResource(
            R.string.app_settings_error_fps_range, FrameStepRepeatSchedule.MIN_FPS, FrameStepRepeatSchedule.MAX_FPS
        )
        ScheduleProblem.NOT_A_NUMBER -> stringResource(
            if (issue.stageIndex == null) R.string.app_settings_error_delay_number
            else R.string.app_settings_error_stage_numbers
        )
    }
    Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
