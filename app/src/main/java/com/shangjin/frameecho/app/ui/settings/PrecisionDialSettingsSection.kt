package com.shangjin.frameecho.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * Each edit is saved through [onSettingsChange] as soon as every field holds a valid
 * value; until then the invalid fields are marked and nothing is stored, so the dial
 * never runs on half-typed numbers. Saved values replace the fields only when there is
 * no newer edit, so delayed persistence never overwrites text still being typed.
 */
@Composable
fun PrecisionDialSettingsSection(
    settings: PrecisionDialSettings,
    onSettingsChange: (PrecisionDialSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(PrecisionDialSettingsDraft.from(settings)) }
    var observedDraft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(PrecisionDialSettingsDraft.from(settings)) }
    LaunchedEffect(settings) {
        val current = PrecisionDialSettingsDraft.from(settings)
        draft = draft.reconcileSavedDraft(observedDraft, current)
        observedDraft = current
    }
    val result = draft.toSettings()
    val invalid = (result as? Result.Invalid)?.fields.orEmpty()

    fun edit(next: PrecisionDialSettingsDraft) {
        draft = next
        val parsed = (next.toSettings() as? Result.Valid)?.settings
        if (parsed != null && parsed != settings) onSettingsChange(parsed)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(stringResource(R.string.precision_dial_settings_title))
        SettingsHint(stringResource(R.string.precision_dial_settings_hint))
        DialNumberField(
            value = draft.framesPerTurn,
            onValueChange = { edit(draft.copy(framesPerTurn = it)) },
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
            onValueChange = { edit(draft.copy(initialOpacityPercent = it)) },
            label = R.string.precision_dial_initial_opacity,
            unit = R.string.precision_dial_unit_percent,
            supporting = percentRange(PrecisionDialSettings.MIN_INITIAL_OPACITY),
            isError = PrecisionDialField.INITIAL_OPACITY in invalid,
            decimal = true
        )
        DialNumberField(
            value = draft.restingOpacityPercent,
            onValueChange = { edit(draft.copy(restingOpacityPercent = it)) },
            label = R.string.precision_dial_resting_opacity,
            unit = R.string.precision_dial_unit_percent,
            supporting = percentRange(PrecisionDialSettings.MIN_RESTING_OPACITY) + " " +
                stringResource(R.string.precision_dial_resting_dimmer),
            isError = PrecisionDialField.RESTING_OPACITY in invalid,
            decimal = true
        )
        DialNumberField(
            value = draft.draggingOpacityPercent,
            onValueChange = { edit(draft.copy(draggingOpacityPercent = it)) },
            label = R.string.precision_dial_dragging_opacity,
            unit = R.string.precision_dial_unit_percent,
            supporting = percentRange(0f),
            isError = PrecisionDialField.DRAGGING_OPACITY in invalid,
            decimal = true
        )
        DialNumberField(
            value = draft.returnDelayMs,
            onValueChange = { edit(draft.copy(returnDelayMs = it)) },
            label = R.string.precision_dial_return_delay,
            unit = R.string.precision_dial_unit_ms,
            supporting = stringResource(R.string.precision_dial_range_whole, 0, PrecisionDialSettings.MAX_RETURN_DELAY_MS.toInt()),
            isError = PrecisionDialField.RETURN_DELAY in invalid
        )
        PrecisionGuideColorPicker(
            colorHex = draft.guideColorHex,
            onColorHexChange = { edit(draft.copy(guideColorHex = it)) },
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
                draft = PrecisionDialSettingsDraft.from(PrecisionDialSettings.DEFAULT)
                if (settings != PrecisionDialSettings.DEFAULT) onSettingsChange(PrecisionDialSettings.DEFAULT)
            },
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(stringResource(R.string.precision_dial_reset))
        }
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

private val DraftSaver = listSaver<PrecisionDialSettingsDraft, String>(
    save = {
        listOf(it.framesPerTurn, it.initialOpacityPercent, it.restingOpacityPercent, it.draggingOpacityPercent, it.returnDelayMs, it.guideColorHex)
    },
    restore = { PrecisionDialSettingsDraft(it[0], it[1], it[2], it[3], it[4], it[5]) }
)
