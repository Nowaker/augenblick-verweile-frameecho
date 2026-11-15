package com.shangjin.frameecho.app

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OpenGopCaptureDeviceTest {
    @Test
    fun leadingBFramesExportCleanPixelsAtTheirActualPresentationTime() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val evidence = File(context.cacheDir, "open-gop-capture").apply { check(mkdirs() || isDirectory) }
        val source = File(evidence, "source.mp4")
        instrumentation.context.assets.open("frameecho-open-gop.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val extractor = FrameExtractor(context)
        for ((index, expectedUs) in listOf(58 to 1_933_333L, 59 to 1_966_666L, 60 to 2_000_000L)) {
            val (bitmap, frame) = requireNotNull(extractor.extractFrameWithInfo(Uri.fromFile(source), expectedUs))
            try {
                assertEquals(expectedUs, frame.timestampUs)
                assertEquals(640, bitmap.width)
                assertEquals(360, bitmap.height)
                val result = FrameExporter(context).exportStaticFrame(bitmap, frame, ExportConfig(quality = 100))
                check(result is ExportResult.Success) { "JPEG export failed: $result" }
                val output = Uri.parse(result.outputPath)
                val jpeg = File(evidence, "frame-$index.jpg")
                context.contentResolver.openInputStream(output)!!.use { input ->
                    jpeg.outputStream().use { input.copyTo(it) }
                }
                val decoded = requireNotNull(BitmapFactory.decodeFile(jpeg.absolutePath))
                val reference = instrumentation.context.assets.open("frameecho-open-gop-$index.png").use {
                    requireNotNull(BitmapFactory.decodeStream(it))
                }
                try {
                    assertSourceLabelPixels(index, reference, decoded)
                } finally {
                    decoded.recycle()
                    reference.recycle()
                    context.contentResolver.delete(output, null, null)
                }
            } finally {
                extractor.releaseBitmap(bitmap)
            }
        }
    }

}
