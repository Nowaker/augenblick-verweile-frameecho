package com.shangjin.frameecho.app.ui.player.precision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecisionDialControllerTest {

    @Test
    fun `losing input ownership settles a key adjustment even without key up`() {
        val controller = PrecisionDialController()
        controller.stepByKey(1, 60)

        assertTrue(controller.endAdjustment())
        assertFalse(controller.endAdjustment())
    }

    @Test
    fun `losing input ownership settles pointer and key steps exactly once`() {
        val controller = PrecisionDialController()
        controller.beginGesture(100f, 0f, 60, 20f)
        controller.moveGesture(0f, 100f, 60)
        controller.stepByKey(-1, 60)

        assertTrue(controller.endAdjustment())
        assertFalse(controller.endGesture())
        assertFalse(controller.endKeySteps())
        assertFalse(controller.endAdjustment())
    }
}
