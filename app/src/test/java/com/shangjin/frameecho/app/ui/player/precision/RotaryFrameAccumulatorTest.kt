package com.shangjin.frameecho.app.ui.player.precision

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RotaryFrameAccumulatorTest {

    private fun degrees(value: Double) = value * PI / 180.0

    /** Pointer at [angle] radians on a 100 px radius around the centre. */
    private fun RotaryFrameAccumulator.beginAt(angle: Double) =
        begin((100 * cos(angle)).toFloat(), (100 * sin(angle)).toFloat())

    private fun RotaryFrameAccumulator.moveTo(angle: Double) =
        move((100 * cos(angle)).toFloat(), (100 * sin(angle)).toFloat())

    /** Turns from [from] by [stepDegrees] per movement, [count] times; returns every step. */
    private fun RotaryFrameAccumulator.turn(from: Double, stepDegrees: Double, count: Int): List<RotaryStep> =
        (1..count).map { moveTo(from + degrees(stepDegrees * it)) }

    @Test
    fun `grabbing away from the thumb does not move until the finger turns`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 60, deadZoneRadius = 20f)
        dial.beginAt(2.0)

        assertEquals(0, dial.moveTo(2.0 + degrees(1.0)).frames)
    }

    @Test
    fun `turning clockwise across the pi seam steps forward instead of a turn back`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 180, deadZoneRadius = 20f)
        dial.beginAt(degrees(179.0))

        assertEquals(1, dial.moveTo(degrees(-179.0)).frames)
    }

    @Test
    fun `turning counterclockwise across the pi seam steps back instead of a turn forward`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 180, deadZoneRadius = 20f)
        dial.beginAt(degrees(-179.0))

        assertEquals(-1, dial.moveTo(degrees(179.0)).frames)
    }

    @Test
    fun `three and a half clockwise turns move three and a half turns of frames`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 60, deadZoneRadius = 20f)
        dial.beginAt(0.0)

        val steps = dial.turn(from = 0.0, stepDegrees = 10.0, count = 126)

        assertEquals(210, steps.sumOf { it.frames })
        assertEquals(3, steps.sumOf { it.revolutionsCompleted })
    }

    @Test
    fun `movements smaller than a frame carry over until they add up`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 60, deadZoneRadius = 20f)
        dial.beginAt(1.0)

        // 0.6 degrees is a tenth of a frame at 60 frames per turn.
        val steps = dial.turn(from = 1.0, stepDegrees = -0.6, count = 100)

        assertEquals(-10, steps.sumOf { it.frames })
    }

    @Test
    fun `reversing a multi-turn gesture lands back on the grab frame`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 120, deadZoneRadius = 20f)
        dial.beginAt(0.5)
        val forward = dial.turn(from = 0.5, stepDegrees = 7.3, count = 113)
        val end = 0.5 + degrees(7.3 * 113)

        val back = dial.turn(from = end, stepDegrees = -7.3, count = 113)

        assertEquals(0, forward.sumOf { it.frames } + back.sumOf { it.frames })
    }

    @Test
    fun `a haptic turn is counted one full turn away from the grab, not at the grab itself`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 60, deadZoneRadius = 20f)
        dial.beginAt(0.0)
        val wobble = listOf(-5.0, 5.0, -5.0, 5.0, 0.0).map { dial.moveTo(degrees(it)) }

        // 26 steps of 14 degrees: the 26th, at -364 degrees, completes the turn.
        val counterclockwiseTurn = dial.turn(from = 0.0, stepDegrees = -14.0, count = 26)

        assertEquals(0, wobble.sumOf { it.revolutionsCompleted })
        assertEquals(List(25) { 0 } + 1, counterclockwiseTurn.map { it.revolutionsCompleted })
    }

    @Test
    fun `leaving the centre dead zone re-anchors instead of jumping to the new angle`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 60, deadZoneRadius = 20f)
        dial.beginAt(0.0)
        dial.move(1f, 1f)

        val reentry = dial.moveTo(degrees(90.0))

        assertEquals(0, reentry.frames)
        assertEquals(1, dial.moveTo(degrees(96.0)).frames)
    }

    @Test
    fun `changing frames per turn mid-gesture keeps what moved and applies the new rate onwards`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 60, deadZoneRadius = 20f)
        dial.beginAt(0.0)
        val halfTurn = dial.turn(from = 0.0, stepDegrees = 10.0, count = 18).sumOf { it.frames }
        dial.retune(120)

        val quarterTurn = dial.turn(from = PI, stepDegrees = 10.0, count = 9).sumOf { it.frames }

        assertEquals(30, halfTurn)
        assertEquals(30, quarterTurn)
    }

    @Test
    fun `wobbling around a completed turn does not emit another revolution tick`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 60, deadZoneRadius = 20f)
        dial.beginAt(0.0)
        val firstTurn = dial.turn(from = 0.0, stepDegrees = 10.0, count = 37)

        val wobble = listOf(350.0, 370.0, 350.0, 370.0).map { dial.moveTo(degrees(it)) }

        assertEquals(1, firstTurn.sumOf { it.revolutionsCompleted })
        assertEquals(0, wobble.sumOf { it.revolutionsCompleted })
    }

    @Test
    fun `a counterclockwise revolution ticks when the full turn is reached`() {
        val dial = RotaryFrameAccumulator(framesPerTurn = 60, deadZoneRadius = 20f)
        dial.begin(100f, 0f)

        val steps = listOf(0f to -100f, -100f to 0f, 0f to 100f, 100f to 0f)
            .map { (x, y) -> dial.move(x, y) }

        assertEquals(listOf(0, 0, 0, 1), steps.map { it.revolutionsCompleted })
    }

    @Test
    fun `four closed turns at screen-coordinate precision emit four revolution ticks`() {
        val centerX = 540f
        val centerY = 1_308f
        val radius = 194.4f
        fun point(angleDegrees: Double): Pair<Float, Float> {
            val angle = degrees(angleDegrees)
            return (centerX + radius * sin(angle).toFloat() - centerX) to
                (centerY - radius * cos(angle).toFloat() - centerY)
        }

        for (start in listOf(0.0, 17.0, 90.0, 180.0, 270.0)) {
            for (direction in listOf(-1, 1)) {
                val dial = RotaryFrameAccumulator(framesPerTurn = 30, deadZoneRadius = 20f)
                val (startX, startY) = point(start)
                dial.begin(startX, startY)

                val steps = (1..144).map { index ->
                    val (x, y) = point(start + direction * index * 10.0)
                    dial.move(x, y)
                }

                assertEquals("frames at $start in direction $direction", direction * 120, steps.sumOf { it.frames })
                assertEquals("ticks at $start in direction $direction", 4, steps.sumOf { it.revolutionsCompleted })
            }
        }
    }
}
