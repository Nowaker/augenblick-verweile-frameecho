package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.BuildConfig
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.components.TooltipWrapper

/** The app-wide actions every screen's top bar offers. */
data class TopBarActions(
    val onOpenSystemPicker: () -> Unit,
    val onOpenRecents: (() -> Unit)?,
    val onOpenAppSettings: (() -> Unit)?,
    val onNavigateToAbout: () -> Unit
)

/**
 * Top app bar shared by the home, player, gallery and recent-files screens, so opening
 * a file with the system picker and reaching recent files are always one tap away.
 *
 * Export settings appear only while a video is open; app settings and About live in the
 * overflow menu to keep the bar from crowding the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTopBar(
    scrollBehavior: TopAppBarScrollBehavior?,
    onOpenSystemPicker: () -> Unit,
    onOpenRecents: (() -> Unit)?,
    onOpenAppSettings: (() -> Unit)?,
    onNavigateToAbout: () -> Unit,
    title: String = stringResource(R.string.app_name),
    onNavigateBack: (() -> Unit)? = null,
    onOpenExportSettings: (() -> Unit)? = null,
    extraActions: @Composable RowScope.() -> Unit = {}
) {
    val appName = stringResource(R.string.app_name)
    val forkTitle = stringResource(R.string.fork_toolbar_title)
    val contextualTitle = title.takeIf { it != appName }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = forkTitle,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2
                )
            }
            Text(
                text = "#${BuildConfig.VERSION_CODE} - ${BuildConfig.BUILD_TIMESTAMP}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                maxLines = 2
            )
            TopAppBar(
                title = {
                    if (contextualTitle != null) {
                        Text(
                            contextualTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                expandedHeight = 56.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    if (onNavigateBack != null) {
                        TooltipWrapper(label = stringResource(R.string.go_back)) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                            }
                        }
                    }
                },
                actions = {
                    extraActions()
                    TooltipWrapper(label = stringResource(R.string.open_system_picker)) {
                        IconButton(onClick = onOpenSystemPicker) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.open_system_picker))
                        }
                    }
                    if (onOpenRecents != null) {
                        TooltipWrapper(label = stringResource(R.string.recent_files)) {
                            IconButton(onClick = onOpenRecents) {
                                Icon(Icons.Default.History, contentDescription = stringResource(R.string.recent_files))
                            }
                        }
                    }
                    if (onOpenExportSettings != null) {
                        TooltipWrapper(label = stringResource(R.string.export_settings)) {
                            IconButton(onClick = onOpenExportSettings) {
                                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.export_settings))
                            }
                        }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    TooltipWrapper(label = stringResource(R.string.more_options)) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onOpenAppSettings != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.app_settings)) },
                                onClick = { menuOpen = false; onOpenAppSettings() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.about)) },
                            onClick = { menuOpen = false; onNavigateToAbout() }
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    }
}
