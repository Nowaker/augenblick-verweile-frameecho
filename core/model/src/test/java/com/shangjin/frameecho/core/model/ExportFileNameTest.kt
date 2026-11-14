package com.shangjin.frameecho.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFileNameTest {
    private val frame = CapturedFrame(1_234_567, 1920, 1080, ColorSpaceInfo(),
        VideoMetadata(sourceFileName = "holiday.part.one.mp4", frameRate = 30f))

    @Test
    fun `preview token values are exactly those expanded by the exporter`() {
        assertEquals(mapOf("{filename}" to "holiday.part.one", "{timestamp}" to "1700000000000",
            "{position}" to "000001234567", "{frame}" to "00000037"), ExportFileName.tokens(frame, 1700000000000))
        val config = ExportConfig(customFileName = "{filename}_{timestamp}_{position}_{frame}.JPG")
        assertEquals("holiday.part.one_1700000000000_000001234567_00000037.jpg",
            ExportFileName.generate(frame, config, 1700000000000))
    }

    @Test
    fun `auto and literal names retain useful time and position suffixes`() {
        assertEquals("IMG_123_1234.jpg", ExportFileName.generate(frame, ExportConfig(), 123))
        assertEquals("MVIMG_123_1234.jpg", ExportFileName.generate(frame, ExportConfig(), 123, true))
        assertEquals("my_photo_123_1234.jpg", ExportFileName.generate(frame, ExportConfig(customFileName = "my/photo.jpg"), 123))
    }

    @Test
    fun `millisecond rounded positions resolve to the nearest estimated frame`() {
        assertEquals("00000001", ExportFileName.tokens(frame.copy(timestampUs = 33_000), 1)["{frame}"])
        assertEquals("00000002", ExportFileName.tokens(frame.copy(timestampUs = 67_000,
            metadata = frame.metadata.copy(frameRate = 29.97f)), 1)["{frame}"])
    }

    @Test
    fun `selection clock makes preview and export identical despite wall clock changes`() {
        val config = ExportConfig(customFileName = "{filename}_{timestamp}_{frame}", fileNameTimestampMs = 123)
        assertEquals("holiday.part.one_123_00000037.jpg", ExportFileName.generate(frame, config, 456))
        assertEquals("holiday.part.one_123_00000037.jpg", ExportFileName.generate(frame, config, 789))
        assertEquals("IMG_123_1234.jpg", ExportFileName.generate(frame, config.copy(customFileName = null), 456))
        assertEquals("literal_123_1234.jpg", ExportFileName.generate(frame, config.copy(customFileName = "literal"), 789))
    }

    @Test
    fun `expanded names cannot escape directories or exceed filesystem name limits`() {
        val source = frame.copy(metadata = frame.metadata.copy(sourceFileName = "a\u202Eevil.mp4"))
        val result = ExportFileName.generate(source, ExportConfig(customFileName = "../../{filename}/x"), 1)
        assertEquals("a_evil_x.jpg", result)
        assertFalse(result.contains(".."))
        assertTrue(ExportFileName.sanitize("界".repeat(200)).toByteArray().size <= 180)
        assertThrows(IllegalArgumentException::class.java) {
            ExportFileName.generate(frame, ExportConfig(customFileName = "{unknown}"), 1)
        }
    }
}
