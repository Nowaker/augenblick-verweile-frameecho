package com.shangjin.frameecho.app.ui.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R

@Composable
internal fun DiscardSettingsDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.app_settings_discard_title)) },
        text = { Text(stringResource(R.string.app_settings_discard_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.app_settings_discard_changes), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.app_settings_keep_editing))
            }
        }
    )
}
