package com.shangjin.frameecho.app.ui.player.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale

@Composable
internal fun ScrubPreviewOverlay(preview: () -> Bitmap?, transform: VideoTransform) {
    val bitmap = preview() ?: return
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = imageBitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = transform.scale
                scaleY = transform.scale
                translationX = transform.offsetX
                translationY = transform.offsetY
            }
    )
}
