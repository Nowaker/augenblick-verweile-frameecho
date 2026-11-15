package com.shangjin.frameecho.app.ui.player.precision

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI

/**
 * State of one precision dial that has to outlive recomposition: the guide cycle, where
 * the thumb is drawn, and the gesture in progress. Gesture state lives here rather than
 * in the pointer coroutine so a change of `enabled` or of the settings can end or retune
 * a turn without restarting it.
 */
@Stable
internal class PrecisionDialController {

    var guide by mutableStateOf(PrecisionGuideState())
        private set

    /** Thumb position on the ring in radians, screen coordinates (0 = right, pi/2 = bottom). */
    var thumbAngle by mutableFloatStateOf(THUMB_START_ANGLE)
        private set

    private var accumulator: RotaryFrameAccumulator? = null
    private var gestureMovedFrames = false
    private var keyMovedFrames = false

    val isGestureActive: Boolean get() = accumulator != null

    fun beginGesture(dx: Float, dy: Float, framesPerTurn: Int, deadZoneRadius: Float) {
        accumulator = RotaryFrameAccumulator(framesPerTurn, deadZoneRadius).also { it.begin(dx, dy) }
        gestureMovedFrames = false
        guide = guide.pressed()
    }

    /** Null when no gesture is active, e.g. after it was ended by a disable. */
    fun moveGesture(dx: Float, dy: Float, framesPerTurn: Int): RotaryStep? {
        val active = accumulator ?: return null
        active.retune(framesPerTurn)
        val step = active.move(dx, dy)
        if (step.radians != 0.0) {
            rotateThumb(step.radians)
            guide = guide.turned()
        }
        if (step.frames != 0) gestureMovedFrames = true
        return step
    }

    /** Ends the gesture, if any; true when it moved frames and the caller must settle. */
    fun endGesture(): Boolean {
        if (accumulator == null) return false
        accumulator = null
        guide = guide.released()
        return gestureMovedFrames.also { gestureMovedFrames = false }
    }

    /** A single frame from a key or accessibility action; the thumb moves by one frame. */
    fun stepByKey(direction: Int, framesPerTurn: Int) {
        rotateThumb(direction * 2 * PI / framesPerTurn)
        keyMovedFrames = true
    }

    /** True when keys moved frames since the last call, so the caller must settle. */
    fun endKeySteps(): Boolean = keyMovedFrames.also { keyMovedFrames = false }

    fun endAdjustment(): Boolean {
        val pointerMoved = endGesture()
        val keyMoved = endKeySteps()
        return pointerMoved || keyMoved
    }

    fun returnElapsed(generation: Long) {
        guide = guide.returnElapsed(generation)
    }

    private fun rotateThumb(radians: Double) {
        thumbAngle = ((thumbAngle + radians) % (2 * PI)).toFloat()
    }

    private companion object {
        const val THUMB_START_ANGLE = (-PI / 2).toFloat()
    }
}
