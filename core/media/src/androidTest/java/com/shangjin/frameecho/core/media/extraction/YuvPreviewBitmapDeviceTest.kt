package com.shangjin.frameecho.core.media.extraction

import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.media.MediaFormat
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class YuvPreviewBitmapDeviceTest {
    @Test
    fun nativeStridedPlanesUseTheirDeclaredSdrMatrixAndRange() {
        val cases = listOf(
            intArrayOf(MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_RANGE_LIMITED, 16, 128, 128, 0, 0, 0),
            intArrayOf(MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_RANGE_LIMITED, 235, 128, 128, 255, 255, 255),
            intArrayOf(MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_RANGE_LIMITED, 63, 102, 240, 255, 0, 0),
            intArrayOf(MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_RANGE_FULL, 255, 128, 128, 255, 255, 255)
        )
        ImageReader.newInstance(8, 6, ImageFormat.YUV_420_888, 2).use { reader ->
            ImageWriter.newInstance(reader.surface, 1).use { writer ->
                for (case in cases) {
                    writer.dequeueInputImage().use { image ->
                        fill(image, case[2], case[3], case[4])
                        image.cropRect = Rect(2, 0, 6, 6)
                        val format = MediaFormat().apply {
                            setInteger(MediaFormat.KEY_COLOR_STANDARD, case[0])
                            setInteger(MediaFormat.KEY_COLOR_RANGE, case[1])
                        }
                        val bitmap = yuvPreviewBitmap(image, format, Size(960, 960))
                        try {
                            assertEquals(4, bitmap.width)
                            assertEquals(6, bitmap.height)
                            for ((x, y) in listOf(0 to 0, 3 to 5)) {
                                val pixel = bitmap.getPixel(x, y)
                                assertTrue("Unexpected red channel", abs(Color.red(pixel) - case[5]) <= 2)
                                assertTrue("Unexpected green channel", abs(Color.green(pixel) - case[6]) <= 2)
                                assertTrue("Unexpected blue channel", abs(Color.blue(pixel) - case[7]) <= 2)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun croppedPortraitRotationIsAppliedOnceAfterDownsampling() {
        ImageReader.newInstance(8, 6, ImageFormat.YUV_420_888, 2).use { reader ->
            ImageWriter.newInstance(reader.surface, 1).use { writer ->
                writer.dequeueInputImage().use { image ->
                    fill(image, 16, 128, 128)
                    val plane = image.planes[0]
                    for (x in 0 until 8) plane.buffer.put(5 * plane.rowStride + x * plane.pixelStride, 235.toByte())
                    image.cropRect = Rect(2, 0, 6, 6)
                    val bitmap = yuvPreviewBitmap(image, MediaFormat().apply { setInteger(MediaFormat.KEY_ROTATION, 90) }, Size(960, 960))
                    try {
                        assertEquals(6, bitmap.width)
                        assertEquals(4, bitmap.height)
                        assertTrue(Color.red(bitmap.getPixel(0, 0)) >= 253)
                        assertTrue(Color.red(bitmap.getPixel(5, 0)) <= 2)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun fill(image: Image, y: Int, u: Int, v: Int) {
        for ((index, value) in listOf(y, u, v).withIndex()) {
            val plane = image.planes[index]
            val width = if (index == 0) image.width else (image.width + 1) / 2
            val height = if (index == 0) image.height else (image.height + 1) / 2
            val buffer = plane.buffer
            val start = buffer.position()
            for (row in 0 until height) {
                for (column in 0 until width) buffer.put(start + row * plane.rowStride + column * plane.pixelStride, value.toByte())
            }
        }
    }
}
