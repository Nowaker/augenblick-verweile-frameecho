package com.shangjin.frameecho.app.ui.recents

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.recents.RecentMedia
import com.shangjin.frameecho.app.recents.RecentMediaHistory
import com.shangjin.frameecho.app.recents.RecentMediaStore
import com.shangjin.frameecho.app.ui.media.MediaGrid
import com.shangjin.frameecho.app.ui.media.MediaTile
import com.shangjin.frameecho.app.ui.media.ThumbnailLoader
import com.shangjin.frameecho.app.ui.player.components.PlayerTopBar
import com.shangjin.frameecho.app.ui.player.components.TopBarActions
import kotlinx.coroutines.launch

/**
 * Files opened before, most recently opened or saved-from first, in the same grid as
 * the gallery. Entries that can't be read any more (a share grant that ended, a deleted
 * file) stay listed but dimmed, and explain themselves instead of failing to open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(
    store: RecentMediaStore,
    onlyWithSavedFrames: Boolean,
    onOnlyWithSavedFramesChange: (Boolean) -> Unit,
    thumbnails: ThumbnailLoader,
    actions: TopBarActions,
    onOpen: (RecentMedia) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by store.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val visible = remember(entries, onlyWithSavedFrames) { RecentMediaHistory.visible(entries, onlyWithSavedFrames) }
    // Checked off the main thread; unknown entries count as available until checked.
    val unavailable by produceState(emptySet<String>(), visible) {
        value = visible.filterNot { RecentMediaStore.isReadable(context, it) }.map { it.uri }.toSet()
    }
    var explaining by remember { mutableStateOf<RecentMedia?>(null) }

    Scaffold(
        topBar = {
            PlayerTopBar(
                scrollBehavior = null,
                title = stringResource(R.string.recent_files),
                onNavigateBack = onNavigateBack,
                onOpenSystemPicker = actions.onOpenSystemPicker,
                onOpenRecents = null,
                onOpenAppSettings = actions.onOpenAppSettings,
                onNavigateToAbout = actions.onNavigateToAbout
            )
        }
    ) { padding ->
        val byUri = visible.associateBy { it.uri }
        val tiles = visible.map { entry ->
            MediaTile(
                key = entry.uri,
                uri = entry.uri.toUri(),
                title = entry.displayName,
                isVideo = entry.mimeType?.startsWith("video/") != false,
                isMotionPhoto = entry.mimeType?.startsWith("image/") == true,
                subtitle = if (entry.hasSavedFrames) {
                    pluralStringResource(R.plurals.recent_saved_frames, entry.savedFrameCount, entry.savedFrameCount)
                } else null,
                available = entry.uri !in unavailable
            )
        }
        MediaGrid(
            tiles = tiles,
            thumbnails = thumbnails,
            onTileClick = { tile ->
                val entry = byUri[tile.key] ?: return@MediaGrid
                if (tile.available) onOpen(entry) else explaining = entry
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "filter") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = onlyWithSavedFrames,
                            role = Role.Checkbox,
                            onValueChange = onOnlyWithSavedFramesChange
                        )
                ) {
                    Checkbox(checked = onlyWithSavedFrames, onCheckedChange = null)
                    Text(
                        stringResource(R.string.recents_only_saved),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
            if (visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Text(
                        stringResource(if (onlyWithSavedFrames) R.string.recents_empty_saved else R.string.recents_empty_all),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp)
                    )
                }
            }
        }
    }

    explaining?.let { entry ->
        AlertDialog(
            onDismissRequest = { explaining = null },
            title = { Text(entry.displayName) },
            text = { Text(stringResource(R.string.recent_unavailable_message)) },
            confirmButton = {
                TextButton(onClick = {
                    explaining = null
                    scope.launch { store.remove(entry.uri) }
                }) { Text(stringResource(R.string.recent_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { explaining = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
