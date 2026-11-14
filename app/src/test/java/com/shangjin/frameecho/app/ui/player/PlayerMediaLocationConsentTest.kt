package com.shangjin.frameecho.app.ui.player

import android.net.Uri
import com.shangjin.frameecho.app.recents.RecentAccess
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.media.source.ResolvedMedia
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerMediaLocationConsentTest {

    private val resolved = mutableListOf<String>()
    private var consent = MediaLocationConsent.NOT_GRANTED
    private lateinit var vm: PlayerViewModel

    private fun uri(value: String, scheme: String = "content"): Uri = mockk {
        every { this@mockk.toString() } returns value
        every { this@mockk.scheme } returns scheme
    }

    /** A redacted read: the still carries a date and camera but no location. */
    private val stillWithoutLocation = VideoMetadata(dateTime = "2026-05-04T12:30:15+02:00", make = "Pixel")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = PlayerViewModel()
        vm.setDependenciesForTesting(mockk<FrameExtractor>(relaxed = true), mockk<FrameExporter>(relaxed = true))
        vm.setSourceDependenciesForTesting(
            resolve = { picked ->
                resolved += picked.toString()
                mockk<ResolvedMedia>(relaxed = true) {
                    every { videoUri } returns uri("file:///cache/v.mp4", "file")
                    every { sourceUri } returns picked
                    every { sourceFileName } returns "PXL.MP.jpg"
                    every { sourceMetadata } returns stillWithoutLocation
                    every { presentationTimestampUs } returns 1_000_000L
                    every { originalImageLength } returns 10L
                    every { originalImageMimeType } returns "image/jpeg"
                }
            },
            metadata = { VideoMetadata(frameRate = 30f, durationMs = 3_000) },
            recents = null,
            clock = { 1L }
        )
        vm.setMediaLocationConsentForTesting { consent }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `granted or pre-Android-10 opens at once without asking`() {
        consent = MediaLocationConsent.GRANTED
        vm.openMedia(uri("content://media/external/images/media/1"), RecentAccess.MEDIA_STORE)
        assertFalse(vm.uiState.value.awaitingMediaLocationConsent)

        consent = MediaLocationConsent.NOT_APPLICABLE
        vm.openMedia(uri("content://media/external/images/media/2"), RecentAccess.MEDIA_STORE)
        assertFalse(vm.uiState.value.awaitingMediaLocationConsent)
        assertEquals(listOf("content://media/external/images/media/1", "content://media/external/images/media/2"), resolved)
    }

    @Test
    fun `a file waits unread for the answer, then opens on refusal with honest metadata`() {
        val picked = uri("content://com.android.providers.media.documents/document/image%3A5")
        vm.openMedia(picked, RecentAccess.PERSISTED_GRANT)

        assertTrue(vm.uiState.value.awaitingMediaLocationConsent)
        assertTrue(vm.uiState.value.isOpening)
        assertTrue(resolved.isEmpty())

        vm.onMediaLocationConsentResult(granted = false)
        val state = vm.uiState.value
        assertFalse(state.awaitingMediaLocationConsent)
        assertTrue(state.showMediaLocationNotice)
        assertSame(picked, state.source?.sourceUri)
        assertEquals("PXL.MP.jpg", state.frameMetadata?.sourceFileName)
        // Location the read did not deliver stays absent: never guessed or filled in.
        assertNull(state.frameMetadata?.latitude)
        assertNull(state.frameMetadata?.longitude)
        assertEquals("Pixel", state.frameMetadata?.make)

        vm.clearMediaLocationNotice()
        assertFalse(vm.uiState.value.showMediaLocationNotice)
    }

    @Test
    fun `after a refusal later opens this session do not ask again`() {
        vm.openMedia(uri("content://media/external/images/media/1"), RecentAccess.MEDIA_STORE)
        vm.onMediaLocationConsentResult(granted = false)

        vm.openMedia(uri("content://media/external/video/media/2"), RecentAccess.MEDIA_STORE)
        assertFalse(vm.uiState.value.awaitingMediaLocationConsent)
        assertEquals(2, resolved.size)
    }

    @Test
    fun `a grant opens without the notice and is not asked for again`() {
        vm.openMedia(uri("content://media/external/images/media/1"), RecentAccess.MEDIA_STORE)
        consent = MediaLocationConsent.GRANTED
        vm.onMediaLocationConsentResult(granted = true)
        assertFalse(vm.uiState.value.showMediaLocationNotice)
        assertEquals(1, resolved.size)

        vm.openMedia(uri("content://media/external/images/media/3"), RecentAccess.MEDIA_STORE)
        assertFalse(vm.uiState.value.awaitingMediaLocationConsent)
        assertEquals(2, resolved.size)
    }

    @Test
    fun `no request without metadata preservation, for local files, or for an abandoned open`() {
        vm.updateExportConfig(ExportConfig(preserveMetadata = false))
        vm.openMedia(uri("content://media/external/images/media/1"), RecentAccess.MEDIA_STORE)
        assertFalse(vm.uiState.value.awaitingMediaLocationConsent)

        vm.updateExportConfig(ExportConfig(preserveMetadata = true))
        vm.openMedia(uri("file:///sdcard/DCIM/v.mp4", "file"), RecentAccess.TEMPORARY_GRANT)
        assertFalse(vm.uiState.value.awaitingMediaLocationConsent)
        assertEquals(2, resolved.size)

        vm.openMedia(uri("content://media/external/images/media/9"), RecentAccess.MEDIA_STORE)
        vm.clearVideoUri()
        vm.onMediaLocationConsentResult(granted = false)
        assertEquals(2, resolved.size)
        assertNull(vm.uiState.value.videoUri)
    }
}
