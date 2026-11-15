@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shangjin.frameecho.app.ui.player.framestep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import com.shangjin.frameecho.app.ui.player.PlayerUiState
import com.shangjin.frameecho.app.ui.player.PlayerViewModel
import com.shangjin.frameecho.core.media.extraction.FramePreview

internal class FrameStepPresentation(player: ExoPlayer, viewModel: PlayerViewModel, haptics: HapticFeedback) {
    var holding by mutableStateOf(false)
    var overlayMs by mutableStateOf<Long?>(null)
    var shown by mutableStateOf<FramePreview?>(null)
    val visiblePreview: FramePreview? get() = shown.takeIf { overlayMs != null }
    val steps = FrameStepState(player, {
        val state = viewModel.uiState.value
        FrameStepSnapshot(state.selectedFrameUs / 1_000L, state.videoFrameRate, state.durationMs, viewModel.isFramePreviewWorking)
    }, object : FrameStepListener {
        override fun onStepped(targetMs: Long, moved: Boolean, haptic: Boolean) {
            holding = true
            if (moved) {
                viewModel.updatePosition(targetMs)
                overlayMs = targetMs
                viewModel.requestFramePreview(targetMs)
            }
            if (haptic) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        }
        override fun onFinished(targetMs: Long, seekIssued: Boolean) {
            holding = false
            if (overlayMs != targetMs) overlayMs = null
        }
    })

    fun clear() {
        holding = false
        overlayMs = null
        shown = null
    }
}

@Composable
internal fun rememberFrameStepPresentation(player: ExoPlayer, viewModel: PlayerViewModel, state: PlayerUiState): FrameStepPresentation {
    val haptics = LocalHapticFeedback.current
    val presentation = remember(player, state.videoUri) { FrameStepPresentation(player, viewModel, haptics) }
    val previews = viewModel.framePreview.collectAsStateWithLifecycle()
    DisposableEffect(presentation) { onDispose { presentation.steps.cancel() } }
    LaunchedEffect(presentation) {
        snapshotFlow { previews.value to presentation.overlayMs }.collect { (preview, targetMs) ->
            when {
                targetMs == null -> presentation.shown = null
                preview != null && (presentation.holding || preview.positionMs == targetMs) -> presentation.shown = preview
            }
        }
    }
    LaunchedEffect(state.displayedFrameUs, presentation.holding, presentation.overlayMs, state.videoFrameRate) {
        val targetMs = presentation.overlayMs ?: return@LaunchedEffect
        val displayedUs = state.displayedFrameUs ?: return@LaunchedEffect
        if (!presentation.holding && state.videoFrameRate > 0f &&
            FrameStepMath.frameIndexAt((displayedUs + 500L) / 1_000L, state.videoFrameRate) == FrameStepMath.frameIndexAt(targetMs, state.videoFrameRate)) {
            presentation.overlayMs = null
        }
    }
    return presentation
}
