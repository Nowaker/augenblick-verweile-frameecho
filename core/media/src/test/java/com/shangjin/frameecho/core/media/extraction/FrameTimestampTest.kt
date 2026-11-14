package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.shangjin.frameecho.core.media.colorspace.ColorSpaceDetector
import com.shangjin.frameecho.core.media.metadata.MetadataExtractor
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameTimestampTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `export timestamp is the closest actual sample PTS including reordered B frames`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val uri = mockk<Uri>()
        val format = mockk<MediaFormat>()
        val bitmap = mockk<Bitmap> {
            every { width } returns 320
            every { height } returns 180
        }
        mockkConstructor(MediaExtractor::class, MediaMetadataRetriever::class)
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        every { format.getString(MediaFormat.KEY_MIME) } returns "video/avc"
        every { anyConstructed<MediaExtractor>().setDataSource(context, uri, null) } returns Unit
        every { anyConstructed<MediaExtractor>().trackCount } returns 1
        every { anyConstructed<MediaExtractor>().getTrackFormat(0) } returns format
        every { anyConstructed<MediaExtractor>().selectTrack(0) } returns Unit
        every { anyConstructed<MediaExtractor>().release() } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(context, uri) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns bitmap
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit
        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns format
        every { MetadataExtractor.extract(context, uri, any(), format) } returns VideoMetadata()
        every { ColorSpaceDetector.detect(format) } returns ColorSpaceInfo()

        val cases = listOf(
            Triple(listOf(0L, 66_666L, 33_333L, 100_000L), 33_000L, 33_333L),
            Triple(listOf(0L, 66_666L, 33_333L, 100_000L), 50_000L, 66_666L),
            Triple(listOf(0L, 80_000L, 20_000L, 100_000L), 50_000L, 20_000L),
            Triple(listOf(0L, 66_666L, 33_333L), 999_000L, 66_666L)
        )
        for ((timestamps, request, expected) in cases) {
            var index = 0
            every { anyConstructed<MediaExtractor>().seekTo(any(), any()) } answers { index = 0 }
            every { anyConstructed<MediaExtractor>().sampleTime } answers { timestamps.getOrElse(index) { -1L } }
            every { anyConstructed<MediaExtractor>().sampleFlags } answers {
                if (index == 0 || index == 3) MediaExtractor.SAMPLE_FLAG_SYNC else 0
            }
            every { anyConstructed<MediaExtractor>().advance() } answers {
                index++
                index < timestamps.size
            }

            val result = requireNotNull(FrameExtractor(context).extractFrameWithInfo(uri, request))

            assertEquals(expected, result.second.timestampUs)
            verify { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(expected, MediaMetadataRetriever.OPTION_CLOSEST) }
        }
        verify(exactly = cases.size) { anyConstructed<MediaExtractor>().release() }
    }
}
