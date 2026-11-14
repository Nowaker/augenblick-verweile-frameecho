package com.shangjin.frameecho.core.media.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HeicSupportTest {
    private fun codec(mime: String, encoder: Boolean = true, surfaceInput: Boolean = true): MediaCodecInfo {
        val codec = mockk<MediaCodecInfo>()
        val capabilities = mockk<MediaCodecInfo.CodecCapabilities>()
        capabilities.colorFormats = if (surfaceInput) intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) else intArrayOf()
        every { codec.isEncoder } returns encoder
        every { codec.supportedTypes } returns arrayOf(mime)
        every { codec.getCapabilitiesForType(mime) } returns capabilities
        return codec
    }

    @Test
    fun `HEIC accepts native image or HEVC surface encoders only on supported Android versions`() {
        assertTrue(HeicSupport.supportsEncoding(28, arrayOf(codec(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC))))
        assertTrue(HeicSupport.supportsEncoding(28, arrayOf(codec(MediaFormat.MIMETYPE_VIDEO_HEVC))))
        assertFalse(HeicSupport.supportsEncoding(27, arrayOf(codec(MediaFormat.MIMETYPE_VIDEO_HEVC))))
        assertFalse(HeicSupport.supportsEncoding(28, emptyArray()))
        assertFalse(HeicSupport.supportsEncoding(28, arrayOf(codec(MediaFormat.MIMETYPE_VIDEO_HEVC, encoder = false))))
        assertFalse(HeicSupport.supportsEncoding(28, arrayOf(codec(MediaFormat.MIMETYPE_VIDEO_HEVC, surfaceInput = false))))
        assertFalse(HeicSupport.supportsEncoding(28, arrayOf(codec(MediaFormat.MIMETYPE_VIDEO_AVC))))
    }

    @Test
    fun `unsupported HEIC encoding fails explicitly before creating any output`() {
        val failure = assertThrows(IOException::class.java) {
            ImageEncoder(mockk<Context>()).encode(mockk<Bitmap>(), ExportConfig(format = ExportFormat.HEIC), VideoMetadata())
        }
        assertTrue(failure.message!!.contains("HEIC encoding is unavailable"))
    }

    @Test
    fun `HEIC never aliases the JPEG bitmap compressor`() {
        assertThrows(UnsupportedOperationException::class.java) { ExportFormat.HEIC.toCompressFormat(100) }
    }
}
