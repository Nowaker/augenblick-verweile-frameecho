package com.shangjin.frameecho.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureTimeTest {
    @Test
    fun `frame time carries microseconds across year boundary and retains fractional offset`() {
        val metadata = VideoMetadata(dateTime = "2023-12-31T23:59:59.999999+05:45")
        val result = CaptureTime.atFrame(metadata, 2)!!
        assertEquals("2024-01-01T00:00:00.000001+05:45", result.toIsoString())
        assertEquals("2024:01:01 00:00:00", result.exifDateTime)
        assertEquals("000001", result.exifSubseconds)
        assertEquals("+05:45", result.exifOffset)
        assertEquals(1704046500000L, result.epochMillis)
    }

    @Test
    fun `motion cover anchor is subtracted exactly once`() {
        val metadata = VideoMetadata(dateTime = "2024-01-01T00:00:01.123456Z", dateTimePositionUs = 1_500_000)
        assertEquals("2023-12-31T23:59:59.623457Z", CaptureTime.atFrame(metadata, 1)!!.toIsoString())
        assertEquals(metadata.dateTime, CaptureTime.atFrame(metadata, 1_500_000)!!.toIsoString())
    }

    @Test
    fun `normalization retains all supplied fractional digits and unknown timezone`() {
        val cases = mapOf(
            "20231027T103000.500Z" to "2023-10-27T10:30:00.500Z",
            "2023-10-27T10:30:00.123456789+0530" to "2023-10-27T10:30:00.123456789+05:30",
            "2023:10:27 10:30:00.000001" to "2023-10-27T10:30:00.000001"
        )
        cases.forEach { (input, expected) -> assertEquals(expected, CaptureTime.parse(input)!!.toIsoString()) }
        assertNull(CaptureTime.parse("invalid"))
        assertNull(CaptureTime.parse("2023-02-30T10:30:00Z"))
        assertNull(CaptureTime.parse("2023:10:27 10:30:00")!!.exifOffset)
    }
}
