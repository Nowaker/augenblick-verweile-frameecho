package com.shangjin.frameecho.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFileName

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportFileNameSection(
    config: ExportConfig,
    onConfigChange: (ExportConfig) -> Unit,
    previewFrame: CapturedFrame?,
    originalStillMimeType: String?
) {
    var draft by rememberSaveable(config.customFileName) { mutableStateOf(config.customFileName.orEmpty()) }
    val issue = fileNameTemplateIssue(draft)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingSectionHeader(
            icon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
            title = stringResource(R.string.custom_file_name)
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf(
                null to stringResource(R.string.name_auto),
                SOURCE_POSITION_TEMPLATE to stringResource(R.string.export_name_source_position),
                SOURCE_FRAME_TEMPLATE to stringResource(R.string.export_name_source_frame),
                "FrameEcho" to "FrameEcho",
                "IMG" to "IMG",
                "Screenshot" to "Screenshot"
            )
            presets.forEach { (value, label) ->
                FilterChip(
                    selected = draft == value.orEmpty(),
                    onClick = {
                        draft = value.orEmpty()
                        onConfigChange(config.copy(customFileName = value))
                    },
                    label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { raw ->
                draft = raw
                if (fileNameTemplateIssue(raw) == null) onConfigChange(config.copy(customFileName = raw))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.export_name_template_label)) },
            placeholder = { Text(SOURCE_POSITION_TEMPLATE) },
            isError = issue != null,
            supportingText = {
                Column {
                    val message = when (issue) {
                        FileNameTemplateIssue.TOO_LONG -> R.string.export_name_too_long
                        FileNameTemplateIssue.INCOMPLETE_TOKEN -> R.string.export_name_incomplete_token
                        FileNameTemplateIssue.UNKNOWN_TOKEN -> R.string.export_name_unknown_token
                        null -> R.string.export_name_template_hint
                    }
                    Text(stringResource(message))
                    Text(
                        text = stringResource(R.string.char_count_format, draft.length, MAX_FILE_NAME_TEMPLATE_LENGTH),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
            }
        )
        when {
            previewFrame == null || config.fileNameTimestampMs == null ->
                ExportOptionHint(stringResource(R.string.export_name_open_media))
            issue != null -> ExportOptionHint(stringResource(R.string.export_name_draft_not_applied))
            else -> FileNamePreview(
                frame = previewFrame,
                config = config.copy(customFileName = draft).forFileNamePreview(originalStillMimeType)
            )
        }
    }
}

@Composable
private fun FileNamePreview(frame: CapturedFrame, config: ExportConfig) {
    val timestampMs = requireNotNull(config.fileNameTimestampMs)
    val values = ExportFileName.tokens(frame, timestampMs)
    Text(
        text = stringResource(R.string.export_name_preview),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = ExportFileName.generate(frame, config, timestampMs, isMotion = config.motionPhoto),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            values.forEach { (token, value) ->
                Text(
                    text = "$token = $value",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    ExportOptionHint(stringResource(R.string.export_name_token_units))
    if (!frame.metadata.frameRate.isFinite() || frame.metadata.frameRate <= 0f) {
        ExportOptionHint(stringResource(R.string.export_name_frame_rate_unknown))
    }
}
