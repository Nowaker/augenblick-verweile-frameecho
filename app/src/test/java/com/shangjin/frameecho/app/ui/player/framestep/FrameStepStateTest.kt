package com.shangjin.frameecho.app.ui.player.framestep

import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStepStateTest {
    @Test
    fun `previous at ended playback moves from the displayed final frame`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.isPlaying } returns false
        every { player.currentPosition } returns 8_244L
        val selected = mutableListOf<Long>()
        val state = FrameStepState(player, { FrameStepSnapshot(7_966L, 30f, 8_000L, true) }, object : FrameStepListener {
            override fun onStepped(targetMs: Long, moved: Boolean, haptic: Boolean) { selected += targetMs }
            override fun onFinished(targetMs: Long, seekIssued: Boolean) = Unit
        })

        state.step(-1)

        assertEquals(listOf(7_933L), selected)
        assertEquals(238L, FrameStepMath.frameIndexAt(requireNotNull(state.targetMs), 30f))
    }

    @Test
    fun `ending the same rotary adjustment twice settles only once`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.isPlaying } returns false
        val finished = mutableListOf<Long>()
        val state = FrameStepState(player, { FrameStepSnapshot(0L, 30f, 8_000L, true) }, object : FrameStepListener {
            override fun onStepped(targetMs: Long, moved: Boolean, haptic: Boolean) = Unit
            override fun onFinished(targetMs: Long, seekIssued: Boolean) { finished += targetMs }
        })
        state.step(60, haptic = false)

        state.finish()
        state.finish()

        assertEquals(listOf(2_000L), finished)
    }
}
