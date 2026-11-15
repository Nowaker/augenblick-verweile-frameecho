@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shangjin.frameecho.app.ui.player.framestep

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.C
import androidx.media3.exoplayer.ScrubbingModeParameters

data class FrameStepSnapshot(
    val positionMs: Long,
    val frameRate: Float,
    val durationMs: Long,
    val previewAvailable: Boolean
)

interface FrameStepListener {
    fun onStepped(targetMs: Long, moved: Boolean, haptic: Boolean)
    fun onFinished(targetMs: Long, seekIssued: Boolean)
}

class FrameStepState(
    private val player: ExoPlayer,
    private val snapshot: () -> FrameStepSnapshot,
    private val listener: FrameStepListener
) {
    var targetMs by mutableStateOf<Long?>(null)
        private set
    var feedbackMs by mutableStateOf<Long?>(null)
        private set
    private var playerTargetMs: Long? = null
    private var scrubbing = false

    fun step(frames: Int, haptic: Boolean = true) {
        val state = snapshot()
        if (state.frameRate <= 0f || frames == 0) return
        val repeat = targetMs != null
        val baseMs = targetMs ?: if (player.isPlaying) player.currentPosition else state.positionMs
        if (!repeat) playerTargetMs = baseMs
        if (player.isPlaying) player.pause()
        val target = FrameStepMath.steppedPositionMs(baseMs, state.frameRate, frames, state.durationMs)
        targetMs = target
        feedbackMs = target
        val moved = target != baseMs
        if (moved && (!repeat || !state.previewAvailable)) {
            if (repeat && !scrubbing) {
                player.enterScrubbingMode()
                scrubbing = true
            }
            player.setSeekParameters(SeekParameters.EXACT)
            player.seekTo(target)
            playerTargetMs = target
        }
        listener.onStepped(target, moved, moved && haptic)
    }

    fun finish() {
        val target = targetMs ?: return
        val seek = scrubbing || playerTargetMs != target
        clearPress()
        if (seek) {
            player.setSeekParameters(SeekParameters.EXACT)
            player.seekTo(target)
        }
        listener.onFinished(target, seek)
    }

    fun cancel() {
        val target = targetMs ?: return
        clearPress()
        listener.onFinished(target, false)
    }

    fun clearFeedback() { feedbackMs = null }

    private fun clearPress() {
        targetMs = null
        playerTargetMs = null
        if (scrubbing) {
            scrubbing = false
            player.setScrubbingModeEnabled(false)
        }
    }
}

internal fun ExoPlayer.enterScrubbingMode() {
    setScrubbingModeParameters(
        ScrubbingModeParameters.Builder()
            .setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA))
            .setShouldIncreaseCodecOperatingRate(true)
            .setShouldEnableDynamicScheduling(true)
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(true)
            .setUseDecodeOnlyFlag(true)
            .build()
    )
    setScrubbingModeEnabled(true)
}
