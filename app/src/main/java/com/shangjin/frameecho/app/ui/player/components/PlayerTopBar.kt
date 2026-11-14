package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.BuildConfig
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.components.TooltipWrapper

/**
 * Top app bar for the player screen.
 *
 * Shows the app name with contextual action buttons:
 * - "Select Video" is always visible.
 * - "Export Settings" is visible only when a video is loaded.
 * - "About" is visible only on the empty (home) state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTopBar(
    hasVideo: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    onSelectVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.fork_toolbar_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = BuildConfig.BUILD_TIMESTAMP,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        expandedHeight = 88.dp,
        actions = {
            TooltipWrapper(label = stringResource(R.string.select_video)) {
                IconButton(onClick = onSelectVideo) {
                    Icon(
                        Icons.Default.VideoFile,
                        contentDescription = stringResource(R.string.select_video)
                    )
                }
            }
            if (hasVideo) {
                TooltipWrapper(label = stringResource(R.string.export_settings)) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.export_settings)
                        )
                    }
                }
            }
            if (!hasVideo) {
                TooltipWrapper(label = stringResource(R.string.about)) {
                    IconButton(onClick = onNavigateToAbout) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.about)
                        )
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        )
    )
}
