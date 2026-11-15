package com.shangjin.frameecho.core.media.extraction

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaFormat
import android.util.Size
import java.io.IOException
import kotlin.math.roundToInt

internal class UnsupportedPreviewPixels : IOException("The decoder does not expose supported SDR preview pixels")

internal fun yuvPreviewBitmap(image: Image, format: MediaFormat, maximumSize: Size): Bitmap {
    if (image.format != ImageFormat.YUV_420_888 || image.planes.size != 3) throw UnsupportedPreviewPixels()
    val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
    if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG) throw UnsupportedPreviewPixels()
    val crop = image.cropRect
    val maximum = maxOf(maximumSize.width, maximumSize.height)
    val scale = minOf(1.0, maximum.toDouble() / maxOf(crop.width(), crop.height()))
    val width = (crop.width() * scale).roundToInt().coerceAtLeast(1)
    val height = (crop.height() * scale).roundToInt().coerceAtLeast(1)
    val pixels = IntArray(width * height)
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yData = yPlane.buffer
    val uData = uPlane.buffer
    val vData = vPlane.buffer
    val yBase = yData.position()
    val uBase = uData.position()
    val vBase = vData.position()
    val fullRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE) && format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
    val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else 0
    if (standard == MediaFormat.COLOR_STANDARD_BT2020) throw UnsupportedPreviewPixels()
    val bt709 = standard == MediaFormat.COLOR_STANDARD_BT709
    val redV = if (fullRange) { if (bt709) 403 else 359 } else { if (bt709) 459 else 409 }
    val greenU = if (fullRange) { if (bt709) 48 else 88 } else { if (bt709) 55 else 100 }
    val greenV = if (fullRange) { if (bt709) 120 else 183 } else { if (bt709) 136 else 208 }
    val blueU = if (fullRange) { if (bt709) 475 else 454 } else { if (bt709) 541 else 516 }
    for (y in 0 until height) {
        val sourceY = crop.top + y * crop.height() / height
        for (x in 0 until width) {
            val sourceX = crop.left + x * crop.width() / width
            val luma = yData.get(yBase + sourceY * yPlane.rowStride + sourceX * yPlane.pixelStride).toInt() and 255
            val u = (uData.get(uBase + sourceY / 2 * uPlane.rowStride + sourceX / 2 * uPlane.pixelStride).toInt() and 255) - 128
            val v = (vData.get(vBase + sourceY / 2 * vPlane.rowStride + sourceX / 2 * vPlane.pixelStride).toInt() and 255) - 128
            val luminance = if (fullRange) luma * 256 else (luma - 16).coerceAtLeast(0) * 298
            val red = ((luminance + redV * v + 128) shr 8).coerceIn(0, 255)
            val green = ((luminance - greenU * u - greenV * v + 128) shr 8).coerceIn(0, 255)
            val blue = ((luminance + blueU * u + 128) shr 8).coerceIn(0, 255)
            pixels[y * width + x] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
    if (rotation % 360 == 0) return bitmap
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, width, height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    } finally {
        bitmap.recycle()
    }
}
