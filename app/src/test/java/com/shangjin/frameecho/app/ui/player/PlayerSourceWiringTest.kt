package com.shangjin.frameecho.app.ui.player

import android.graphics.Bitmap
import android.net.Uri
import com.shangjin.frameecho.app.recents.RecentAccess
import com.shangjin.frameecho.app.recents.RecentMediaRecorder
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.media.source.MediaSourceException
import com.shangjin.frameecho.core.media.source.ResolvedMedia
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFileName
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSourceWiringTest {

    private class Recorder : RecentMediaRecorder {
        val opened = mutableListOf<String>()
        val saved = mutableListOf<String>()
        override suspend fun recordOpened(uri: Uri, displayName: String, mimeType: String?, access: RecentAccess) {
            opened += displayName
        }
        override suspend fun recordSaved(uri: Uri, displayName: String, mimeType: String?, access: RecentAccess) {
            saved += displayName
        }
    }

    private val extractor = mockk<FrameExtractor>(relaxed = true)
    private val exporter = mockk<FrameExporter>()
    private val recorder = Recorder()
    private var now = 1_000L
    private val decoded = VideoMetadata(
        dateTime = "2026-01-01T00:00:00Z", frameRate = 30f, durationMs = 3_000,
        videoWidth = 1920, videoHeight = 1080, sourceFileName = "motion_photo_123.mp4"
    )
    private val still = VideoMetadata(dateTime = "2026-05-04T12:30:15.250+02:00", make = "Pixel")
    private lateinit var vm: PlayerViewModel

    private fun uri(value: String): Uri = mockk { every { this@mockk.toString() } returns value }

    private fun resolved(name: String): ResolvedMedia = mockk(relaxed = true) {
        every { videoUri } returns uri("file:///cache/$name.mp4")
        every { sourceUri } returns uri("content://media/$name")
        every { sourceFileName } returns name
        every { sourceMetadata } returns still
        every { presentationTimestampUs } returns 1_000_000L
        every { originalImageLength } returns 4_000L
        every { originalImageMimeType } returns "image/jpeg"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = PlayerViewModel()
        vm.setDependenciesForTesting(extractor, exporter)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun useResolver(resolve: suspend (Uri) -> ResolvedMedia) =
        vm.setSourceDependenciesForTesting(resolve, { decoded }, recorder) { now++ }

    @Test
    fun `an opened motion photo plays its video but keeps the original name and still metadata`() {
        val media = resolved("PXL_1.MP.jpg")
        useResolver { media }

        vm.openMedia(uri("content://media/PXL_1.MP.jpg"), RecentAccess.PERSISTED_GRANT)
        val state = vm.uiState.value

        assertEquals("file:///cache/PXL_1.MP.jpg.mp4", state.videoUri.toString())
        assertEquals("PXL_1.MP.jpg", state.frameMetadata?.sourceFileName)
        assertEquals(still.dateTime, state.frameMetadata?.dateTime)
        assertEquals(1_000_000L, state.frameMetadata?.dateTimePositionUs)
        assertFalse(state.isOpening)
    }

    @Test
    fun `recents record a successful open once, only after playback is ready`() {
        useResolver { resolved("a.jpg") }
        vm.openMedia(uri("content://media/a.jpg"), RecentAccess.MEDIA_STORE)
        assertEquals(emptyList<String>(), recorder.opened)

        vm.onPlaybackReady()
        vm.onPlaybackReady()
        assertEquals(listOf("a.jpg"), recorder.opened)
    }

    @Test
    fun `open failures are explained and leave nothing open or recorded`() {
        useResolver { throw MediaSourceException(MediaSourceException.Reason.NO_EMBEDDED_VIDEO, "still") }
        vm.openMedia(uri("content://media/still.jpg"), RecentAccess.TEMPORARY_GRANT)
        assertEquals(PlayerError.OpenFailed(OpenFailure.NO_EMBEDDED_VIDEO, "still"), vm.uiState.value.error)
        assertNull(vm.uiState.value.videoUri)

        useResolver { throw IOException("grant revoked") }
        vm.openMedia(uri("content://other/gone.mp4"), RecentAccess.TEMPORARY_GRANT)
        assertEquals(OpenFailure.UNREADABLE, (vm.uiState.value.error as PlayerError.OpenFailed).reason)
        vm.onPlaybackReady()
        assertEquals(emptyList<String>(), recorder.opened)
    }

    @Test
    fun `switching or closing media releases the extracted video`() {
        val first = resolved("first.jpg")
        val second = resolved("second.jpg")
        var next = first
        useResolver { next }

        vm.openMedia(uri("content://media/first.jpg"), RecentAccess.MEDIA_STORE)
        next = second
        vm.openMedia(uri("content://media/second.jpg"), RecentAccess.MEDIA_STORE)
        verify(exactly = 1) { first.release() }
        verify(exactly = 0) { second.release() }

        vm.clearVideoUri()
        verify(exactly = 1) { second.release() }
    }

    @Test
    fun `a playback error returns home with the reason`() {
        val media = resolved("a.jpg")
        useResolver { media }
        vm.openMedia(uri("content://media/a.jpg"), RecentAccess.MEDIA_STORE)

        vm.onPlaybackError("ERROR_CODE_DECODER_INIT_FAILED")
        assertNull(vm.uiState.value.videoUri)
        assertEquals(PlayerError.PlaybackFailed("ERROR_CODE_DECODER_INIT_FAILED"), vm.uiState.value.error)
        verify { media.release() }
    }

    @Test
    fun `a late render of an older seek does not become the capture frame`() {
        useResolver { resolved("a.jpg") }
        vm.openMedia(uri("content://media/a.jpg"), RecentAccess.MEDIA_STORE)
        vm.updateVideoFrameRate(30f)
        vm.updatePosition(300L)

        // Frame 10 (333.37 ms) from the tap that started a backward hold to frame 9.
        vm.onDisplayedFrame(333_367L)
        assertNull(vm.uiState.value.displayedFrameUs)
        assertEquals(300_000L, vm.uiState.value.selectedFrameUs)

        vm.onDisplayedFrame(300_000L)
        assertEquals(300_000L, vm.uiState.value.displayedFrameUs)
    }

    @Test
    fun `the previewed file name is the one the save writes, and saves are recorded`() {
        useResolver { resolved("PXL_2.MP.jpg") }
        vm.openMedia(uri("content://media/PXL_2.MP.jpg"), RecentAccess.PERSISTED_GRANT)
        vm.updateDuration(3_000L)
        vm.updateVideoFrameRate(30f)
        vm.updateExportConfig(ExportConfig(customFileName = "{filename}_{timestamp}_{frame}"))
        vm.onPausedAtFrame(1_500_000L)

        val previewState = vm.uiState.value
        val previewName = ExportFileName.generate(previewState.previewFrame!!, previewState.effectiveExportConfig, exportTimestampMs = 1L)
        vm.onPausedAtFrame(1_500_000L)

        val frameSlot = slot<CapturedFrame>()
        val configSlot = slot<ExportConfig>()
        coEvery { extractor.extractFrameWithInfo(any(), 1_500_000L) } returns
            (mockk<Bitmap>(relaxed = true) to CapturedFrame(1_500_000L, 1920, 1080, ColorSpaceInfo(), decoded))
        coEvery { exporter.exportStaticFrame(any(), capture(frameSlot), capture(configSlot), any()) } returns
            ExportResult.Success("content://out/1", 1920, 1080, 10, ExportFormat.JPEG)

        vm.captureAndSave(motionPhoto = false)

        // A different fallback clock must not matter: the frozen selection clock wins.
        val savedName = ExportFileName.generate(frameSlot.captured, configSlot.captured, exportTimestampMs = 999L)
        assertEquals(previewName, savedName)
        assertEquals("PXL_2.MP", previewName.substringBefore('_') + "_" + previewName.split('_')[1])
        assertEquals(listOf("PXL_2.MP.jpg"), recorder.saved)

        coEvery { exporter.exportStaticFrame(any(), any(), any(), any()) } returns ExportResult.Error("disk full")
        vm.captureAndSave(motionPhoto = false)
        assertEquals(listOf("PXL_2.MP.jpg"), recorder.saved)
    }
}
