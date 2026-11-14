package com.shangjin.frameecho.core.media.metadata

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.shangjin.frameecho.core.media.utils.LogUtils
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo

class MetadataWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkStatic(LogUtils::class)
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
        unmockkStatic(LogUtils::class)
    }

    @Test
    fun `sanitizes make and model attributes before writing to EXIF`() {
        // Arrange
        val maliciousMake = "Sony\nCamera" // Has newline
        val longModel = "b".repeat(200)   // Exceeds 100 chars
        val metadata = VideoMetadata(
            make = maliciousMake,
            model = longModel
        )

        val exif = mockk<ExifInterface>(relaxed = true)

        // Act
        MetadataWriter.writeExifData(exif, metadata)

        // Assert
        val expectedMake = "SonyCamera"
        val expectedModel = "b".repeat(100)

        verify { exif.setAttribute(ExifInterface.TAG_MAKE, expectedMake) }
        verify { exif.setAttribute(ExifInterface.TAG_MODEL, expectedModel) }
    }

    @Test
    fun `sanitizes metadata in user comment`() {
        // Arrange
        val maliciousFileName = "malicious\nfile.mp4"
        // Create a very long string (200 chars)
        val longCodec = "a".repeat(200)

        val metadata = VideoMetadata(
            sourceFileName = maliciousFileName,
            codec = longCodec,
            frameRate = 30.0f
        )

        // Act
        val comment = MetadataWriter.buildUserComment(metadata)

        // Log output for debugging
        println("Generated Comment: $comment")

        // Assert
        // 1. Check for absence of newlines
        assertFalse("Comment should not contain newlines", comment.contains("\n"))

        // 2. Check filename sanitization (newline removed)
        // "malicious\nfile.mp4" -> "maliciousfile.mp4"
        assertTrue("Comment should contain sanitized filename", comment.contains("maliciousfile.mp4"))

        // 3. Check codec truncation (max 100 chars)
        // The codec part should look like "; Codec: aaaaa..." (100 'a's)
        val expectedCodecPart = "a".repeat(100)
        assertTrue("Comment should contain truncated codec", comment.contains(expectedCodecPart))
        assertFalse("Comment should not contain full length codec", comment.contains("a".repeat(101)))
    }

    @Test
    fun `real JPEG retains source camera attributes and precise frame time without stale layout`() {
        val image = temporaryFolder.newFile("frame.jpg")
        image.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
        val frame = CapturedFrame(2, 4, 3, ColorSpaceInfo(), VideoMetadata(
            dateTime = "2023-12-31T23:59:59.999999+05:45", rotation = 90,
            videoWidth = 1920, videoHeight = 1080, exifAttributes = mapOf(
                ExifInterface.TAG_ARTIST to "Photographer",
                ExifInterface.TAG_LENS_MODEL to "35mm",
                ExifInterface.TAG_ORIENTATION to "6",
                ExifInterface.TAG_IMAGE_WIDTH to "1920",
                ExifInterface.TAG_XMP to "stale motion photo offsets"
            )
        ))
        val exif = ExifInterface(image)
        MetadataWriter.writeExifData(exif, frame.exportMetadata(4, 3))
        exif.saveAttributes()

        val saved = ExifInterface(image)
        assertEquals("2024-01-01T00:00:00.000001+05:45", ExifMetadata.captureDateTime(saved))
        assertEquals("000001", saved.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
        assertEquals("+05:45", saved.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertEquals("Photographer", saved.getAttribute(ExifInterface.TAG_ARTIST))
        assertEquals("35mm", saved.getAttribute(ExifInterface.TAG_LENS_MODEL))
        assertEquals("Photographer", ExifMetadata.read(saved)[ExifInterface.TAG_ARTIST])
        assertEquals(1, saved.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals(4, saved.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0))
        assertEquals(3, saved.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0))
        org.junit.Assert.assertNull(saved.getAttribute(ExifInterface.TAG_XMP))
        val block = JpegExifSegment.read(image)
        org.junit.Assert.assertArrayEquals(byteArrayOf(69, 120, 105, 102, 0, 0), block.copyOfRange(0, 6))
    }
}
