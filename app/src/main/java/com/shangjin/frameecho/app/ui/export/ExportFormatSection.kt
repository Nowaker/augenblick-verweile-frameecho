package com.shangjin.frameecho.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFormat

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportFormatSection(
    config: ExportConfig,
    onConfigChange: (ExportConfig) -> Unit,
    heicSupported: Boolean,
    originalCopyAvailable: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingSectionHeader(
            icon = { Icon(Icons.Outlined.Image, contentDescription = null) },
            title = stringResource(R.string.format)
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExportFormat.entries.forEach { format ->
                FilterChip(
                    selected = !config.originalImage && config.format == format,
                    enabled = format == ExportFormat.JPEG || heicSupported,
                    onClick = { onConfigChange(config.copy(format = format, originalImage = false)) },
                    label = { Text(format.name) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
            FilterChip(
                selected = config.originalImage,
                enabled = originalCopyAvailable,
                onClick = { onConfigChange(config.copy(originalImage = true, motionPhoto = false)) },
                label = { Text(stringResource(R.string.export_copy_original_still)) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
        if (!heicSupported) ExportOptionHint(stringResource(R.string.export_heic_unavailable))
        ExportOptionHint(stringResource(
            if (originalCopyAvailable) R.string.export_original_still_explanation
            else R.string.export_original_still_unavailable
        ))
        if (!config.originalImage) {
            ExportOptionHint(stringResource(R.string.export_encoded_image_explanation))
            if (config.motionPhoto) {
                ExportOptionHint(stringResource(
                    if (config.format == ExportFormat.HEIC) R.string.export_motion_heic_explanation
                    else R.string.export_motion_jpeg_explanation
                ))
            }
        }
    }
}
