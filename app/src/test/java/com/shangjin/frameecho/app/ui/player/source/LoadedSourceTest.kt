package com.shangjin.frameecho.app.ui.player.source

import android.net.Uri
import com.shangjin.frameecho.app.recents.RecentAccess
import com.shangjin.frameecho.core.model.CaptureTime
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadedSourceTest {

    private val decoded = VideoMetadata(
        dateTime = "2026-01-01T00:00:00Z", make = "VideoMake", videoWidth = 1920, videoHeight = 1080,
        frameRate = 30f, durationMs = 3_000, sourceFileName = "motion_photo_123.mp4",
        exifAttributes = mapOf("Software" to "encoder")
    )
    private val still = VideoMetadata(
        dateTime = "2026-05-04T12:30:15.123+02:00", latitude = 50.0, longitude = 19.9, make = "Pixel",
        model = "Pixel 9", iso = 100, dateTimePositionUs = 1_234_000, exifAttributes = mapOf("Artist" to "Ania")
    )

    private fun motion(cover: Long?, mime: String = "image/jpeg") = LoadedSource(
        sourceUri = mockk<Uri>(), displayName = "PXL_20260504.MP.jpg", mimeType = "image/jpeg",
        access = RecentAccess.PERSISTED_GRANT, sourceMetadata = still, coverPresentationUs = cover,
        originalImageLength = 2_000_000, originalImageMimeType = mime
    )

    private val video = LoadedSource(
        mockk(), "holiday.mp4", "video/mp4", RecentAccess.MEDIA_STORE, null, null, null, null
    )

    @Test
    fun `plain videos keep their own metadata but always the original file name`() {
        val merged = video.metadataFor(decoded.copy(sourceFileName = "1234"))
        assertEquals(decoded.copy(sourceFileName = "holiday.mp4"), merged)
        assertFalse(video.isMotionPhoto)
        assertNull(video.coverAnchorUs)
    }

    @Test
    fun `still capture metadata overrides the extracted video, anchored at the cover`() {
        val merged = motion(cover = 1_234_000).metadataFor(decoded)

        assertEquals("2026-05-04T12:30:15.123+02:00", merged.dateTime)
        assertEquals(1_234_000L, merged.dateTimePositionUs)
        assertEquals("Pixel", merged.make)
        assertEquals(50.0, merged.latitude!!, 0.0)
        assertEquals(1920, merged.videoWidth)
        assertEquals(30f, merged.frameRate)
        assertEquals("PXL_20260504.MP.jpg", merged.sourceFileName)
        assertEquals(mapOf("Software" to "encoder", "Artist" to "Ania"), merged.exifAttributes)
    }

    @Test
    fun `an unknown cover never anchors the still date, keeping the video's own date if any`() {
        val source = motion(cover = null)

        // Video has a capture date: it stays, anchored at the clip start.
        val withVideoDate = source.metadataFor(decoded)
        assertEquals("2026-01-01T00:00:00Z", withVideoDate.dateTime)
        assertEquals(0L, withVideoDate.dateTimePositionUs)
        // Descriptive still attributes still apply.
        assertEquals("Pixel", withVideoDate.make)
        assertEquals(50.0, withVideoDate.latitude!!, 0.0)
        assertEquals("PXL_20260504.MP.jpg", withVideoDate.sourceFileName)

        // No video date either: the frame has no capture time rather than an invented one.
        val withoutVideoDate = source.metadataFor(decoded.copy(dateTime = null))
        assertNull(withoutVideoDate.dateTime)
        assertNull(CaptureTime.atFrame(withoutVideoDate, 1_500_000))
    }

    @Test
    fun `only a known cover can be the original still`() {
        assertFalse(motion(cover = null).isAtCover(1_500_000, 30f))
        assertNull(motion(cover = null).originalStillFrame(CapturedFrame(1_500_000, 1, 1, ColorSpaceInfo(), decoded)))

        val known = motion(cover = 1_500_000)
        assertTrue(known.isAtCover(1_500_000 + 10_000, 30f))
        assertFalse(known.isAtCover(1_500_000 + 40_000, 30f))
    }

    @Test
    fun `the capture time of a frame counts from the cover, not the clip start`() {
        val merged = motion(cover = 1_000_000).metadataFor(decoded)
        assertEquals(
            "2026-05-04T12:30:15.623000+02:00",
            CaptureTime.atFrame(merged, 1_500_000)!!.toIsoString()
        )
    }

    @Test
    fun `original still frame sits at the anchor and keeps the capture time`() {
        val source = motion(cover = 1_000_000)
        val metadata = source.metadataFor(decoded)
        // Nearest decoded sample a few microseconds off the cover.
        val sampled = CapturedFrame(1_000_033, 1920, 1080, ColorSpaceInfo(), metadata)
        val original = source.originalStillFrame(sampled)!!

        assertEquals(1_000_000L, original.timestampUs)
        assertEquals(original.timestampUs, original.metadata.dateTimePositionUs)
        assertEquals(
            CaptureTime.atFrame(metadata, 1_000_000)!!.epochMillis,
            CaptureTime.atFrame(original.metadata, original.timestampUs)!!.epochMillis
        )
    }

    @Test
    fun `only jpeg and heic primaries can be copied as the original still`() {
        assertTrue(motion(1, "image/jpeg").canCopyOriginalStill)
        assertTrue(motion(1, "image/heic").canCopyOriginalStill)
        assertFalse(motion(1, "image/avif").canCopyOriginalStill)
        assertFalse(video.canCopyOriginalStill)
    }
}
