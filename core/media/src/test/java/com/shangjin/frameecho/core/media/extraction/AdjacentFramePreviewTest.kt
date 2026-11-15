package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdjacentFramePreviewTest {
    @Before
    fun setupAndroidLogging() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `revisiting a decoded frame uses cache and release closes the decoder`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val uri = mockk<Uri>()
        mockkConstructor(MediaMetadataRetriever::class)
        mockkConstructor(PreviewFrameReader::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(context, uri) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } returns "100"
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit
        coEvery { anyConstructed<PreviewFrameReader>().release() } returns Unit
        coEvery { anyConstructed<PreviewFrameReader>().read(any()) } coAnswers {
            val bitmap = mockk<Bitmap>(relaxed = true).also { bitmap ->
                every { bitmap.allocationByteCount } returns 40_000
            }
            DecodedFrame(bitmap, firstArg())
        }
        val preview = AdjacentFramePreview(context, uri, this)
        try {
            withTimeout(5_000L) {
                for (position in listOf(100L, 200L, 100L)) {
                    preview.request(position, 0f, 1_000L)
                    preview.preview.first { it?.positionMs == position }
                }
            }
        } finally {
            preview.release()
        }

        coVerify(exactly = 1) { anyConstructed<PreviewFrameReader>().read(100_000L) }
        coVerify(exactly = 1) { anyConstructed<PreviewFrameReader>().release() }
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().setDataSource(context, uri) }
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
        assertEquals(null, preview.preview.value)
    }

    @Test
    fun `completed frames remain visible while a newer target is waiting`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val uri = mockk<Uri>()
        val decoding = CompletableDeferred<Unit>()
        val allowDecode = CountDownLatch(1)
        val allowNewestDecode = CountDownLatch(1)
        mockkConstructor(MediaMetadataRetriever::class)
        mockkConstructor(PreviewFrameReader::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(context, uri) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } returns "100"
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit
        coEvery { anyConstructed<PreviewFrameReader>().release() } returns Unit
        coEvery { anyConstructed<PreviewFrameReader>().read(any()) } coAnswers {
            if (firstArg<Long>() == 100_000L) {
                decoding.complete(Unit)
                check(allowDecode.await(5L, TimeUnit.SECONDS)) { "Test never released the native decoder" }
            }
            if (firstArg<Long>() == 300_000L) {
                check(allowNewestDecode.await(5L, TimeUnit.SECONDS)) { "Test never released the newest decode" }
            }
            val bitmap = mockk<Bitmap>(relaxed = true).also { bitmap ->
                every { bitmap.allocationByteCount } returns 40_000
            }
            DecodedFrame(bitmap, firstArg())
        }
        val preview = AdjacentFramePreview(context, uri, this)
        val firstPublished = async(start = CoroutineStart.UNDISPATCHED) {
            preview.preview.filterNotNull().first()
        }
        try {
            withTimeout(5_000L) {
                preview.request(100L, 0f, 1_000L)
                decoding.await()
                preview.request(200L, 0f, 1_000L)
                preview.request(300L, 0f, 1_000L)
                allowDecode.countDown()
                assertEquals(100L, firstPublished.await().positionMs)
                allowNewestDecode.countDown()
                preview.preview.first { it?.positionMs == 300L }
            }
        } finally {
            allowDecode.countDown()
            allowNewestDecode.countDown()
            firstPublished.cancel()
            preview.release()
        }
        coVerify(exactly = 0) { anyConstructed<PreviewFrameReader>().read(200_000L) }
    }

    @Test
    fun `decoder failure retires the published preview so the player can take over`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val uri = mockk<Uri>()
        mockkConstructor(MediaMetadataRetriever::class)
        mockkConstructor(PreviewFrameReader::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(context, uri) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } returns "100"
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit
        coEvery { anyConstructed<PreviewFrameReader>().release() } returns Unit
        val bitmap = mockk<Bitmap>(relaxed = true).also { bitmap ->
                every { bitmap.allocationByteCount } returns 40_000
            }
        coEvery { anyConstructed<PreviewFrameReader>().read(100_000L) } returns DecodedFrame(bitmap, 100_000L)
        coEvery { anyConstructed<PreviewFrameReader>().read(200_000L) } throws
            IllegalStateException("Decoder stopped")
        val preview = AdjacentFramePreview(context, uri, this)
        try {
            withTimeout(5_000L) {
                preview.request(100L, 0f, 1_000L)
                preview.preview.first { it?.positionMs == 100L }
                preview.request(200L, 0f, 1_000L)
                preview.preview.first { it == null }
            }
        } finally {
            preview.release()
        }
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }

    @Test
    fun `preloads ahead first and retains reverse neighbours`() {
        assertEquals(
            listOf(1_040_000L, 1_080_000L, 1_120_000L, 1_160_000L, 960_000L, 920_000L),
            AdjacentFramePreview.adjacentTimestampsUs(1_000L, 25f, 5_000L, 1)
        )
        assertEquals(
            listOf(960_000L, 920_000L, 880_000L, 840_000L, 1_040_000L, 1_080_000L),
            AdjacentFramePreview.adjacentTimestampsUs(1_000L, 25f, 5_000L, -1)
        )
    }

    @Test
    fun `fractional frame rates preload the floored player seek positions`() {
        assertEquals(
            listOf(66_000L, 100_000L, 133_000L, 166_000L, 0L),
            AdjacentFramePreview.adjacentTimestampsUs(33L, 29.97f, 1_000L, 1)
        )
    }

    @Test
    fun `preload window never crosses either end of the video`() {
        assertEquals(
            listOf(40_000L, 80_000L),
            AdjacentFramePreview.adjacentTimestampsUs(0L, 25f, 100L, -1)
        )
        assertEquals(
            listOf(40_000L, 0L),
            AdjacentFramePreview.adjacentTimestampsUs(80L, 25f, 100L, 1)
        )
    }

    @Test
    fun `unknown invalid and nonfinite frame rates do not schedule speculative decoding`() {
        for (frameRate in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(AdjacentFramePreview.adjacentTimestampsUs(100L, frameRate, 1_000L, 1).isEmpty())
        }
        assertTrue(AdjacentFramePreview.adjacentTimestampsUs(100L, 25f, 0L, 1).isEmpty())
    }
}
