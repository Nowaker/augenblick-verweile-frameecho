package com.shangjin.frameecho.app.ui.export

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.core.media.export.HeicSupport
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFormat

/** Existing callbacks remain independent so callers can own persistence and SAF permission handling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSettingsSheet(
    config: ExportConfig,
    onConfigChange: (ExportConfig) -> Unit,
    rememberQuickSettings: Boolean,
    onRememberQuickSettingsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    customExportTreeUri: Uri? = null,
    onPickCustomFolder: (() -> Unit)? = null,
    onClearCustomFolder: (() -> Unit)? = null,
    previewFrame: CapturedFrame? = null,
    originalStillAvailable: Boolean = false,
    originalStillMimeType: String? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val heicSupported = remember { HeicSupport.isSupported() }
    val originalCopyAvailable = originalStillAvailable && originalStillFormat(originalStillMimeType) != null
    LaunchedEffect(config.originalImage, config.motionPhoto, config.format, originalCopyAvailable, heicSupported) {
        val normalized = config.copy(
            originalImage = config.originalImage && originalCopyAvailable,
            motionPhoto = config.motionPhoto && !(config.originalImage && originalCopyAvailable),
            format = if (config.format == ExportFormat.HEIC && !heicSupported) ExportFormat.JPEG else config.format
        )
        if (normalized != config) onConfigChange(normalized)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                .imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.export_settings),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.export_options_done)) }
            }
            Spacer(modifier = Modifier.height(24.dp))
            ExportFormatSection(config, onConfigChange, heicSupported, originalCopyAvailable)
            SectionDivider()
            ExportFileNameSection(config, onConfigChange, previewFrame, originalStillMimeType)
            SectionDivider()
            ExportLocationSection(config, onConfigChange, customExportTreeUri, onPickCustomFolder, onClearCustomFolder)
            SectionDivider()
            ExportEncodingSection(config, onConfigChange)
            SectionDivider()
            ExportSettingSwitch(
                title = stringResource(R.string.remember_quick_settings),
                checked = rememberQuickSettings,
                onCheckedChange = onRememberQuickSettingsChange,
                hint = stringResource(R.string.remember_quick_settings_hint)
            )
        }
    }
}
