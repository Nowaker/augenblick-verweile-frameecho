package com.shangjin.frameecho.app

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shangjin.frameecho.app.recents.RecentAccess
import com.shangjin.frameecho.app.ui.export.forFileNamePreview
import com.shangjin.frameecho.app.ui.player.PlayerUiState
import com.shangjin.frameecho.app.ui.player.source.LoadedSource
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.export.HeicSupport
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.media.source.MediaSourceResolver
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFileName
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.OffsetDateTime

@RunWith(AndroidJUnit4::class)
class MediaWorkflowDeviceTest {
    private lateinit var context: Context
    private lateinit var source: File
    private val outputs = mutableListOf<Uri>()

    @Before
    fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        source = File.createTempFile("frameecho_fixture_", ".mp4", context.cacheDir)
        instrumentation.context.assets.open("frameecho-fixture.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
    }

    @After
    fun cleanup() {
        outputs.forEach { context.contentResolver.delete(it, null, null) }
        source.delete()
    }

    @Test
    fun jpegExportUsesActualFrameTimeAndPreservesExif() = runBlocking {
        val extractor = FrameExtractor(context)
        val (bitmap, decoded) = requireNotNull(extractor.extractFrameWithInfo(Uri.fromFile(source), 1_233_000L))
        try {
            assertEquals(1_233_333L, decoded.timestampUs)
            val frame = decoded.copy(metadata = decoded.metadata.copy(
                dateTime = "2026-11-14T12:34:56.123456+05:45",
                sourceFileName = "IMG_1234456.JPG",
                exifAttributes = mapOf(ExifInterface.TAG_ARTIST to "FrameEcho device fixture")
            ))
            val config = ExportConfig(customFileName = "{filename}_{position}_{frame}")

            val result = save(FrameExporter(context).exportStaticFrame(bitmap, frame, config))

            assertEquals(ExportFormat.JPEG, result.format)
            assertTrue(result.metadataPreserved)
            val expectedTime = OffsetDateTime.parse("2026-11-14T12:34:57.356789+05:45").toInstant()
            context.contentResolver.openInputStream(Uri.parse(result.outputPath))!!.use { input ->
                val exif = ExifInterface(input)
                assertEquals("2026:11:14 12:34:57", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertEquals("356789", exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
                assertEquals("+05:45", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                assertEquals("FrameEcho device fixture", exif.getAttribute(ExifInterface.TAG_ARTIST))
                assertEquals(bitmap.width, exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0))
            }
            context.contentResolver.query(
                Uri.parse(result.outputPath), arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null
            )!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(expectedTime.toEpochMilli(), cursor.getLong(0))
            }
            context.contentResolver.openFileDescriptor(Uri.parse(result.outputPath), "r")!!.use { descriptor ->
                val path = Paths.get("/proc/self/fd/${descriptor.fd}")
                val actualTime = Files.getLastModifiedTime(path).toInstant()
                val inodeOwner = Os.fstat(descriptor.fileDescriptor).st_uid
                if (actualTime != expectedTime && inodeOwner != Process.myUid()) {
                    assertTrue("A provider-owned inode must report its timestamp permission denial",
                        result.timestampWarnings.any { it.contains("errno 1)") || it.contains("errno 13)") })
                } else {
                    assertEquals("Permitted filesystem mtime retains the selected frame microseconds", expectedTime, actualTime)
                }
            }
        } finally {
            extractor.releaseBitmap(bitmap)
        }
    }

    @Test
    fun exportedMotionPhotoCanBeReopenedAndItsOriginalStillCopiedExactly() = runBlocking {
        val extractor = FrameExtractor(context)
        val (bitmap, frame) = requireNotNull(extractor.extractFrameWithInfo(Uri.fromFile(source), 1_000_000L))
        try {
            val motion = save(FrameExporter(context).exportMotionPhoto(
                Uri.fromFile(source), bitmap, frame, ExportConfig(motionPhoto = true)
            ))
            val motionUri = Uri.parse(motion.outputPath)

            val resolved = MediaSourceResolver(context).resolve(motionUri)
            try {
                val reopened = requireNotNull(extractor.extractFrameWithInfo(resolved.videoUri, 0L))
                extractor.releaseBitmap(reopened.first)
                val imageLength = requireNotNull(resolved.originalImageLength)
                val coverUs = requireNotNull(resolved.presentationTimestampUs)
                val loaded = LoadedSource(
                    motionUri, resolved.sourceFileName, "image/jpeg", RecentAccess.MEDIA_STORE,
                    resolved.sourceMetadata, coverUs, imageLength, resolved.originalImageMimeType
                )
                val selected = frame.copy(timestampUs = coverUs + 33L, metadata = loaded.metadataFor(frame.metadata))
                val selection = PlayerUiState(
                    source = loaded, displayedFrameUs = selected.timestampUs, videoFrameRate = selected.metadata.frameRate,
                    frameMetadata = selected.metadata, fileNameTimestampMs = 123L,
                    exportConfig = ExportConfig(originalImage = true, format = ExportFormat.HEIC,
                        customFileName = "{filename}_{timestamp}_{position}")
                )
                assertTrue(selection.originalStillAvailable)
                val previewName = ExportFileName.generate(
                    requireNotNull(selection.previewFrame), selection.effectiveExportConfig.forFileNamePreview("image/jpeg"), 999L
                )
                val still = requireNotNull(loaded.originalStillFrame(selected))
                val copy = save(FrameExporter(context).exportOriginalImage(
                    motionUri, imageLength, requireNotNull(resolved.originalImageMimeType),
                    still, selection.effectiveExportConfig
                ))

                context.contentResolver.query(
                    Uri.parse(copy.outputPath), arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null
                )!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(previewName, cursor.getString(0))
                }
                val sourceBytes = context.contentResolver.openInputStream(motionUri)!!.use { it.readBytes() }
                val copiedBytes = context.contentResolver.openInputStream(Uri.parse(copy.outputPath))!!.use { it.readBytes() }
                assertArrayEquals(sourceBytes.copyOfRange(0, imageLength.toInt()), copiedBytes)
                assertTrue(copy.originalImagePreserved)
            } finally {
                resolved.release()
            }
        } finally {
            extractor.releaseBitmap(bitmap)
        }
    }

    @Test
    fun heicExportProducesARealImageOrAnExplicitUnsupportedResult() = runBlocking {
        val extractor = FrameExtractor(context)
        val (bitmap, frame) = requireNotNull(extractor.extractFrameWithInfo(Uri.fromFile(source), 0L))
        try {
            val supported = HeicSupport.isSupported()
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("heic_encoder_available", supported.toString())
            })
            val result = FrameExporter(context).exportStaticFrame(bitmap, frame, ExportConfig(format = ExportFormat.HEIC))

            if (supported) {
                val saved = save(result)
                assertEquals(ExportFormat.HEIC, saved.format)
                assertTrue(saved.metadataPreserved)
                context.contentResolver.openInputStream(Uri.parse(saved.outputPath))!!.use { input ->
                    val header = ByteArray(12)
                    assertEquals(12, input.read(header))
                    assertEquals("ftyp", String(header, 4, 4, Charsets.US_ASCII))
                }
                context.contentResolver.openInputStream(Uri.parse(saved.outputPath))!!.use { input ->
                    val decoded = requireNotNull(BitmapFactory.decodeStream(input)) { "HEIC file must decode as a real image" }
                    try {
                        assertEquals(bitmap.width, decoded.width)
                        assertEquals(bitmap.height, decoded.height)
                    } finally {
                        decoded.recycle()
                    }
                }
                context.contentResolver.openInputStream(Uri.parse(saved.outputPath))!!.use { input ->
                    val exif = ExifInterface(input)
                    assertEquals("2026:11:14 12:00:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                    assertEquals("+00:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                }
            } else {
                assertTrue("Unsupported HEIC must fail explicitly rather than write a disguised JPEG", result is ExportResult.Error)
            }
        } finally {
            extractor.releaseBitmap(bitmap)
        }
    }

    private fun save(result: ExportResult): ExportResult.Success {
        check(result is ExportResult.Success) { "Native export failed: $result" }
        outputs += Uri.parse(result.outputPath)
        return result
    }
}
