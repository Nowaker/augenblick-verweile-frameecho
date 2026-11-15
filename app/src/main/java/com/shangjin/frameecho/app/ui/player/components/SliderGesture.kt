package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Reserves upward-first slider gestures before the enclosing pane can start scrolling. */
@Composable
internal fun Modifier.reserveSliderGesture(onGestureActive: (Boolean) -> Unit): Modifier {
    val currentOnGestureActive by rememberUpdatedState(onGestureActive)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            currentOnGestureActive(true)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                currentOnGestureActive(false)
            }
        }
    }
}
