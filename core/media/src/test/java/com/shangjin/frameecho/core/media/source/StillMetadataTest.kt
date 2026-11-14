package com.shangjin.frameecho.core.media.source

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StillMetadataTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun teardown() = unmockkAll()

    @Test
    fun `motion still import retains descriptive EXIF and precise capture time`() {
        val image = temporaryFolder.newFile("source.jpg")
        image.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
        ExifInterface(image).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:14 12:34:56")
            setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "123456")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+05:45")
            setAttribute(ExifInterface.TAG_ARTIST, "Fixture photographer")
            setAttribute(ExifInterface.TAG_LENS_MODEL, "Fixture lens")
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            setAttribute(ExifInterface.TAG_XMP, "Source container offsets")
            saveAttributes()
        }

        val metadata = image.inputStream().use { StillMetadata.read(it, "IMG_1234456.JPG") }

        assertEquals("2026-11-14T12:34:56.123456+05:45", metadata.dateTime)
        assertEquals("IMG_1234456.JPG", metadata.sourceFileName)
        assertEquals("Fixture photographer", metadata.exifAttributes[ExifInterface.TAG_ARTIST])
        assertEquals("Fixture lens", metadata.exifAttributes[ExifInterface.TAG_LENS_MODEL])
        assertFalse(metadata.exifAttributes.containsKey(ExifInterface.TAG_ORIENTATION))
        assertFalse(metadata.exifAttributes.containsKey(ExifInterface.TAG_XMP))
    }
}
