package com.shangjin.frameecho.app.ui.player.precision

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/** What one pointer movement on the dial amounts to. */
data class RotaryStep(
    /** Signed frames to move now: positive is clockwise (next), negative counterclockwise. */
    val frames: Int,
    /** Full turns completed by this movement, in either direction, for the per-turn haptic. */
    val revolutionsCompleted: Int,
    /** Rotation of this movement in radians, clockwise positive; 0 while in the centre. */
    val radians: Double
) {
    companion object {
        val NONE = RotaryStep(0, 0, 0.0)
    }
}

/**
 * Turns pointer positions around the dial centre into signed frame steps, with no limit
 * on how many turns a gesture makes.
 *
 * Positions are relative to the centre in screen coordinates (y grows downwards), so a
 * growing angle is a clockwise turn. Each movement adds its shortest angular delta,
 * unwrapped across the -pi/pi seam, to a running rotation; the frames due are that
 * rotation rounded to the nearest frame, minus what was already emitted. Fractions of a
 * frame therefore carry over between movements and reversing undoes exactly what was
 * done. Grabbing never moves anything: only the change from the grab angle counts.
 *
 * Positions closer to the centre than [deadZoneRadius] are ignored, because the angle
 * there swings wildly for a tiny finger movement; leaving the dead zone re-anchors at the
 * new angle instead of jumping to it.
 *
 * A haptic revolution is counted whenever the rotation since the grab reaches another
 * whole turn away from it (one turn, two turns, ...), clockwise or counterclockwise.
 */
class RotaryFrameAccumulator(framesPerTurn: Int, private val deadZoneRadius: Float) {

    var framesPerTurn: Int = framesPerTurn
        private set

    private var lastAngle: Double? = null
    private var rotation = 0.0
    // Frames and rotation at the last change of framesPerTurn, so a change mid-gesture
    // keeps what was already emitted instead of re-scaling it.
    private var rebaseFrames = 0L
    private var rebaseRotation = 0.0
    private var emittedFrames = 0L

    /** Starts a gesture at [dx], [dy] from the centre. Emits nothing. */
    fun begin(dx: Float, dy: Float) {
        rotation = 0.0
        rebaseFrames = 0L
        rebaseRotation = 0.0
        emittedFrames = 0L
        lastAngle = angleOf(dx, dy)
    }

    fun move(dx: Float, dy: Float): RotaryStep {
        val angle = angleOf(dx, dy)
        val previous = lastAngle
        lastAngle = angle
        if (angle == null || previous == null) return RotaryStep.NONE

        var delta = angle - previous
        if (delta > PI) delta -= TWO_PI else if (delta < -PI) delta += TWO_PI

        val turnsBefore = rotation / TWO_PI
        rotation += delta
        val turnsAfter = rotation / TWO_PI

        val target = rebaseFrames + floor((rotation - rebaseRotation) / TWO_PI * framesPerTurn + 0.5).toLong()
        val frames = (target - emittedFrames).toInt()
        emittedFrames = target
        return RotaryStep(frames, wholeTurnsCrossed(turnsBefore, turnsAfter), delta)
    }

    /** Applies a new frames-per-turn from now on, without moving or restarting the gesture. */
    fun retune(newFramesPerTurn: Int) {
        if (newFramesPerTurn == framesPerTurn) return
        rebaseFrames = emittedFrames
        rebaseRotation = rotation
        framesPerTurn = newFramesPerTurn
    }

    private fun angleOf(dx: Float, dy: Float): Double? =
        if (hypot(dx, dy) < deadZoneRadius) null else atan2(dy.toDouble(), dx.toDouble())

    private companion object {
        const val TWO_PI = 2 * PI

        /** Nonzero whole numbers between [from] and [to]: the grab point itself is no turn. */
        fun wholeTurnsCrossed(from: Double, to: Double): Int {
            val lo = minOf(from, to)
            val hi = maxOf(from, to)
            val crossed = (floor(hi) - floor(lo)).toInt()
            return if (lo < 0.0 && hi >= 0.0) crossed - 1 else crossed
        }
    }
}
