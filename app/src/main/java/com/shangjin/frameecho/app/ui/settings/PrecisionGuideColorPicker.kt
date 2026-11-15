package com.shangjin.frameecho.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R

/** One preset swatch; [argb] null is the theme's primary colour. */
private data class GuideSwatch(val argb: Int?, @StringRes val name: Int)

private val SWATCHES = listOf(
    GuideSwatch(null, R.string.precision_dial_color_theme),
    GuideSwatch(0xFFFFFFFF.toInt(), R.string.precision_dial_color_white),
    GuideSwatch(0xFFFFC107.toInt(), R.string.precision_dial_color_amber),
    GuideSwatch(0xFF00E5FF.toInt(), R.string.precision_dial_color_cyan),
    GuideSwatch(0xFFC6FF00.toInt(), R.string.precision_dial_color_lime),
    GuideSwatch(0xFFFF4081.toInt(), R.string.precision_dial_color_pink)
)

/**
 * Picks the dial guide's colour from a few swatches that read well over video, or as a
 * typed `#RRGGBB` value. Choosing a swatch fills the field, so both always agree.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrecisionGuideColorPicker(colorHex: String, onColorHexChange: (String) -> Unit, isError: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.precision_dial_guide_color), style = MaterialTheme.typography.titleMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SWATCHES.forEach { swatch ->
                val hex = swatch.argb?.let(PrecisionDialSettingsDraft::formatColor).orEmpty()
                SwatchButton(
                    swatch = swatch,
                    selected = colorHex.trim().equals(hex, ignoreCase = true),
                    onSelect = { onColorHexChange(hex) }
                )
            }
        }
        OutlinedTextField(
            value = colorHex,
            onValueChange = onColorHexChange,
            label = { Text(stringResource(R.string.precision_dial_color_hex)) },
            supportingText = { Text(stringResource(R.string.precision_dial_color_hex_hint)) },
            isError = isError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        )
    }
}

@Composable
private fun SwatchButton(swatch: GuideSwatch, selected: Boolean, onSelect: () -> Unit) {
    val name = stringResource(swatch.name)
    val fill = swatch.argb?.let(::Color) ?: MaterialTheme.colorScheme.primary
    val mark = if (swatch.argb == null) contentColorFor(fill) else if (fill.luminance() > 0.5f) Color.Black else Color.White
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = name }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .background(fill, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        ) {
            when {
                selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = mark, modifier = Modifier.size(20.dp))
                swatch.argb == null -> Icon(Icons.Outlined.Palette, contentDescription = null, tint = mark, modifier = Modifier.size(18.dp))
            }
        }
    }
}
