package com.shangjin.frameecho.app

import android.net.Uri
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shangjin.frameecho.core.media.extraction.AdjacentFramePreview
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToLong

@RunWith(AndroidJUnit4::class)
class AdjacentPreviewDeviceTest {
    @Test
    fun rotatedContainerPreviewUsesTheSourceOrientation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "adjacent-preview-rotated.mp4")
        instrumentation.context.assets.open("frameecho-open-gop-rotated.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val preview = AdjacentFramePreview(context, Uri.fromFile(source), this)
        try {
            withTimeout(30_000L) {
                preview.request(1_966L, 30f, 8_000L)
                val shown = requireNotNull(preview.preview.first { it?.positionMs == 1_966L })
                assertEquals(1_966_666L, shown.presentationTimeUs)
                assertEquals(360, shown.bitmap.width)
                assertEquals(640, shown.bitmap.height)
            }
        } finally {
            preview.release()
        }
    }

    @Test
    fun previewSeeksRemainUsableAndDoNotBlockFullResolutionCapture() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "adjacent-preview-device.mp4")
        instrumentation.context.assets.open("frameecho-open-gop.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val uri = Uri.fromFile(source)
        val preview = AdjacentFramePreview(context, uri, this)
        try {
            withTimeout(30_000L) {
                for (index in 0L..10L) {
                    val positionMs = index * 1_000L / 30L
                    preview.request(positionMs, 30f, 8_000L)
                    val shown = requireNotNull(preview.preview.first { it?.positionMs == positionMs })
                    assertEquals(index, (shown.presentationTimeUs * 30.0 / 1_000_000.0).roundToLong())
                    assertTrue(shown.bitmap.width <= AdjacentFramePreview.MAX_PREVIEW_DIMENSION)
                    assertTrue(shown.bitmap.height <= AdjacentFramePreview.MAX_PREVIEW_DIMENSION)
                }
                for ((index, positionMs) in listOf(58 to 1_933L, 59 to 1_966L, 60 to 2_000L)) {
                    preview.request(positionMs, 30f, 8_000L)
                    val shown = requireNotNull(preview.preview.first { it?.positionMs == positionMs })
                    val reference = instrumentation.context.assets.open("frameecho-open-gop-$index.png").use {
                        requireNotNull(BitmapFactory.decodeStream(it))
                    }
                    try {
                        assertSourceLabelPixels(index, reference, shown.bitmap)
                    } finally {
                        reference.recycle()
                    }
                }
                val extractor = FrameExtractor(context)
                val (bitmap, frame) = requireNotNull(extractor.extractFrameWithInfo(uri, 1_966_666L))
                try {
                    assertEquals(1_966_666L, frame.timestampUs)
                    assertEquals(640, bitmap.width)
                    assertEquals(360, bitmap.height)
                } finally {
                    extractor.releaseBitmap(bitmap)
                }
            }
        } finally {
            preview.release()
        }
    }
}
