package com.shangjin.frameecho.app.ui.player.framestep

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStepMathTest {

    @Test
    fun `a thousand steps at 29_97 fps land exactly on frame 1000`() {
        var position = 0L
        repeat(1000) { position = FrameStepMath.steppedPositionMs(position, 29.97f, 1, 0L) }

        assertEquals(1000L, FrameStepMath.frameIndexAt(position, 29.97f))
        assertEquals(33_367L, position)
    }

    @Test
    fun `frame positions use the nearest millisecond, the preview cache key`() {
        assertEquals(33L, FrameStepMath.frameStartMs(1L, 30f))
        assertEquals(67L, FrameStepMath.frameStartMs(2L, 30f))
        assertEquals(1L, FrameStepMath.frameIndexAt(33L, 30f))
        assertEquals(2L, FrameStepMath.frameIndexAt(67L, 30f))
    }

    @Test
    fun `stepping back and forth returns to the same frame`() {
        val start = FrameStepMath.frameStartMs(250L, 23.976f)
        var position = start
        repeat(37) { position = FrameStepMath.steppedPositionMs(position, 23.976f, -1, 0L) }
        repeat(37) { position = FrameStepMath.steppedPositionMs(position, 23.976f, 1, 0L) }

        assertEquals(start, position)
    }

    @Test
    fun `stepping clamps to the first and last frame`() {
        assertEquals(0L, FrameStepMath.steppedPositionMs(10L, 30f, -5, 1_000L))
        // 1000 ms at 30 fps holds frames 0..29; frame 29 starts at 966.67 ms.
        assertEquals(967L, FrameStepMath.steppedPositionMs(900L, 30f, 10, 1_000L))
    }

    @Test
    fun `a multi-frame step moves by exactly that many frames`() {
        val position = FrameStepMath.steppedPositionMs(FrameStepMath.frameStartMs(10L, 60f), 60f, 7, 0L)
        assertEquals(17L, FrameStepMath.frameIndexAt(position, 60f))
    }

    @Test
    fun `unknown frame rate leaves the position unchanged`() {
        assertEquals(1234L, FrameStepMath.steppedPositionMs(1234L, 0f, 1, 5_000L))
    }
}
