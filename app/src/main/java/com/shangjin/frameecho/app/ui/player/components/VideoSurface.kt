@file:androidx.annotation.OptIn(UnstableApi::class)

package com.shangjin.frameecho.app.ui.player.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.media3.common.util.UnstableApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.shangjin.frameecho.R
import kotlinx.coroutines.delay
private val whiteColor60 = Color.White.copy(alpha = 0.6f)


/**
 * Video surface with gesture handling (tap-to-play/pause, pinch-to-zoom, pan).
 *
 * Also renders overlays for:
 * - Export progress
 * - Video loading (sustained buffering)
 * - Tap play/pause feedback
 * - Capture flash effect
 * - Zoom level indicator + reset button
 */
@Composable
fun VideoSurface(
    exoPlayer: ExoPlayer,
    isExporting: Boolean,
    showBufferingIndicator: Boolean,
    isSliderDragging: Boolean,
    showCaptureFlash: Boolean,
    onTogglePlayPause: () -> Unit,
    onCancelExport: () -> Unit,
    /**
     * Preview frame to show instead of the live surface while scrubbing, or null.
     * A lambda so the (frequently changing) bitmap is only read inside the leaf
     * composable that draws it, rather than recomposing this whole surface.
     */
    scrubPreview: () -> Bitmap? = { null },
    overlay: @Composable BoxScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    val transform = remember { VideoTransform() }

    // Tap-to-play/pause visual feedback (null = hidden)
    var tapFeedbackIsPlaying by remember { mutableStateOf<Boolean?>(null) }

    // Derive colors with copy(alpha) directly. Do NOT use remember{} for Color.copy()
    // because Color is a value class (wrapping ULong). copy() is just lightweight bitwise math.
    // Using remember forces boxing of the primitive to store it in the Compose slot table,
    // which introduces memory allocation and overhead.
    val colorScheme = MaterialTheme.colorScheme
    val scrimColor32 = colorScheme.scrim.copy(alpha = 0.32f)
    val scrimColor18 = colorScheme.scrim.copy(alpha = 0.18f)
    val inverseSurfaceColor70 = colorScheme.inverseSurface.copy(alpha = 0.7f)
    val inverseSurfaceColor60 = colorScheme.inverseSurface.copy(alpha = 0.6f)

    LaunchedEffect(tapFeedbackIsPlaying) {
        if (tapFeedbackIsPlaying != null) {
            delay(600L)
            tapFeedbackIsPlaying = null
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    controllerAutoShow = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setOnClickListener(null)
                }
            },
            modifier = Modifier.fillMaxSize()
                // ⚡ Bolt: Use lambda-based graphicsLayer to defer state reading to the draw phase.
                // This prevents expensive full recompositions of the VideoSurface during
                // high-frequency gesture events like panning and zooming.
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.offsetX
                    translationY = transform.offsetY
                }
        )

        // Scrub preview: while the finger is moving, the frame under it is decoded by a
        // separate extractor and drawn here, so the playback decoder is left alone. The
        // real surface takes over once the drag settles and the exact seek has rendered.
        ScrubPreviewOverlay(
            preview = scrubPreview,
            transform = transform
        )

        // Keep AndroidView's consumed DOWN events out of the gesture chain while
        // retaining the dial's child-first ownership of gestures inside its circle.
        Box(Modifier.fillMaxSize().videoGestures(transform, !isExporting) {
            tapFeedbackIsPlaying = !exoPlayer.isPlaying
            onTogglePlayPause()
        }) { overlay() }

        // Loading/exporting overlay
        if (isExporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor32)
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center
            ) {
                ElevatedCard(shape = RoundedCornerShape(28.dp)) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val exportLoadingLabel = stringResource(R.string.exporting)
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { contentDescription = exportLoadingLabel }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.exporting),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onCancelExport) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }

        // Video loading indicator (only for sustained buffering, not brief seeks)
        if (showBufferingIndicator && !isExporting && !isSliderDragging) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor18)
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val bufferingLabel = stringResource(R.string.video_loading)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = bufferingLabel },
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = inverseSurfaceColor70
                    ) {
                        Text(
                            text = stringResource(R.string.video_loading),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Tap-to-play/pause visual feedback overlay
        AnimatedVisibility(
            visible = tapFeedbackIsPlaying != null,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut() + scaleOut(targetScale = 0.7f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = inverseSurfaceColor60,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (tapFeedbackIsPlaying == true)
                            Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        // Capture flash overlay (camera shutter effect)
        AnimatedVisibility(
            visible = showCaptureFlash,
            enter = fadeIn(animationSpec = tween(50)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(whiteColor60)
            )
        }

        // Zoom level indicator + reset button (visible when zoomed in)
        if (transform.scale > 1.05f) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = inverseSurfaceColor70
                ) {
                    Text(
                        text = "\u00d7${"%.1f".format(transform.scale)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = inverseSurfaceColor70,
                    onClick = transform::reset
                ) {
                    Text(
                        text = stringResource(R.string.zoom_reset),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
