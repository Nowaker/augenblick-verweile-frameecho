package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

/** Keeps both player subtrees in composition while changing only their measured bounds. */
@Composable
internal fun PlayerContentLayout(
    sideBySide: Boolean,
    video: @Composable () -> Unit,
    controls: @Composable (onSeekGestureActive: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val controlsScrollState = rememberScrollState()
    var seekGestureActive by remember { mutableStateOf(false) }
    Layout(
        modifier = modifier,
        content = {
            Box(modifier = if (sideBySide) Modifier.clipToBounds() else Modifier) { video() }
            Column(
                modifier = if (sideBySide) {
                    Modifier.verticalScroll(controlsScrollState, enabled = !seekGestureActive)
                } else Modifier
            ) { controls { seekGestureActive = it } }
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (sideBySide) {
            val videoWidth = width / 2
            val videoPane = measurables[0].measure(Constraints.fixed(videoWidth, height))
            val controlsPane = measurables[1].measure(Constraints.fixed(width - videoWidth, height))
            layout(width, height) {
                videoPane.placeRelative(0, 0)
                controlsPane.placeRelative(videoWidth, 0)
            }
        } else {
            val controlsPane = measurables[1].measure(
                Constraints(minWidth = width, maxWidth = width, maxHeight = height)
            )
            val videoPane = measurables[0].measure(Constraints.fixed(width, height - controlsPane.height))
            layout(width, height) {
                videoPane.placeRelative(0, 0)
                controlsPane.placeRelative(0, videoPane.height)
            }
        }
    }
}
