package com.shangjin.frameecho.app.ui.export

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportDirectory

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportLocationSection(
    config: ExportConfig,
    onConfigChange: (ExportConfig) -> Unit,
    customExportTreeUri: Uri?,
    onPickCustomFolder: (() -> Unit)?,
    onClearCustomFolder: (() -> Unit)?
) {
    SettingSectionHeader(
        icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
        title = stringResource(R.string.default_export_location)
    )
    if (customExportTreeUri != null) {
        Text(
            text = stringResource(R.string.custom_folder_active),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (onPickCustomFolder != null) {
            FilterChip(
                selected = customExportTreeUri != null,
                onClick = {
                    if (customExportTreeUri != null) onClearCustomFolder?.invoke() else onPickCustomFolder()
                },
                label = { Text(stringResource(R.string.location_custom)) },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        ExportDirectory.entries.forEach { directory ->
            val label = when (directory) {
                ExportDirectory.PICTURES_FRAMEECHO -> R.string.location_pictures
                ExportDirectory.DCIM_FRAMEECHO -> R.string.location_dcim
                ExportDirectory.MOVIES_FRAMEECHO -> R.string.location_movies
                ExportDirectory.PICTURES -> R.string.location_pictures_root
                ExportDirectory.DCIM -> R.string.location_dcim_root
                ExportDirectory.MOVIES -> R.string.location_movies_root
            }
            FilterChip(
                selected = directory == config.exportDirectory && customExportTreeUri == null,
                onClick = {
                    onClearCustomFolder?.invoke()
                    onConfigChange(config.copy(exportDirectory = directory))
                },
                label = { Text(stringResource(label)) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}
