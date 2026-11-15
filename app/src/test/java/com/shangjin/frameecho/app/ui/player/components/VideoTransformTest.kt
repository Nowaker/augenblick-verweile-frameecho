package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTransformTest {
    @Test
    fun `double tap reset follows pointer timestamps even when taps are processed together`() {
        for ((gapMs, expectedScale) in mapOf(100L to 1f, 500L to 3f)) {
            val transform = VideoTransform()
            transform.transformBy(3f, Offset.Zero, IntSize(1_000, 1_000))
            transform.tapToReset(1_000L, 1_010L, 300L)

            transform.tapToReset(1_010L + gapMs, 1_020L + gapMs, 300L)

            assertEquals("Input tap gap $gapMs ms", expectedScale, transform.scale, 0f)
        }
    }
}
