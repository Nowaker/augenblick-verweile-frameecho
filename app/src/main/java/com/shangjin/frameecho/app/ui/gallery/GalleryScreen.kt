package com.shangjin.frameecho.app.ui.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.gallery.GalleryItem
import com.shangjin.frameecho.app.gallery.GalleryItemKind
import com.shangjin.frameecho.app.gallery.GalleryPermissions
import com.shangjin.frameecho.app.gallery.GalleryPhase
import com.shangjin.frameecho.app.gallery.GalleryViewModel
import com.shangjin.frameecho.app.ui.components.TooltipWrapper
import com.shangjin.frameecho.app.ui.media.MediaGrid
import com.shangjin.frameecho.app.ui.media.MediaTile
import com.shangjin.frameecho.app.ui.media.ThumbnailLoader
import com.shangjin.frameecho.app.ui.player.components.PlayerTopBar
import com.shangjin.frameecho.app.ui.player.components.TopBarActions

/**
 * The device's videos and motion photos. Media permission is asked for only here, with
 * an explanation first; denied, partial (Android 14 "selected photos") and videos-only
 * grants each get their own notice instead of an empty grid.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    thumbnails: ThumbnailLoader,
    actions: TopBarActions,
    onOpen: (GalleryItem) -> Unit,
    onNavigateBack: (() -> Unit)?
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Set after a request the system answered without showing a dialog: the user chose
    // "don't ask again", so only the app's system settings page can grant it now.
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    fun currentAccess() = GalleryPermissions.access(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val access = currentAccess()
        permanentlyDenied = !access.canList && activity != null &&
            GalleryPermissions.requested(Build.VERSION.SDK_INT).none { activity.shouldShowRequestPermissionRationale(it) }
        viewModel.onAccessChanged(access)
    }
    val requestAccess = { permissionLauncher.launch(GalleryPermissions.requested(Build.VERSION.SDK_INT).toTypedArray()) }
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // Grants can change in system settings or the photo picker while we're away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val access = currentAccess()
        if (access.canList) permanentlyDenied = false
        viewModel.onAccessChanged(access)
    }

    Scaffold(
        topBar = {
            PlayerTopBar(
                scrollBehavior = null,
                title = stringResource(R.string.gallery_title),
                onNavigateBack = onNavigateBack,
                onOpenSystemPicker = actions.onOpenSystemPicker,
                onOpenRecents = actions.onOpenRecents,
                onOpenAppSettings = actions.onOpenAppSettings,
                onNavigateToAbout = actions.onNavigateToAbout,
                extraActions = {
                    if (state.access.canList) {
                        TooltipWrapper(label = stringResource(R.string.gallery_refresh)) {
                            IconButton(onClick = viewModel::refresh) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.gallery_refresh))
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when (state.phase) {
            GalleryPhase.NEEDS_PERMISSION -> CenteredMessage(contentModifier) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.gallery_permission_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (permanentlyDenied) R.string.gallery_permission_denied else R.string.gallery_permission_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = if (permanentlyDenied) openAppSettings else requestAccess,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(if (permanentlyDenied) R.string.gallery_open_app_settings else R.string.gallery_permission_grant))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = actions.onOpenSystemPicker, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.open_system_picker))
                }
            }
            GalleryPhase.LOADING -> CenteredMessage(contentModifier) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.gallery_loading))
            }
            GalleryPhase.ERROR -> CenteredMessage(contentModifier) {
                Text(
                    stringResource(R.string.gallery_error, state.errorMessage ?: "?"),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::refresh, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.gallery_retry))
                }
            }
            GalleryPhase.LOADED -> {
                val tiles = state.items.map { item ->
                    MediaTile(
                        key = item.key,
                        uri = item.uri,
                        title = item.displayName,
                        isVideo = item.kind == GalleryItemKind.VIDEO,
                        isMotionPhoto = item.kind == GalleryItemKind.MOTION_PHOTO,
                        durationMs = item.durationMs
                    )
                }
                val byKey = state.items.associateBy { it.key }
                val notice: GalleryNotice? = when {
                    state.access.selectedOnly -> GalleryNotice(R.string.gallery_selected_only, R.string.gallery_select_more, requestAccess)
                    Build.VERSION.SDK_INT >= 33 && state.access.videos && !state.access.images ->
                        GalleryNotice(R.string.gallery_videos_only, R.string.gallery_allow_photos) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
                        }
                    else -> null
                }
                val scanningLabel = stringResource(R.string.gallery_scanning, state.stillsScanned, state.stillsToScan)
                MediaGrid(
                    tiles = tiles,
                    thumbnails = thumbnails,
                    onTileClick = { tile -> byKey[tile.key]?.let(onOpen) },
                    modifier = contentModifier
                ) {
                    if (notice != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "notice") {
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(stringResource(notice.message), style = MaterialTheme.typography.bodyMedium)
                                    FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = notice.onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                                            Text(stringResource(notice.action))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.scanningMotionPhotos) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "scanning") {
                            Column {
                                Text(scanningLabel, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { if (state.stillsToScan > 0) state.stillsScanned.toFloat() / state.stillsToScan else 0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    if (state.isEmpty) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                            Text(
                                stringResource(R.string.gallery_empty),
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
        }
    }
}

private class GalleryNotice(val message: Int, val action: Int, val onAction: () -> Unit)

/** Scrollable, centred column for full-screen notices, so large fonts never clip them. */
@Composable
internal fun CenteredMessage(modifier: Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(32.dp)
        ) {
            content()
        }
    }
}
