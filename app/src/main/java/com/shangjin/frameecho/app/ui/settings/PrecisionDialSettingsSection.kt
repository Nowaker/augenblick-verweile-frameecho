package com.shangjin.frameecho.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.settings.PrecisionDialSettings
import com.shangjin.frameecho.app.ui.settings.PrecisionDialSettingsDraft.Result

/**
 * Settings for the rotary precision dial: frames per turn, the guide's three opacities,
 * how long it stays hidden after a turn, and its colour.
 *
 * Fields remain drafts until the user saves a valid configuration. The caller owns
 * those drafts so leaving the Settings screen can protect unsaved edits.
 */
@Composable
fun PrecisionDialSettingsSection(
    settings: PrecisionDialSettings,
    draft: PrecisionDialSettingsDraft,
    onDraftChange: (PrecisionDialSettingsDraft) -> Unit,
    onSettingsChange: (PrecisionDialSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val result = draft.toSettings()
    val invalid = (result as? Result.Invalid)?.fields.orEmpty()
    val isSaved = draft == PrecisionDialSettingsDraft.from(settings)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(stringResource(R.string.precision_dial_settings_title))
        SettingsHint(stringResource(R.string.precision_dial_settings_hint))
        DialNumberField(
            value = draft.framesPerTurn,
            onValueChange = { onDraftChange(draft.copy(framesPerTurn = it)) },
            label = R.string.precision_dial_frames_per_turn,
            unit = R.string.precision_dial_unit_frames,
            supporting = stringResource(
                R.string.precision_dial_range_whole,
                PrecisionDialSettings.MIN_FRAMES_PER_TURN,
                PrecisionDialSettings.MAX_FRAMES_PER_TURN
            ),
            isError = PrecisionDialField.FRAMES_PER_TURN in invalid
        )
        DialNumberField(
            value = draft.initialOpacityPercent,
            onValueChange = { onDraftChange(draft.copy(initialOpacityPercent = it)) },
            label = R.string.precision_dial_initial_opacity,
            unit = R.string.precision_dial_unit_percent,
            supporting = percentRange(PrecisionDialSettings.MIN_INITIAL_OPACITY),
            isError = PrecisionDialField.INITIAL_OPACITY in invalid,
            decimal = true
        )
        DialNumberField(
            value = draft.restingOpacityPercent,
            onValueChange = { onDraftChange(draft.copy(restingOpacityPercent = it)) },
            label = R.string.precision_dial_resting_opacity,
            unit = R.string.precision_dial_unit_percent,
            supporting = percentRange(PrecisionDialSettings.MIN_RESTING_OPACITY) + " " +
                stringResource(R.string.precision_dial_resting_dimmer),
            isError = PrecisionDialField.RESTING_OPACITY in invalid,
            decimal = true
        )
        DialNumberField(
            value = draft.draggingOpacityPercent,
            onValueChange = { onDraftChange(draft.copy(draggingOpacityPercent = it)) },
            label = R.string.precision_dial_dragging_opacity,
            unit = R.string.precision_dial_unit_percent,
            supporting = percentRange(0f),
            isError = PrecisionDialField.DRAGGING_OPACITY in invalid,
            decimal = true
        )
        DialNumberField(
            value = draft.returnDelayMs,
            onValueChange = { onDraftChange(draft.copy(returnDelayMs = it)) },
            label = R.string.precision_dial_return_delay,
            unit = R.string.precision_dial_unit_ms,
            supporting = stringResource(R.string.precision_dial_range_whole, 0, PrecisionDialSettings.MAX_RETURN_DELAY_MS.toInt()),
            isError = PrecisionDialField.RETURN_DELAY in invalid
        )
        PrecisionGuideColorPicker(
            colorHex = draft.guideColorHex,
            onColorHexChange = { onDraftChange(draft.copy(guideColorHex = it)) },
            isError = PrecisionDialField.GUIDE_COLOR in invalid
        )
        if (invalid.isNotEmpty()) {
            Text(
                text = stringResource(R.string.precision_dial_unsaved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        TextButton(
            onClick = {
                onDraftChange(PrecisionDialSettingsDraft.from(PrecisionDialSettings.DEFAULT))
            },
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(stringResource(R.string.precision_dial_reset))
        }
        Text(
            text = stringResource(if (isSaved) R.string.precision_dial_saved else R.string.precision_dial_changes_unsaved),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                when (val current = draft.toSettings()) {
                    is Result.Valid -> onSettingsChange(current.settings)
                    is Result.Invalid -> Unit
                }
            },
            enabled = result is Result.Valid && !isSaved,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(stringResource(R.string.precision_dial_save)) }
    }
}

@Composable
private fun percentRange(minFraction: Float): String =
    stringResource(R.string.precision_dial_range_percent, Math.round(minFraction * 100f), 100)

@Composable
private fun DialNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    @StringRes unit: Int,
    supporting: String,
    isError: Boolean,
    decimal: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        suffix = { Text(stringResource(unit)) },
        supportingText = { Text(supporting) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    )
}

internal val PrecisionDialDraftSaver = listSaver<PrecisionDialSettingsDraft, String>(
    save = {
        listOf(it.framesPerTurn, it.initialOpacityPercent, it.restingOpacityPercent, it.draggingOpacityPercent, it.returnDelayMs, it.guideColorHex)
    },
    restore = { PrecisionDialSettingsDraft(it[0], it[1], it[2], it[3], it[4], it[5]) }
)
