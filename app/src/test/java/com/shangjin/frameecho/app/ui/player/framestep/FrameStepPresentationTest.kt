package com.shangjin.frameecho.app.ui.player.framestep

import android.graphics.Bitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.media3.exoplayer.ExoPlayer
import com.shangjin.frameecho.app.ui.player.PlayerUiState
import com.shangjin.frameecho.app.ui.player.PlayerViewModel
import com.shangjin.frameecho.core.media.extraction.FramePreview
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStepPresentationTest {
    private val player = mockk<ExoPlayer>(relaxed = true)
    private val viewModel = mockk<PlayerViewModel>(relaxed = true)
    private val haptics = mockk<HapticFeedback>(relaxed = true)

    private fun presentation(previewAvailable: () -> Boolean): FrameStepPresentation {
        every { player.isPlaying } returns false
        every { viewModel.uiState } returns MutableStateFlow(PlayerUiState(videoFrameRate = 30f, durationMs = 8_000L))
        every { viewModel.isFramePreviewWorking } answers { previewAvailable() }
        return FrameStepPresentation(player, viewModel, haptics)
    }

    @Test
    fun `failed preview exposes native fallback during the same continuing hold`() {
        var available = true
        val presentation = presentation { available }
        presentation.steps.step(1)
        val preview = FramePreview(33L, mockk<Bitmap>(), 33_333L)
        presentation.updatePreview(preview)
        assertSame(preview, presentation.visiblePreview)

        available = false
        presentation.updatePreview(null)
        presentation.steps.step(1)

        assertTrue(presentation.holding)
        assertNull(presentation.visiblePreview)
        assertEquals(66L, presentation.overlayMs)
        verify { player.seekTo(66L) }
        presentation.steps.finish()
        assertFalse(presentation.holding)
        assertNull(presentation.visiblePreview)
        assertNull(presentation.steps.targetMs)
        verify { player.setScrubbingModeEnabled(false) }
    }

    @Test
    fun `working previews can lag the held target but settlement keeps only its matching frame`() {
        val presentation = presentation { true }
        presentation.steps.step(1)
        val first = FramePreview(33L, mockk<Bitmap>(), 33_333L)
        presentation.updatePreview(first)
        presentation.steps.step(1)

        presentation.updatePreview(first)

        assertSame(first, presentation.visiblePreview)
        presentation.steps.finish()
        val settled = FramePreview(66L, mockk<Bitmap>(), 66_667L)
        presentation.updatePreview(settled)
        presentation.updatePreview(first)
        assertSame(settled, presentation.visiblePreview)
    }
}
