package com.shangjin.frameecho.app.gallery

import android.Manifest
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val readSelected = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    @Test
    fun `permissions requested match each api level`() {
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), GalleryPermissions.requested(26))
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), GalleryPermissions.requested(32))
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES),
            GalleryPermissions.requested(33)
        )
        assertTrue(readSelected in GalleryPermissions.requested(34))
    }

    @Test
    fun `access reflects full, video-only and selected-only grants`() {
        assertTrue(GalleryPermissions.access(30) { it == Manifest.permission.READ_EXTERNAL_STORAGE }.isFull)
        assertFalse(GalleryPermissions.access(30) { false }.canList)

        val videosOnly = GalleryPermissions.access(33) { it == Manifest.permission.READ_MEDIA_VIDEO }
        assertEquals(GalleryAccess(videos = true, images = false, selectedOnly = false), videosOnly)

        val selected = GalleryPermissions.access(34) { it == readSelected }
        assertEquals(GalleryAccess(videos = false, images = false, selectedOnly = true), selected)
        assertTrue(selected.canList)
        // READ_MEDIA_VISUAL_USER_SELECTED does not exist before 34.
        assertFalse(GalleryPermissions.access(33) { it == readSelected }.canList)
    }

    @Test
    fun `motion photo markers are recognised, plain stills are not`() {
        fun bytes(text: String) = ("\u00ff\u00d8\u00ff\u00e1" + text).toByteArray(Charsets.ISO_8859_1)

        assertTrue(MotionPhotoDetector.hasMotionPhotoMarker(bytes("""GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"""")))
        assertTrue(MotionPhotoDetector.hasMotionPhotoMarker(bytes("""<Camera:MotionPhoto>1</Camera:MotionPhoto>""")))
        assertTrue(MotionPhotoDetector.hasMotionPhotoMarker(bytes("""GCamera:MicroVideo='1'""")))
        assertTrue(MotionPhotoDetector.hasMotionPhotoMarker(bytes("plain"), bytes("...MotionPhoto_Data")))

        assertFalse(MotionPhotoDetector.hasMotionPhotoMarker(bytes("""GCamera:MotionPhoto="0"""")))
        assertFalse(MotionPhotoDetector.hasMotionPhotoMarker(bytes("""GCamera:MotionPhotoVersion="1"""")))
        assertFalse(MotionPhotoDetector.hasMotionPhotoMarker(bytes("Exif\u0000\u0000 ordinary photo")))
    }

    private fun item(name: String, date: Long, kind: GalleryItemKind) = GalleryItem(
        uri = mockk<Uri> { every { this@mockk.toString() } returns "content://media/$name" },
        displayName = name, mimeType = null, dateMs = date, durationMs = null,
        sizeBytes = 100, modifiedSeconds = 1, kind = kind
    )

    private class FakeSource(
        val videos: List<GalleryItem>,
        val stills: List<GalleryItem>,
        val motion: Set<String>
    ) : GallerySource {
        val gates = HashMap<String, CompletableDeferred<Unit>>()
        val detected = mutableListOf<String>()
        var failVideos = false
        var running = 0
        var maxRunning = 0

        override suspend fun videos(): List<GalleryItem> {
            if (failVideos) throw IllegalStateException("provider crashed")
            return videos
        }

        override suspend fun stillCandidates(limit: Int) = stills.take(limit)

        override suspend fun isMotionPhoto(item: GalleryItem): Boolean {
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                gates[item.displayName]?.await()
                detected += item.displayName
                return item.displayName in motion
            } finally {
                running--
            }
        }
    }

    @Test
    fun `videos load at once and only real motion photos join, newest first`() = scope.runTest {
        val source = FakeSource(
            videos = listOf(item("v-old", 10, GalleryItemKind.VIDEO), item("v-new", 50, GalleryItemKind.VIDEO)),
            stills = listOf(item("mp", 40, GalleryItemKind.MOTION_PHOTO), item("still", 30, GalleryItemKind.MOTION_PHOTO)),
            motion = setOf("mp")
        )
        source.gates["mp"] = CompletableDeferred()
        val vm = GalleryViewModel(source)

        vm.onAccessChanged(GalleryAccess(videos = true, images = true, selectedOnly = false))
        runCurrent()
        assertEquals(GalleryPhase.LOADED, vm.uiState.value.phase)
        assertEquals(listOf("v-new", "v-old"), vm.uiState.value.items.map { it.displayName })
        assertTrue(vm.uiState.value.scanningMotionPhotos)

        source.gates.getValue("mp").complete(Unit)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(listOf("v-new", "mp", "v-old"), state.items.map { it.displayName })
        assertFalse(state.scanningMotionPhotos)
        assertEquals(2, state.stillsScanned)
    }

    @Test
    fun `detection is bounded in parallelism and cached across refreshes`() = scope.runTest {
        val stills = (0 until 12).map { item("s$it", it.toLong(), GalleryItemKind.MOTION_PHOTO) }
        val source = FakeSource(emptyList(), stills, motion = setOf("s3"))
        stills.forEach { source.gates[it.displayName] = CompletableDeferred() }
        val vm = GalleryViewModel(source)

        vm.onAccessChanged(GalleryAccess(videos = true, images = true, selectedOnly = false))
        runCurrent()
        assertEquals(GalleryViewModel.DETECTION_PARALLELISM, source.running)
        source.gates.values.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(GalleryViewModel.DETECTION_PARALLELISM, source.maxRunning)
        assertEquals(listOf("s3"), vm.uiState.value.items.map { it.displayName })

        source.detected.clear()
        vm.refresh()
        advanceUntilIdle()
        assertTrue(source.detected.isEmpty())
        assertEquals(listOf("s3"), vm.uiState.value.items.map { it.displayName })
    }

    @Test
    fun `video-only access skips the still scan, errors and revoked access are reported`() = scope.runTest {
        val source = FakeSource(listOf(item("v", 1, GalleryItemKind.VIDEO)), listOf(item("mp", 2, GalleryItemKind.MOTION_PHOTO)), setOf("mp"))
        val vm = GalleryViewModel(source)

        vm.onAccessChanged(GalleryAccess(videos = true, images = false, selectedOnly = false))
        advanceUntilIdle()
        assertEquals(listOf("v"), vm.uiState.value.items.map { it.displayName })
        assertTrue(source.detected.isEmpty())

        source.failVideos = true
        vm.refresh()
        advanceUntilIdle()
        assertEquals(GalleryPhase.ERROR, vm.uiState.value.phase)
        assertEquals("provider crashed", vm.uiState.value.errorMessage)

        vm.onAccessChanged(GalleryAccess(videos = false, images = false, selectedOnly = false))
        assertEquals(GalleryPhase.NEEDS_PERMISSION, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `an empty library reads as empty only once scanning is done`() = scope.runTest {
        val vm = GalleryViewModel(FakeSource(emptyList(), emptyList(), emptySet()))
        vm.onAccessChanged(GalleryAccess(videos = true, images = true, selectedOnly = false))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isEmpty)
    }
}
