package com.shangjin.frameecho.app.ui.export

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.shangjin.frameecho.R
import com.shangjin.frameecho.core.model.ExportConfig
import kotlin.math.roundToInt

@Composable
internal fun ExportEncodingSection(config: ExportConfig, onConfigChange: (ExportConfig) -> Unit) {
    val qualityLabel = stringResource(R.string.quality)
    SettingSectionHeader(
        icon = { Icon(Icons.Outlined.HighQuality, contentDescription = null) },
        title = "$qualityLabel: ${config.quality}%"
    )
    Slider(
        value = config.quality.toFloat(),
        onValueChange = { onConfigChange(config.copy(quality = it.roundToInt().coerceIn(1, 100))) },
        enabled = !config.originalImage,
        valueRange = 1f..100f,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = qualityLabel },
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
    ExportOptionHint(stringResource(
        if (config.originalImage) R.string.export_original_encoding_unchanged else R.string.quality_hint
    ))
    SectionDivider()
    ExportSettingSwitch(
        title = stringResource(R.string.export_motion_photo),
        checked = config.motionPhoto && !config.originalImage,
        enabled = !config.originalImage,
        onCheckedChange = { onConfigChange(config.copy(motionPhoto = it)) }
    )
    if (config.motionPhoto && !config.originalImage) {
        val durationLabel = stringResource(R.string.motion_duration)
        SettingSectionHeader(
            icon = { Icon(Icons.Outlined.MotionPhotosOn, contentDescription = null) },
            title = "$durationLabel: ${"%.1f".format(config.totalMotionDurationS)}s"
        )
        Slider(
            value = config.motionDurationBeforeS.coerceIn(0.5f, 5f),
            onValueChange = { onConfigChange(config.copy(motionDurationBeforeS = it, motionDurationAfterS = it)) },
            valueRange = 0.5f..5f,
            steps = 8,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = durationLabel },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
    ExportSettingSwitch(
        title = stringResource(R.string.export_mute_motion_audio),
        checked = config.muteAudio,
        enabled = config.motionPhoto && !config.originalImage,
        onCheckedChange = { onConfigChange(config.copy(muteAudio = it)) }
    )
    SectionDivider()
    ExportSettingSwitch(
        title = stringResource(R.string.preserve_metadata),
        checked = config.originalImage || config.preserveMetadata,
        enabled = !config.originalImage,
        onCheckedChange = { onConfigChange(config.copy(preserveMetadata = it)) }
    )
}
