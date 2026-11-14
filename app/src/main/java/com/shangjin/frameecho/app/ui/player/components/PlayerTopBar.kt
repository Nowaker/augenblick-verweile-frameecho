package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    TopAppBar(
        title = {
            Column {
                Text(
                    text = contextualTitle?.let { "$forkTitle - $it" } ?: forkTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
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
        expandedHeight = if (contextualTitle == null) 88.dp else 104.dp,
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
