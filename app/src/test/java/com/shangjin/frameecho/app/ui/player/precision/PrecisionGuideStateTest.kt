package com.shangjin.frameecho.app.ui.player.precision

import com.shangjin.frameecho.app.settings.PrecisionDialSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class PrecisionGuideStateTest {

    private val settings = PrecisionDialSettings(
        initialGuideOpacity = 0.7f,
        restingGuideOpacity = 0.3f,
        draggingGuideOpacity = 0.05f
    )

    @Test
    fun `the guide stays at its first showing while held until the dial turns`() {
        val held = PrecisionGuideState().pressed()

        assertEquals(0.7f, held.opacity(settings))
    }

    @Test
    fun `turning fades the guide and it stays faded until the return delay elapses`() {
        val released = PrecisionGuideState().pressed().turned().released()

        assertEquals(0.05f, released.opacity(settings))
        assertEquals(0.3f, released.returnElapsed(released.returnGeneration).opacity(settings))
    }

    @Test
    fun `grabbing again during the return delay keeps the guide hidden and voids the old timer`() {
        val firstRelease = PrecisionGuideState().pressed().turned().released()
        val regrabbed = firstRelease.pressed()

        val afterStaleTimer = regrabbed.returnElapsed(firstRelease.returnGeneration)

        assertEquals(GuidePhase.DRAGGING, afterStaleTimer.phase)
    }

    @Test
    fun `a timer from an earlier release does not end a later return delay`() {
        val firstRelease = PrecisionGuideState().pressed().turned().released()
        val secondRelease = firstRelease.pressed().released()

        val afterStaleTimer = secondRelease.returnElapsed(firstRelease.returnGeneration)

        assertEquals(GuidePhase.RETURNING, afterStaleTimer.phase)
        assertEquals(GuidePhase.RESTING, afterStaleTimer.returnElapsed(secondRelease.returnGeneration).phase)
    }

    @Test
    fun `a tap without turning leaves the first showing in place`() {
        val tapped = PrecisionGuideState().pressed().released()

        assertEquals(GuidePhase.INITIAL, tapped.phase)
    }
}
