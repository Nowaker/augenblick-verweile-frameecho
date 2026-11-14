package com.shangjin.frameecho.app.incoming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R

/**
 * Lists the files of a multi-file share. Picking one opens it and keeps the list for
 * when the player is closed; Close forgets the batch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingMediaChooser(
    queue: IncomingMediaQueue,
    onPick: (IncomingItem) -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.incoming_choose_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .semantics { heading() }
            )
            Text(
                text = pluralStringResource(R.plurals.incoming_shared_count, queue.items.size, queue.items.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(queue.items, key = { it.uri.toString() }) { item ->
                    val isVideo = item.mimeType?.startsWith("video/") == true
                    ListItem(
                        headlineContent = {
                            Text(item.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                when {
                                    !item.supported -> stringResource(R.string.incoming_unsupported, item.displayName)
                                    isVideo -> stringResource(R.string.media_kind_video)
                                    else -> stringResource(R.string.media_kind_motion_photo)
                                }
                            )
                        },
                        leadingContent = {
                            Icon(
                                when {
                                    !item.supported -> Icons.Outlined.Block
                                    isVideo -> Icons.Outlined.Videocam
                                    else -> Icons.Outlined.Image
                                },
                                contentDescription = null
                            )
                        },
                        trailingContent = if (item.opened) {
                            { Icon(Icons.Outlined.CheckCircle, contentDescription = null) }
                        } else null,
                        modifier = Modifier.clickable(
                            enabled = item.supported,
                            role = Role.Button,
                            onClick = { onPick(item) }
                        )
                    )
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.incoming_dismiss))
            }
        }
    }
}

/** Explains why nothing from a share was opened. */
@Composable
fun IncomingNoticeDialog(notice: IncomingOutcome.NothingSupported, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.incoming_dismiss)) }
        },
        text = {
            Text(
                notice.onlyItemName?.let { stringResource(R.string.incoming_unsupported, it) }
                    ?: stringResource(R.string.incoming_none_supported)
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}
