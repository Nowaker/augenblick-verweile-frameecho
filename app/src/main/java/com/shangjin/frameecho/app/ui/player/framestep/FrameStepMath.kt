package com.shangjin.frameecho.app.ui.player.framestep

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Frame-index arithmetic for constant-frame-rate stepping.
 *
 * Stepping by a whole-millisecond frame duration drifts: at 29.97 fps a frame lasts
 * 33.367 ms, so subtracting a truncated 33 ms a thousand times lands 367 ms (11 frames)
 * away from where the user thinks they are. Stepping instead converts the position to a
 * frame index, moves the index, and converts back from the exact fractional duration.
 *
 * Positions are the frame timestamp rounded to the nearest millisecond, the same key the
 * adjacent-frame preview cache uses. Rounding is safe both ways: an exact player seek
 * renders the first frame at or after the position, and capture picks the frame closest
 * to it, and either way a frame is at most half a millisecond from its rounded position.
 */
object FrameStepMath {
    /** Absorbs floating-point error at the end of the clip. */
    private const val EPSILON = 1e-6

    fun frameIndexAt(positionMs: Long, frameRate: Float): Long {
        if (frameRate <= 0f) return 0L
        return (positionMs.coerceAtLeast(0L) * frameRate.toDouble() / 1000.0).roundToLong()
    }

    fun frameStartMs(index: Long, frameRate: Float): Long {
        if (frameRate <= 0f) return 0L
        return (index.coerceAtLeast(0L) * 1000.0 / frameRate.toDouble()).roundToLong()
    }

    /** Index of the last frame that starts before [durationMs]. */
    fun lastFrameIndex(durationMs: Long, frameRate: Float): Long {
        if (frameRate <= 0f || durationMs <= 0L) return 0L
        return (ceil(durationMs * frameRate.toDouble() / 1000.0 - EPSILON).toLong() - 1L).coerceAtLeast(0L)
    }

    /**
     * Position of the frame [frames] frames away from the one shown at [positionMs],
     * clamped to the first and last frame of the clip.
     */
    fun steppedPositionMs(positionMs: Long, frameRate: Float, frames: Int, durationMs: Long): Long {
        if (frameRate <= 0f) return positionMs
        val upperIndex = if (durationMs > 0L) lastFrameIndex(durationMs, frameRate) else Long.MAX_VALUE
        val target = (frameIndexAt(positionMs, frameRate) + frames).coerceIn(0L, upperIndex)
        val position = frameStartMs(target, frameRate)
        return if (durationMs > 0L) position.coerceAtMost(durationMs) else position
    }
}
