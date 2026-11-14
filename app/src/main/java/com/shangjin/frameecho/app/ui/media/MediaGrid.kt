package com.shangjin.frameecho.app.ui.media

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.core.common.FileUtils

/** One tile of [MediaGrid], shared by the in-app gallery and recent files. */
data class MediaTile(
    val key: String,
    val uri: Uri,
    val title: String,
    val isVideo: Boolean,
    val isMotionPhoto: Boolean,
    val durationMs: Long? = null,
    /** Extra line under the title, e.g. how many frames were saved from it. */
    val subtitle: String? = null,
    /** False when the file can no longer be read; the tile stays visible but dimmed. */
    val available: Boolean = true
)

private val TILE_MIN_SIZE = 112.dp

/**
 * Thumbnail grid. [header] adds full-width rows above the tiles (filters, notices) so a
 * single scroll container holds everything and nothing gets clipped in landscape.
 */
@Composable
fun MediaGrid(
    tiles: List<MediaTile>,
    thumbnails: ThumbnailLoader,
    onTileClick: (MediaTile) -> Unit,
    modifier: Modifier = Modifier,
    header: LazyGridScope.() -> Unit = {}
) {
    // Tiles grow up to twice their minimum width on wide screens.
    val thumbnailPx = with(LocalDensity.current) { TILE_MIN_SIZE.roundToPx() * 2 }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(TILE_MIN_SIZE),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize()
    ) {
        header()
        items(tiles, key = { it.key }) { tile ->
            MediaTileView(tile, thumbnails, thumbnailPx, onClick = { onTileClick(tile) })
        }
    }
}

@Composable
private fun MediaTileView(tile: MediaTile, thumbnails: ThumbnailLoader, thumbnailPx: Int, onClick: () -> Unit) {
    // produceState cancels the load when the tile leaves composition (scrolled away).
    val bitmap by produceState<Bitmap?>(thumbnails.cached(tile.uri), tile.uri, tile.available) {
        if (value == null && tile.available) value = thumbnails.load(tile.uri, tile.isVideo, thumbnailPx)
    }
    val kindLabel = stringResource(if (tile.isMotionPhoto) R.string.media_kind_motion_photo else R.string.media_kind_video)
    val unavailableLabel = stringResource(R.string.recent_unavailable)
    val description = remember(tile, kindLabel, unavailableLabel) {
        listOfNotNull(
            tile.title, kindLabel, tile.durationMs?.let { FileUtils.formatDuration(it) },
            tile.subtitle, unavailableLabel.takeIf { !tile.available }
        ).joinToString(", ")
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { this.contentDescription = description }
            .padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .alpha(if (tile.available) 1f else 0.4f)
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (!tile.available) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).size(32.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            ) {
                Icon(
                    if (tile.isMotionPhoto) Icons.Outlined.MotionPhotosOn else Icons.Outlined.Videocam,
                    contentDescription = null,
                    modifier = Modifier.padding(3.dp).size(16.dp)
                )
            }
            tile.durationMs?.takeIf { it > 0 }?.let { duration ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                ) {
                    Text(
                        FileUtils.formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Text(
            text = tile.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp)
        )
        val secondLine = if (!tile.available) unavailableLabel else tile.subtitle
        if (secondLine != null) {
            Text(
                text = secondLine,
                style = MaterialTheme.typography.labelSmall,
                color = if (tile.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
