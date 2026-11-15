package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntSize

@Stable
internal class VideoTransform {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    private var lastTapUpTimeMs: Long? = null

    fun transformBy(zoom: Float, pan: Offset, viewport: IntSize) {
        scale = (scale * zoom).coerceIn(1f, 3f)
        val maxOffsetX = viewport.width * (scale - 1f) / 2f
        val maxOffsetY = viewport.height * (scale - 1f) / 2f
        offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
        offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
    }

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        lastTapUpTimeMs = null
    }

    fun tapToReset(downTimeMs: Long, upTimeMs: Long, timeoutMs: Long) {
        val previousUpTimeMs = lastTapUpTimeMs
        if (previousUpTimeMs != null && downTimeMs - previousUpTimeMs in 0..timeoutMs) {
            reset()
        } else {
            lastTapUpTimeMs = upTimeMs
        }
    }
}

/** Attach above the Android view, with consuming controls such as the dial as children. */
@Composable
internal fun Modifier.videoGestures(
    transform: VideoTransform,
    enabled: Boolean,
    onTap: () -> Unit
): Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnTap by rememberUpdatedState(onTap)
    return pointerInput(transform) {
        awaitEachGesture {
            val firstDown = awaitFirstDown()
            if (!currentEnabled) return@awaitEachGesture
            var pastTouchSlop = false
            var isTap = true
            var hasMultiTouch = false
            var upTimeMs = firstDown.uptimeMillis

            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) {
                    isTap = false
                    hasMultiTouch = true
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) {
                        transform.transformBy(zoom, pan, size)
                        event.changes.forEach { it.consume() }
                    }
                } else {
                    val change = event.changes.first()
                    if (!pastTouchSlop && (change.position - firstDown.position).getDistance() > viewConfiguration.touchSlop) {
                        pastTouchSlop = true
                        isTap = false
                    }
                    if (transform.scale > 1f) {
                        val pan = change.positionChange()
                        if (pan != Offset.Zero) {
                            transform.transformBy(1f, pan, size)
                            change.consume()
                        }
                    }
                }
                if (event.changes.none { it.pressed }) {
                    upTimeMs = event.changes.first().uptimeMillis
                    break
                }
            }

            if (isTap && !hasMultiTouch) {
                if (transform.scale > 1f) {
                    transform.tapToReset(firstDown.uptimeMillis, upTimeMs, viewConfiguration.doubleTapTimeoutMillis)
                } else currentOnTap()
            }
        }
    }
}
